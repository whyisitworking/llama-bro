package com.suhel.llamabro.hexagon

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.suhel.llamabro.DeviceKind
import com.suhel.llamabro.DevicePolicy
import com.suhel.llamabro.Endpoint
import com.suhel.llamabro.LlamaBro
import com.suhel.llamabro.Model
import com.suhel.llamabro.ModelSource
import com.suhel.llamabro.Speculative
import kotlinx.coroutines.flow.single
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * D35: `Speculative.Ngram` on the NPU with Qwen3.5 4B (copied into this app's external files directory by
 * sdk/scripts/check.sh). A reply that copies its prompt must draft from n-grams and stay byte-identical to plain
 * decoding (F72); n-grams then MTP must load and answer. Speeds are logged, not asserted.
 */
@RunWith(AndroidJUnit4::class)
class NgramDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val llama = LlamaBro.getInstance(context)
    private val model = File(context.getExternalFilesDir(null), "Qwen3.5-4B-Q4_K_M.gguf").path

    private fun load(s: Speculative): Model = runBlocking {
        settleMemory() // the previous test's model may still be returning its memory
        llama.loadModel(ModelSource.file(model)) { devicePolicy = DevicePolicy.Only(DeviceKind.Npu); speculative = s }
    }

    private data class Reply(val text: String, val accepted: Int, val decodeTps: Double)

    private fun Model.copy(): Reply = runBlocking {
        val text = (1..12).joinToString(" ") { "Line $it: the build copies libllama.so into the jniLibs folder for arm64-v8a." }
        val body = """{"messages":[{"role":"user","content":${Json.encodeToString(kotlinx.serialization.json.JsonPrimitive("Repeat the following text exactly, and nothing else:\n\n$text"))}}],""" +
            """"max_tokens":160,"temperature":0,"cache_prompt":false,"chat_template_kwargs":{"enable_thinking":false}}"""
        val r = Json.parseToJsonElement(rawRequest(Endpoint.ChatCompletions, body).single()) as JsonObject
        val t = r.getValue("timings").jsonObject
        Reply(
            r.getValue("choices").jsonArray[0].jsonObject.getValue("message").jsonObject.getValue("content").jsonPrimitive.content,
            t["draft_n_accepted"]?.jsonPrimitive?.int ?: 0,
            t.getValue("predicted_per_second").jsonPrimitive.double,
        )
    }

    private fun run(s: Speculative): Reply {
        val m = load(s)
        try {
            assertEquals(m.loadReport.toString(), s, m.loadReport.speculative)
            return m.copy().also { Log.i("Ngram", "$s -> accepted ${it.accepted}, ${"%.1f".format(it.decodeTps)} t/s") }
        } finally {
            runBlocking { m.closeAndJoin() }  // free the NPU before the next load
        }
    }

    @Test
    fun ngramDraftsAndKeepsTheReply() {
        val off = run(Speculative.Off)
        val ngram = run(Speculative.Ngram())
        assertEquals(0, off.accepted)
        assertTrue("accepted ${ngram.accepted}", ngram.accepted > 0)
        assertEquals(off.text, ngram.text)
    }

    @Test
    fun ngramThenMtpLoadsAndAnswers() {
        val both = run(Speculative.Ngram(then = Speculative.Mtp(4)))
        assertTrue("accepted ${both.accepted}", both.accepted > 0)
        // 160 tokens reach about line 7 of 12
        assertTrue(both.text, both.text.startsWith("Line 1: the build copies libllama.so") && both.text.contains("Line 6: the build copies"))
    }
}
