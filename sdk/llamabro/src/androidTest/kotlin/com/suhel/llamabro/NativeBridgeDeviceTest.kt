package com.suhel.llamabro

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.suhel.llamabro.internal.NativeBridge
import com.suhel.llamabro.spi.RequestSink
import org.json.JSONArray
import org.json.JSONObject
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The native path on a phone. The model must be in the test app's filesDir:
 *   adb shell run-as com.suhel.llamabro.test cp /data/local/tmp/lb-spike/models/Qwen3.5-4B-Q4_K_M.gguf files/
 */
@RunWith(AndroidJUnit4::class)
class NativeBridgeDeviceTest {
    private class Sink : RequestSink {
        val chunks = mutableListOf<Pair<Int, String>>()
        val terminal = CountDownLatch(1)

        @Volatile var done = false

        @Volatile var cancelled = false

        @Volatile var terminalAtNs = 0L

        override fun onChunk(httpStatus: Int, data: ByteArray) {
            synchronized(chunks) { chunks += httpStatus to String(data, Charsets.UTF_8) }
        }

        override fun onDone() {
            done = true
            terminalAtNs = System.nanoTime()
            terminal.countDown()
        }

        override fun onCancelled() {
            cancelled = true
            terminalAtNs = System.nanoTime()
            terminal.countDown()
        }
    }

    private fun chatBody(user: String, maxTokens: Int, stream: Boolean): ByteArray =
        JSONObject()
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", user)))
            .put("max_tokens", maxTokens)
            .put("temperature", 0)
            .put("seed", 1)
            .put("stream", stream)
            .put("chat_template_kwargs", JSONObject().put("enable_thinking", false))
            .toString()
            .toByteArray()

    private fun request(body: ByteArray, sink: Sink): Long =
        NativeBridge.modelRequest(
            model,
            "chat",
            null,
            body,
            emptyArray(),
            emptyArray(),
            emptyArray(),
            IntArray(0),
            emptyArray(),
            sink,
        )

    @Test
    fun loadChatAndDone() {
        val sink = Sink()
        val req = request(chatBody("Say hi", 8, false), sink)
        assertTrue(sink.terminal.await(120, TimeUnit.SECONDS))
        assertTrue(sink.done)
        assertEquals(1, sink.chunks.size)
        assertEquals(200, sink.chunks[0].first)
        val content =
            JSONObject(sink.chunks[0].second)
                .getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")
        assertTrue(content.isNotEmpty())
        NativeBridge.requestFree(req)
    }

    @Test
    fun cancelDuringPrefillFast() {
        val prompt = "Repeat nothing. " + "The quick brown fox jumps over the lazy dog. ".repeat(300)
        val sink = Sink()
        val req = request(chatBody(prompt, 16, true), sink)
        Thread.sleep(200)
        val cancelAt = System.nanoTime()
        NativeBridge.requestCancel(req)
        assertTrue(sink.terminal.await(30, TimeUnit.SECONDS))
        val ms = (sink.terminalAtNs - cancelAt) / 1_000_000
        Log.i("NativeBridgeDeviceTest", "cancel -> terminal: $ms ms")
        assertTrue(sink.cancelled)
        assertTrue("cancel took $ms ms", ms <= 1000)
        NativeBridge.requestFree(req)
    }

    /** Paths reach native code as real UTF-8 (JNI's modified UTF-8 would mangle the emoji, a 4-byte character). */
    @Test
    fun loadsFromNonBmpPath() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val link = File(context.filesDir, "模型😀.gguf")
        link.delete()
        Files
            .createSymbolicLink(link.toPath(), File(context.filesDir, "Qwen3.5-4B-Q4_K_M.gguf").toPath())
        try {
            val m = NativeBridge.modelLoad(JSONObject().put("model_path", link.path).put("n_ctx", 512).toString(), null)
            assertTrue(m != 0L)
            NativeBridge.modelFree(m)
        } finally {
            link.delete()
        }
    }

    companion object {
        private var model = 0L

        @BeforeClass
        @JvmStatic
        fun load() {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            // no backend_dir: the CPU backends load by name, straight from the APK (findings F25)
            assertNull(NativeBridge.runtimeInit("{}"))
            val gguf = File(context.filesDir, "Qwen3.5-4B-Q4_K_M.gguf")
            assertTrue("push the model first: ${gguf.path}", gguf.exists())
            model = NativeBridge.modelLoad(JSONObject().put("model_path", gguf.path).toString(), null)
            assertTrue(model != 0L)
        }

        @AfterClass
        @JvmStatic
        fun free() {
            if (model != 0L) NativeBridge.modelFree(model)
        }
    }
}
