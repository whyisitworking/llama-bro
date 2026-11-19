package com.suhel.llamabro

import com.suhel.llamabro.internal.JniEngine
import com.suhel.llamabro.spi.EngineAttachment
import com.suhel.llamabro.spi.EngineModel
import com.suhel.llamabro.spi.NativeError
import com.suhel.llamabro.spi.RequestSink
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileDescriptor
import java.io.FileInputStream
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Attachments through JNI on the real core (D84, F55): the same clip as a path, a descriptor and bytes gives the same
 * transcription, a chat placeholder becomes the file's data URL, and `readFd` reads a descriptor whole and leaves it
 * open. Upstream's audio test model (`fetch-test-models.sh`) and a clip spoken at check time; run by
 * sdk/scripts/check.sh's e2e step.
 */
class HostNativeAttachmentTest {
    private lateinit var model: EngineModel
    private lateinit var clip: File

    @Before
    fun load() {
        val lib = System.getProperty("llamabro.host.lib")
        val models = System.getProperty("llamabro.host.models")
        val clipPath = System.getProperty("llamabro.host.clip")
        assumeTrue("host end-to-end runs from sdk/scripts/check.sh", lib != null && models != null && clipPath != null)
        clip = File(clipPath!!)
        assertEquals(null, JniEngine.init("""{"backend_dir":"$lib","log_level":"warn"}"""))
        val gguf = File(models!!, "tinyLFM3-Omni-for-testing-Q2_K.gguf").path
        val mmproj = File(models, "mmproj-tinyLFM3-Omni-for-testing-Q2_K.gguf").path
        model = JniEngine.load("""{"model_path":"$gguf","mmproj_path":"$mmproj","n_ctx":2048}""") { true }
    }

    @After
    fun close() {
        if (::model.isInitialized) model.close()
    }

    private class Reply(val status: Int, val body: String)

    private fun send(endpoint: String, body: String, attachments: List<EngineAttachment>): Reply {
        val out = ByteArrayOutputStream()
        var status = 0
        val done = CountDownLatch(1)
        val sink =
            object : RequestSink {
                override fun onChunk(httpStatus: Int, data: ByteArray) {
                    synchronized(out) {
                        status = httpStatus
                        out.write(data)
                    }
                }

                override fun onDone() = done.countDown()

                override fun onCancelled() = done.countDown()
            }
        val request = model.request(endpoint, null, body.toByteArray(), attachments, sink)
        assertTrue(done.await(120, TimeUnit.SECONDS))
        request.free()
        return synchronized(out) { Reply(status, out.toString(Charsets.UTF_8)) }
    }

    /** The descriptor number behind a JVM stream (`--add-opens java.base/java.io`, set for the host tests). */
    private fun fdOf(fd: FileDescriptor): Int =
        FileDescriptor::class.java
            .getDeclaredField("fd")
            .apply { isAccessible = true }
            .getInt(fd)

    // form fields as strings, as upstream reads form data; reasoning kept in the text, where the test model's goes
    private val form = """{"temperature":"0","max_tokens":"16","reasoning_format":"none"}"""

    @Test
    fun transcriptionIsTheSameByPathDescriptorAndBytes() {
        val byPath = send("transcriptions", form, listOf(EngineAttachment.Path("file", "audio/wav", clip.path)))
        val byFd =
            FileInputStream(clip).use {
                send("transcriptions", form, listOf(EngineAttachment.Fd("file", "audio/wav", fdOf(it.fd))))
            }
        val byBytes =
            send("transcriptions", form, listOf(EngineAttachment.Bytes("file", "audio/wav", clip.readBytes())))
        assertEquals(byPath.body, 200, byPath.status)
        val json = Json.parseToJsonElement(byPath.body).jsonObject
        assertTrue(
            json
                .getValue("text")
                .jsonPrimitive.content
                .isNotEmpty(),
        )
        assertTrue(
            byPath.body,
            json
                .getValue("usage")
                .jsonObject
                .getValue("input_tokens")
                .jsonPrimitive.int > 30,
        )
        assertEquals(withoutCachedCount(byPath.body), withoutCachedCount(byFd.body))
        assertEquals(withoutCachedCount(byPath.body), withoutCachedCount(byBytes.body))
    }

    // a repeat of the same request reuses the prompt cache (F153), so the cached count is the one field that may differ
    private fun withoutCachedCount(body: String): JsonObject {
        val json = Json.parseToJsonElement(body).jsonObject
        val usage = JsonObject(json.getValue("usage").jsonObject - "input_tokens_details")
        return JsonObject(json + ("usage" to usage))
    }

    @Test
    fun chatPlaceholderBecomesTheFilesDataUrl() {
        fun chat(data: String) =
            """{"messages":[{"role":"user","content":[{"type":"text","text":"What is said here?"},
            {"type":"input_audio","input_audio":{"data":"$data","format":"wav"}}]}],
            "max_tokens":8,"temperature":0,"seed":1,"cache_prompt":false}"""
        val viaPlaceholder =
            send("chat", chat("lb-attachment:0"), listOf(EngineAttachment.Bytes("0", "audio/wav", clip.readBytes())))
        val url = "data:audio/wav;base64," + Base64.getEncoder().encodeToString(clip.readBytes())
        val direct = send("chat", chat(url), emptyList())
        assertEquals(viaPlaceholder.body, 200, viaPlaceholder.status)

        fun withoutIds(body: String) =
            JsonObject(Json.parseToJsonElement(body).jsonObject - setOf("id", "created", "timings"))
        assertEquals(withoutIds(direct.body), withoutIds(viaPlaceholder.body))
    }

    @Test
    fun audioThatIsNotWavMp3OrFlacIsABadRequest() {
        val reply =
            send("transcriptions", form, listOf(EngineAttachment.Bytes("file", "audio/wav", "not audio".toByteArray())))
        assertEquals(reply.body, 400, reply.status)
        assertTrue(reply.body, reply.body.contains("invalid_request_error") && reply.body.contains("WAV, MP3 or FLAC"))
    }

    @Test
    fun readFdReadsTheWholeFileAndLeavesItOpen() {
        FileInputStream(clip).use { stream ->
            stream.skip(50)
            assertArrayEquals(clip.readBytes(), JniEngine.readFd(fdOf(stream.fd)))
            assertEquals(50L, stream.channel.position())
            assertNotEquals(-1, stream.read()) // still open
        }
        try {
            JniEngine.readFd(4000)
            fail("a descriptor that isn't open must throw")
        } catch (e: NativeError) {
            assertEquals(1, e.status)
            assertTrue(e.errorJson, e.errorJson.contains("descriptor 4000"))
        }
    }
}
