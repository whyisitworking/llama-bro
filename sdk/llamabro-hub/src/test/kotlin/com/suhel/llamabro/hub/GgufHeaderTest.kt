package com.suhel.llamabro.hub

import com.suhel.llamabro.hub.internal.GgufHeader
import com.suhel.llamabro.hub.internal.HeaderFetch
import com.suhel.llamabro.hub.internal.HttpTransport
import com.suhel.llamabro.hub.internal.JvmStorage
import com.suhel.llamabro.hub.internal.Resolver
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

internal class GgufHeaderTest {
    @Test fun dataOffsetMatchesUpstream() {
        val modelDir = System.getProperty("llamabro.hub.model.dir")
        val goldenDir = System.getProperty("llamabro.hub.golden.dir")
        assumeTrue(modelDir != null && goldenDir != null)
        val offsets = Json.parseToJsonElement(File(goldenDir, "gguf-offsets.json").readText())
            .jsonObject.getValue("files").jsonArray
        for (entry in offsets) {
            val path = entry.jsonObject.getValue("path").jsonPrimitive.content
            val file = File(path)
            assertEquals(modelDir, file.parent)
            val bytes = file.inputStream().use { it.readNBytes(64 shl 20) }
            assertEquals(file.name, entry.jsonObject.getValue("offset").jsonPrimitive.long,
                GgufHeader.dataOffset(bytes))
        }
    }

    @Test fun needsMoreBytesReturnsNull() {
        val bytes = syntheticHeader()
        assertNull(GgufHeader.dataOffset(bytes.copyOf(8 shl 20)))
        assertTrue(GgufHeader.dataOffset(bytes)!! > (12L shl 20))
    }

    @Test fun headerGrowsUntilComplete() = runBlocking {
        val source = Files.createTempFile("hub-big-header-", ".gguf").toFile()
        source.writeBytes(syntheticHeader())
        val repos = buildJsonObject {
            put("test/largeheader", buildJsonObject {
                put("files", buildJsonArray {
                    add(buildJsonObject { put("path", "model-Q4_K_M.gguf"); put("source", source.absolutePath) })
                })
            })
        }
        FakeHub(repos).use { server ->
            val transport = HttpTransport(server.apiUrl, null, null)
            val cache = Files.createTempDirectory("hub-header-cache-").toFile()
            val listing = Resolver(server.apiUrl, cache, transport, JvmStorage()).list("test/largeheader")
            val file = listing.files.single()
            val selected = HubFile(file.path, HubFile.Role.Model, file.sizeBytes, file.oid, false).also {
                it.sourceRepo = "test/largeheader"
                it.sourceCommit = listing.commit
            }
            server.reset()
            val stub = File(cache, "stub/${selected.path}")
            val header = HeaderFetch(server.apiUrl, transport).fetch(selected, stub)
            val expected = GgufHeader.dataOffset(source.readBytes())!!
            assertEquals(expected, header.dataOffset)
            val ranges = server.log().map { it.jsonObject["range"]?.jsonPrimitive?.content }
                .filter { it != null && it != "null" }.distinct()
            // The second round asks only for the bytes the first one didn't bring.
            assertEquals(listOf("bytes=0-8388607", "bytes=8388608-${source.length() - 1}"), ranges)
            assertEquals(source.length(), header.bytesFetched)
            assertEquals(source.length(), stub.length())
            assertTrue(source.readBytes().copyOf(expected.toInt()).contentEquals(stub.readBytes().copyOf(expected.toInt())))
        }
    }

    @Test fun badOrCutHeadersAreHubErrors() = runBlocking {
        val notGguf = Files.createTempFile("hub-not-gguf-", ".gguf").toFile().apply { writeBytes(ByteArray(4096)) }
        val cut = Files.createTempFile("hub-cut-gguf-", ".gguf").toFile()
            .apply { writeBytes(syntheticHeader().copyOf(1 shl 20)) } // valid start, ends inside the 12 MiB header
        val repos = buildJsonObject {
            for ((name, file) in listOf("test/notgguf" to notGguf, "test/cutgguf" to cut)) {
                put(name, buildJsonObject {
                    put("files", buildJsonArray {
                        add(buildJsonObject { put("path", "model-Q4_K_M.gguf"); put("source", file.absolutePath) })
                    })
                })
            }
        }
        FakeHub(repos).use { server ->
            val transport = HttpTransport(server.apiUrl, null, null)
            val cache = Files.createTempDirectory("hub-bad-header-").toFile()
            for ((repo, expected) in listOf("test/notgguf" to "not a valid GGUF", "test/cutgguf" to "ends inside")) {
                val listing = Resolver(server.apiUrl, cache, transport, JvmStorage()).list(repo)
                val file = listing.files.single()
                val selected = HubFile(file.path, HubFile.Role.Model, file.sizeBytes, file.oid, false).also {
                    it.sourceRepo = repo
                    it.sourceCommit = listing.commit
                }
                try {
                    HeaderFetch(server.apiUrl, transport).fetch(selected, File(cache, "stub/$repo.gguf"))
                    org.junit.Assert.fail("$repo should fail")
                } catch (e: HubException.NotFound) {
                    assertTrue("$repo: ${e.message}", e.message!!.contains(expected))
                }
            }
        }
    }

    private fun syntheticHeader(): ByteArray {
        val padding = 12 shl 20
        val buffer = ByteBuffer.allocate(padding + 512).order(ByteOrder.LITTLE_ENDIAN)
        fun string(value: String) {
            val bytes = value.toByteArray()
            buffer.putLong(bytes.size.toLong())
            buffer.put(bytes)
        }
        buffer.put("GGUF".toByteArray())
        buffer.putInt(3)
        buffer.putLong(1)
        buffer.putLong(1)
        string("padding")
        buffer.putInt(8)
        buffer.putLong(padding.toLong())
        buffer.put(ByteArray(padding))
        string("weights")
        buffer.putInt(1)
        buffer.putLong(1)
        buffer.putInt(0)
        buffer.putLong(0)
        buffer.position((buffer.position() + 31) and -32)
        buffer.putInt(0)
        return buffer.array().copyOf(buffer.position())
    }
}
