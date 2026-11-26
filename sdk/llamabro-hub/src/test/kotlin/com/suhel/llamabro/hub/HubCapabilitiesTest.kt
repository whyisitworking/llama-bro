package com.suhel.llamabro.hub

import com.suhel.llamabro.hub.SearchFixtures.ENDPOINT
import com.suhel.llamabro.hub.SearchFixtures.ScriptedTransport
import com.suhel.llamabro.hub.SearchFixtures.gguf
import com.suhel.llamabro.hub.SearchFixtures.hub
import com.suhel.llamabro.hub.SearchFixtures.info
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.Files

/** What an installed model's headers say it can do, read offline (D155). */
internal class HubCapabilitiesTest {
    private suspend fun installedWithProjector(
        transport: ScriptedTransport,
        cache: File,
        options: PlanOptions.() -> Unit = {},
    ): LlamaBroHub {
        transport.json("${ENDPOINT}api/models/ggml-org/Cached-GGUF", info(base = null))
        transport.listing(
            "ggml-org/Cached-GGUF",
            listOf("Cached-Q4_K_M.gguf" to 0L, "mmproj-Cached-F16.gguf" to 0L),
        )
        transport.gguf(
            "ggml-org/Cached-GGUF",
            "Cached-Q4_K_M.gguf",
            gguf(mapOf("tokenizer.chat_template" to "{% if enable_thinking %}think{% endif %}")),
        )
        transport.gguf(
            "ggml-org/Cached-GGUF",
            "mmproj-Cached-F16.gguf",
            gguf(mapOf("clip.has_vision_encoder" to true, "clip.has_audio_encoder" to false)),
        )
        return hub(transport, cache).also { it.download(HfModel("ggml-org/Cached-GGUF"), options).toList() }
    }

    @Test fun capabilitiesReadTheTemplateAndTheProjectorFromDisk() =
        runBlocking {
            val transport = ScriptedTransport()
            val client = installedWithProjector(transport, Files.createTempDirectory("hub-caps-").toFile())
            transport.requests.clear()
            transport.attempts = 0
            transport.failWith = HubException.Network("offline", IOException("no route"), true)

            val capabilities = client.capabilities(HfModel("ggml-org/Cached-GGUF"))

            assertTrue(capabilities!!.chatTemplate!!.contains("enable_thinking"))
            assertTrue(capabilities.takesImages)
            assertFalse(capabilities.takesAudio)
            assertEquals("no request got through (D155)", emptyList<String>(), transport.requests)
        }

    @Test fun capabilitiesOfAModelNotOnThePhoneIsNull() =
        runBlocking {
            val transport = ScriptedTransport()
            transport.model("ggml-org/Absent-GGUF", "Absent-Q4_K_M.gguf", base = null)

            assertNull(hub(transport).capabilities(HfModel("ggml-org/Absent-GGUF")))
            assertEquals(emptyList<String>(), transport.requests)
        }

    @Test fun aCopyWithoutItsProjectorTakesNoImages() =
        runBlocking {
            val transport = ScriptedTransport()
            val client =
                installedWithProjector(transport, Files.createTempDirectory("hub-caps-").toFile()) { mmproj = false }

            val capabilities = client.capabilities(HfModel("ggml-org/Cached-GGUF"))!!

            assertFalse(capabilities.takesImages)
            assertFalse(capabilities.takesAudio)
        }

    @Test fun aCorruptHeaderThrowsIllegalArgument() =
        runBlocking {
            val transport = ScriptedTransport()
            val cache = Files.createTempDirectory("hub-caps-").toFile()
            val client = installedWithProjector(transport, cache)
            val file = cache.walkTopDown().first { it.isFile && it.name == "Cached-Q4_K_M.gguf" }
            RandomAccessFile(file, "rw").use { it.write("NOPE".toByteArray()) }

            try {
                client.capabilities(HfModel("ggml-org/Cached-GGUF"))
                fail("a header that is not GGUF is reported, not guessed")
            } catch (expected: IllegalArgumentException) {
                assertTrue(expected.message!!.isNotEmpty())
            }
        }
}
