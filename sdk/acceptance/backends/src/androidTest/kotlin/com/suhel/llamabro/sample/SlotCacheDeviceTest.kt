package com.suhel.llamabro.sample

import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.suhel.llamabro.ContextSize
import com.suhel.llamabro.Endpoint
import com.suhel.llamabro.LlamaBro
import com.suhel.llamabro.Model
import com.suhel.llamabro.ModelSource
import com.suhel.llamabro.chat
import com.suhel.llamabro.chat.ChatEvent
import com.suhel.llamabro.chat.ChatRequest
import com.suhel.llamabro.chat.Message
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * D59's open question (a measurement, not a gate): after the app frees a model, is reloading it and restoring the
 * chat's slot from disk (llama-server's `/slots/0?action=save|restore`) faster than reading the chat again?
 * Runs only when asked, with the model's file name, for example:
 *   ANDROID_SERIAL=<serial> ./gradlew -p sdk :acceptance:backends:connectedFullDebugAndroidTest \
 *     -Pandroid.testInstrumentationRunnerArguments.class=com.suhel.llamabro.sample.SlotCacheDeviceTest \
 *     -Pandroid.testInstrumentationRunnerArguments.slotCache=Qwen3.5-4B-Q4_K_M.gguf
 * Numbers go to logcat under "SlotCache" and to filesDir/slotcache.log.
 */
@RunWith(AndroidJUnit4::class)
class SlotCacheDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val llama = LlamaBro.create(context)

    private fun log(line: String) {
        Log.i("SlotCache", line)
        File(context.filesDir, "slotcache.log").appendText(line + "\n")
    }

    private suspend fun load(path: String): Pair<Model, Long> {
        val start = SystemClock.elapsedRealtime()
        val model = llama.loadModel(ModelSource.file(path)) { contextSize = ContextSize.Fixed(CONTEXT) }
        return model to SystemClock.elapsedRealtime() - start
    }

    /** The chat so far, then a short new question: what the next message after a reload sends. */
    private fun request(): ChatRequest {
        val history = (1..PARAGRAPHS).joinToString("\n\n") { "Note $it. $PARAGRAPH" }
        return ChatRequest {
            messages(
                Message.user("Here are my notes.\n\n$history\n\nSay ok."),
                Message.Assistant("ok"),
                Message.user("How many notes were there? Answer with a number."),
            )
            sampling {
                temperature = 0f
                maxTokens = 8
            }
            reasoning { enableThinking = false }
        }
    }

    private suspend fun ask(model: Model, label: String): Long {
        val start = SystemClock.elapsedRealtime()
        val events = model.chat(request()).toList()
        val millis = SystemClock.elapsedRealtime() - start
        val t = events.filterIsInstance<ChatEvent.Done>().single().timings
        val answer = events.filterIsInstance<ChatEvent.ContentDelta>().joinToString("") { it.delta }.trim()
        val prompt = "prompt ${t?.promptTokens} tokens (${t?.cachedTokens} cached) at ${t?.promptPerSecond} t/s"
        log("$label: ${millis}ms wall, $prompt, answer \"$answer\"")
        return millis
    }

    private suspend fun slot(model: Model, action: String): Long {
        val start = SystemClock.elapsedRealtime()
        val body =
            model
                .rawRequest(
                    Endpoint.SlotsAction,
                    """{"filename":"$FILE"}""",
                    mapOf(
                        "id_slot" to "0",
                        "action" to action,
                    ),
                ).toList()
        val millis = SystemClock.elapsedRealtime() - start
        log("$action: ${millis}ms wall, ${body.joinToString().take(RESPONSE_CHARS)}")
        return millis
    }

    @Test fun reloadAndRestoreAgainstReadingTheChatAgain() =
        runBlocking {
            val name = InstrumentationRegistry.getArguments().getString("slotCache")
            assumeTrue(
                "a measurement: pass -Pandroid.testInstrumentationRunnerArguments.slotCache=<file.gguf>",
                name != null,
            )
            val path = File(context.filesDir, name!!).path
            assumeTrue("missing model $path", File(path).exists())
            log("---- $name, context $CONTEXT")

            // the chat as it stands before the app is left: answered once, its slot saved to disk
            val (first, firstLoad) = load(path)
            log("load: ${firstLoad}ms (${first.loadReport.device})")
            ask(first, "first turn")
            slot(first, "save")
            val file = File(context.noBackupFilesDir, "llamabro/slots/$FILE")
            log("slot file: ${file.length() / BYTES_PER_MB} MB")
            first.closeAndJoin()

            // freed and loaded again: the next message reads the whole chat
            val (cold, coldLoad) = load(path)
            val coldAsk = ask(cold, "reload, read again")
            cold.closeAndJoin()

            // freed and loaded again: the slot comes back from disk first
            val (warm, warmLoad) = load(path)
            val restore = slot(warm, "restore")
            val warmAsk = ask(warm, "reload, restore")
            warm.closeAndJoin()
            file.delete()

            log("read again: ${coldLoad + coldAsk}ms in all (load $coldLoad, ask $coldAsk)")
            log("restore: ${warmLoad + restore + warmAsk}ms in all (load $warmLoad, restore $restore, ask $warmAsk)")
        }

    private companion object {
        const val CONTEXT = 8192
        const val PARAGRAPHS = 40
        const val FILE = "slotcache.bin"
        const val RESPONSE_CHARS = 300
        const val BYTES_PER_MB = 1_048_576
        const val PARAGRAPH =
            "Today I walked along the river to the old mill, where the path turns to gravel and the water runs fast " +
                "over the stones. I noted two herons and a kingfisher that came and went too quickly to " +
                "photograph. The bakery by the bridge had sold out of rye, so I bought a seeded loaf instead."
    }
}
