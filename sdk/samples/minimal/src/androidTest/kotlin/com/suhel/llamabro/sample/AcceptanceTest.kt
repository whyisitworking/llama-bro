package com.suhel.llamabro.sample

import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.suhel.llamabro.ContextSize
import com.suhel.llamabro.DeviceKind
import com.suhel.llamabro.LlamaBro
import com.suhel.llamabro.LlamaBroException
import com.suhel.llamabro.Model
import com.suhel.llamabro.ModelSource
import com.suhel.llamabro.chat.ChatEvent
import com.suhel.llamabro.chat.FinishReason
import com.suhel.llamabro.conversation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.AfterClass
import org.junit.BeforeClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.io.File

/**
 * P1 and P2 done-when (design §13, P1 T12, P2 T10): chat, tools, stop and the context error on the phone, through the
 * public API only, for each model named in the instrumentation argument `models` (file names in the app's filesDir).
 * Each flavor ships a different set of backends: `DevicePolicy.Auto` must pick its device ([BuildConfig.AUTO_DEVICE]);
 * `full` runs every test, `cpu`, `gpu` and `noskel` (the forced NPU failure) device choice, one chat and stop:
 *   ./gradlew -p sdk :samples:minimal:connectedFullDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.models=a.gguf,b.gguf
 * Results go to logcat under the tag "Acceptance" and to the app's filesDir/acceptance.log:
 *   adb shell run-as com.suhel.llamabro.sample cat files/acceptance.log
 */
@RunWith(Parameterized::class)
class AcceptanceTest(private val file: String) {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val llama = LlamaBro.getInstance(context)
    private val path = File(context.filesDir, file).path

    // deterministic and short; thinking off where the template supports the switch
    private fun Model.chat() = conversation(system = "You are a helpful assistant.") {
        defaults { sampling { temperature = 0f; seed = 1 }; reasoning { enableThinking = false } }
    }

    private fun model(): Model = synchronized(Companion) {
        if (loadedFile != file) {
            loaded?.close()
            assertTrue("missing model $path", File(path).exists())
            val t0 = System.nanoTime()
            loaded = runBlocking { llama.loadModel(ModelSource.file(path)) }
            loadedFile = file
            val r = loaded!!.loadReport
            log("load", "flavor=${BuildConfig.FLAVOR} device=${r.device} deviceName=${r.deviceName} attempts=${r.attempts} ms=${(System.nanoTime() - t0) / 1_000_000} desc=\"${loaded!!.info.description}\" n_ctx=${loaded!!.info.contextSize} caps=${loaded!!.info.templateCaps}")
        }
        loaded!!
    }

    // logcat's ring buffer is small on some phones: the lines also go to filesDir/acceptance.log
    private fun log(test: String, msg: String) {
        val line = "model=$file test=$test $msg"
        Log.i("Acceptance", line)
        File(context.filesDir, "acceptance.log").appendText(line + "\n")
    }

    private fun timings(done: ChatEvent.Done) = done.timings?.let {
        "prompt_tokens=${it.promptTokens} prefill_tps=%.1f decode_tokens=${it.predictedTokens} decode_tps=%.1f".format(it.promptPerSecond, it.predictedPerSecond)
    } ?: "no timings"

    @Test
    fun autoPicksTheFlavorsDevice() {
        val r = model().loadReport
        log("autoPicksTheFlavorsDevice", "device=${r.device} attempts=${r.attempts}")
        assertEquals(r.toString(), DeviceKind.valueOf(BuildConfig.AUTO_DEVICE), r.device)
        if (BuildConfig.FLAVOR == "noskel") {
            // the NPU's backend couldn't install its skeleton: one line with the reason, then the GPU (P2 T10)
            val a = r.attempts.single()
            assertEquals(r.toString(), DeviceKind.Npu, a.kind)
            assertTrue(a.reason, a.reason.startsWith("hexagon didn't load") && a.reason.contains("libggml-htp-v79.so"))
        } else {
            // only what the flavor ships is tried, and its best device loads first
            assertEquals(r.toString(), emptyList<Any>(), r.attempts)
        }
    }

    @Test
    fun streamsReply() = runBlocking {
        val events = model().chat().send("Say hello in five words.").toList()
        val content = events.filterIsInstance<ChatEvent.ContentDelta>().joinToString("") { it.delta }
        val done = events.last() as ChatEvent.Done
        log("streamsReply", "${timings(done)} reason=${done.reason} content=\"${content.replace("\n", "\\n")}\"")
        assertTrue(events.any { it is ChatEvent.ContentDelta })
        assertEquals(FinishReason.Stop, done.reason)
    }

    @Test
    fun runsToolLoop() = runBlocking {
        assumeTrue("full flavor only", BuildConfig.FULL_SUITE)
        // a template without tool support (e.g. Gemma 3) never sees the tools: llama-server leaves them out
        // (decided with the user 2026-11-04: skip, recorded as a template quirk in the findings)
        val caps = model().info.templateCaps
        if (caps["supports_tool_calls"] == false) log("runsToolLoop", "skipped: template supports_tool_calls=false")
        assumeTrue("template has no tool support", caps["supports_tool_calls"] != false)
        val chat = model().conversation(system = "You are a helpful assistant. Use the tools when they help.") {
            defaults { sampling { temperature = 0f; seed = 1 }; reasoning { enableThinking = false } }
            tool("get_weather", "Current weather for a city", """{"type":"object","properties":{"city":{"type":"string","description":"City name"}},"required":["city"]}""") { args ->
                "Sunny, 22 °C in ${args.string("city")}"
            }
        }
        val events = chat.send("What's the weather in Paris right now?").toList()
        val call = events.filterIsInstance<ChatEvent.ToolCallReceived>().firstOrNull()
        val done = events.last() as ChatEvent.Done
        val content = events.filterIsInstance<ChatEvent.ContentDelta>().joinToString("") { it.delta }
        log("runsToolLoop", "call=${call?.call?.name}(${call?.call?.argumentsJson}) ${timings(done)} reason=${done.reason} content=\"${content.replace("\n", "\\n")}\"")
        assertEquals("get_weather", call?.call?.name)
        val callAt = events.indexOf<ChatEvent>(call!!)
        val resultAt = events.indexOfFirst { it is ChatEvent.ToolResult }
        assertTrue("ToolResult after ToolCall", resultAt > callAt)
        assertEquals(FinishReason.Stop, done.reason)
    }

    @Test
    fun stopIsFast() = runBlocking {
        val chat = model().chat()
        val prompt = "Repeat nothing. " + "The quick brown fox jumps over the lazy dog. ".repeat(300)
        val job = launch { chat.send(prompt).collect() }
        delay(200)
        val t0 = System.nanoTime()
        job.cancel()
        job.join()
        val ms = (System.nanoTime() - t0) / 1_000_000
        // the slot frees at the end of the current n_batch pass (F9); the next reply must still work
        val after = System.nanoTime()
        val next = chat.send("Say OK.").toList().last() as ChatEvent.Done
        log("stopIsFast", "cancel_to_flow_end_ms=$ms next_reply_ms=${(System.nanoTime() - after) / 1_000_000} next_reason=${next.reason}")
        assertTrue("stop took $ms ms", ms <= 1000)
    }

    @Test
    fun exceedMapsTyped() = runBlocking {
        assumeTrue("full flavor only", BuildConfig.FULL_SUITE)
        val small = llama.loadModel(ModelSource.file(path)) { contextSize = ContextSize.Fixed(512) }
        try {
            val e = runCatching {
                small.chat().send("The quick brown fox jumps over the lazy dog. ".repeat(100)).toList()
            }.exceptionOrNull()
            log("exceedMapsTyped", "error=${e?.javaClass?.simpleName} ${e?.message}")
            assertTrue("$e", e is LlamaBroException.ContextExceeded)
            e as LlamaBroException.ContextExceeded
            assertEquals(512, e.contextSize)
        } finally {
            small.close()
        }
    }

    companion object {
        private var loaded: Model? = null
        private var loadedFile: String? = null

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun models(): List<String> =
            InstrumentationRegistry.getArguments().getString("models").orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }
                .ifEmpty { error("pass -Pandroid.testInstrumentationRunnerArguments.models=<file.gguf,…>") }

        /**
         * noskel starts as a fresh install would: every flavor shares the app's files, and a valid skeleton that full
         * installed earlier still matches the checksums in this APK, so the broken one in its assets would never be
         * read. Before the runtime starts, which the first load does.
         */
        @BeforeClass
        @JvmStatic
        fun freshSkeletonInstall() {
            if (BuildConfig.FLAVOR != "noskel") return
            File(InstrumentationRegistry.getInstrumentation().targetContext.noBackupFilesDir, "llamabro/htp").deleteRecursively()
        }

        @AfterClass
        @JvmStatic
        fun closeModel() {
            runBlocking { loaded?.closeAndJoin() }  // the next test class starts with the memory freed
            loaded = null
            loadedFile = null
        }
    }
}
