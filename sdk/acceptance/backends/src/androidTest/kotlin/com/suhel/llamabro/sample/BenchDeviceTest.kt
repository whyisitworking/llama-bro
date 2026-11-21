package com.suhel.llamabro.sample

import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.suhel.llamabro.ContextSize
import com.suhel.llamabro.DeviceKind
import com.suhel.llamabro.DevicePolicy
import com.suhel.llamabro.Endpoint
import com.suhel.llamabro.LlamaBro
import com.suhel.llamabro.Model
import com.suhel.llamabro.ModelSource
import com.suhel.llamabro.Speculative
import com.suhel.llamabro.chat
import com.suhel.llamabro.chat.ChatEvent
import com.suhel.llamabro.chat.ChatRequest
import com.suhel.llamabro.chat.Message
import com.suhel.llamabro.voice.VoiceEvent
import com.suhel.llamabro.voice.VoiceSession
import com.suhel.llamabro.voiceSession
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.single
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.roundToLong

/**
 * The numbers the README shows (a measurement, not a gate): one arm per run, so every arm starts from a fresh process
 * and a fresh load, and the host script (not this class) paces the arms, rounds and the phone's temperature. An arm
 * loads its model on one chip with context 8,192 and no prompt cache, sends one prompt with thinking off and greedy
 * sampling, and appends one JSON line to filesDir/bench.jsonl: the load time, the prompt and reply token counts and
 * speeds from llama-server's timings, the drafted tokens accepted, and the arm's own extras. An arm that throws
 * writes its line with `ok: false` and the exception's message, and the test passes: a failed arm is a result.
 * Runs only when asked, so the default device run is unchanged:
 *   ANDROID_SERIAL=<serial> ./gradlew -p sdk :acceptance:backends:connectedFullDebugAndroidTest \
 *     -Pandroid.testInstrumentationRunnerArguments.class=com.suhel.llamabro.sample.BenchDeviceTest \
 *     -Pandroid.testInstrumentationRunnerArguments.bench=qwen-npu \
 *     -Pandroid.testInstrumentationRunnerArguments.benchRound=1
 * `bench` is one of [ARMS]' names, `benchRound` (default 1) is copied into the line.
 *
 * The models are read from the app's files (filesDir): [QWEN], [QWEN_DRAFT] for `json-dflash`, [LFM] and its
 * projector [LFM_PROJECTOR] for `voice`. The voice arm also reads [QUESTION_CLIP], a spoken question as raw PCM:
 * 16-bit signed little-endian samples, mono, at 16 kHz (LFM3-Omni's own rate, the session's `inputSampleRate`), with
 * no header. It is sent in 100 ms pieces at the pace of a microphone, then committed; the session is set up as F166
 * needs (a voice, a language, an answering instruction). `firstAudioMs` is the time from the commit to the first
 * reply audio, `realtimeFactor` the seconds of reply audio over the seconds from the commit to the end of the reply.
 * The `stop` arm sends the same 2,048-token prompt as the chip arms (so the stop comes after the same prefill),
 * cancels the collector of the reply 500 ms after its first token, and reports two numbers: `stopMs`, the time from
 * issuing the cancel until `GET /slots` (`is_processing` of every slot) says the engine is idle, polled every few
 * milliseconds, and `cancelReturnMs`, the time `cancelAndJoin` took to return, which is only the app's side. A slot
 * that never goes idle within 30 s fails the arm.
 */
@RunWith(AndroidJUnit4::class)
class BenchDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val llama = LlamaBro.create(context)
    private val json = Json

    @Test fun measuresTheArm() =
        runBlocking {
            val args = InstrumentationRegistry.getArguments()
            val name = args.getString("bench")
            assumeTrue("a measurement: pass -Pandroid.testInstrumentationRunnerArguments.bench=<arm>", name != null)
            val arm = requireNotNull(ARMS.firstOrNull { it.name == name }) { "unknown arm $name" }
            val round = args.getString("benchRound")?.toIntOrNull() ?: 1
            val line = runCatching { measure(arm, round) }.getOrElse { failed(arm, round, it) }
            val text = json.encodeToString(line)
            Log.i("Bench", text)
            File(context.filesDir, "bench.jsonl").appendText(text + "\n")
        }

    private fun failed(arm: Arm, round: Int, error: Throwable) =
        BenchLine(
            arm = arm.name,
            round = round,
            ok = false,
            error = error.message ?: error.toString(),
            model = arm.model,
            device = arm.device.name,
        )

    private suspend fun measure(arm: Arm, round: Int): BenchLine {
        val files = listOfNotNull(arm.model, arm.draft, arm.projector).map { File(context.filesDir, it) }
        files.forEach { check(it.isFile) { "missing ${it.name} in the app's files" } }
        val draftPath = arm.draft?.let { File(context.filesDir, it).path }
        val projectorPath = arm.projector?.let { File(context.filesDir, it).path }
        val started = SystemClock.elapsedRealtime()
        val model =
            llama.loadModel(
                ModelSource.file(File(context.filesDir, arm.model).path) {
                    draft = draftPath
                    mmproj = projectorPath
                },
            ) {
                devicePolicy = DevicePolicy.Only(arm.device)
                contextSize = ContextSize.Fixed(CONTEXT)
                speculative = arm.speculative
                // every request sets cache_prompt false, so the cache would only take memory the draft needs (F88)
                ramCacheMib = 0
            }
        val loaded =
            BenchLine(
                arm = arm.name,
                round = round,
                ok = true,
                error = null,
                model = arm.model,
                device = model.loadReport.device.name,
                loadMs = SystemClock.elapsedRealtime() - started,
            )
        try {
            return when (arm.workload) {
                Workload.Stop -> loaded.copy(extra = stop(model, arm))
                Workload.Voice -> voice(model, loaded)
                else -> reply(model, arm, loaded)
            }
        } finally {
            model.closeAndJoin()
        }
    }

    private suspend fun reply(model: Model, arm: Arm, loaded: BenchLine): BenchLine {
        val events = model.chat(request(model, arm)).toList()
        val timings = events.filterIsInstance<ChatEvent.Done>().single().timings ?: error("the reply had no timings")
        return loaded.copy(
            promptTokens = timings.promptTokens,
            promptTps = timings.promptPerSecond.rounded(),
            predictedTokens = timings.predictedTokens,
            predictedTps = timings.predictedPerSecond.rounded(),
            draftAccepted = timings.draftAccepted,
        )
    }

    /** Cancels the reply [STOP_AFTER_MS] after its first token; returns `stopMs` and `cancelReturnMs`. */
    private suspend fun stop(model: Model, arm: Arm): Map<String, Double> =
        coroutineScope {
            val firstToken = CompletableDeferred<Unit>()
            val reply =
                async {
                    model.chat(request(model, arm)).collect {
                        if (it is ChatEvent.ContentDelta || it is ChatEvent.ReasoningDelta) firstToken.complete(Unit)
                    }
                }
            reply.invokeOnCompletion { firstToken.complete(Unit) }
            firstToken.await()
            delay(STOP_AFTER_MS)
            if (!reply.isActive) {
                reply.await() // a failure surfaces here
                error("the reply ended before it could be cancelled")
            }
            val cancelled = SystemClock.elapsedRealtime()
            reply.cancelAndJoin()
            val returned = SystemClock.elapsedRealtime()
            withTimeout(IDLE_TIMEOUT_MS) {
                while (isProcessing(model)) delay(IDLE_POLL_MS)
            }
            mapOf(
                "stopMs" to (SystemClock.elapsedRealtime() - cancelled).toDouble(),
                "cancelReturnMs" to (returned - cancelled).toDouble(),
            )
        }

    /** Whether any slot of llama-server's `GET /slots` is still working on a request. */
    private suspend fun isProcessing(model: Model): Boolean =
        json.parseToJsonElement(model.rawRequest(Endpoint.Slots, "").single()).jsonArray.any {
            it.jsonObject
                .getValue("is_processing")
                .jsonPrimitive.boolean
        }

    private suspend fun request(model: Model, arm: Arm): ChatRequest {
        val prompt =
            when (arm.workload) {
                Workload.Summarise, Workload.Stop -> {
                    "${firstTokens(model, asset("document.txt"))}\n\nSummarise the document above."
                }

                Workload.Json -> {
                    val extract = json.parseToJsonElement(asset("extract.json")).jsonObject
                    val text = { key: String -> extract.getValue(key).jsonPrimitive.content }
                    "${text("document")}\n\n${text("instruction")}\nThe schema: ${extract.getValue("schema")}"
                }

                Workload.Edit -> {
                    "Rename the function `parseLine` to `parseReceiptLine` everywhere in the file below and return " +
                        "the whole file in one Kotlin code block, changing nothing else.\n\n" +
                        "```kotlin\n${asset("edit.kt")}```"
                }

                Workload.Voice -> {
                    error("the voice arm sends no chat")
                }
            }
        return ChatRequest {
            messages(Message.user(prompt))
            sampling {
                temperature = 0f
                maxTokens = arm.replyTokens
            }
            reasoning { enableThinking = false }
            extra = """{"cache_prompt":false}"""
        }
    }

    /** The first [DOCUMENT_TOKENS] tokens of [text] as the model counts them, so every model reads as much. */
    private suspend fun firstTokens(model: Model, text: String): String {
        val tokens = call(model, Endpoint.Tokenize, buildJsonObject { put("content", text) })
        val cut = JsonArray(tokens.getValue("tokens").jsonArray.take(DOCUMENT_TOKENS))
        return call(model, Endpoint.Detokenize, buildJsonObject { put("tokens", cut) })
            .getValue("content")
            .jsonPrimitive.content
    }

    private suspend fun call(model: Model, endpoint: Endpoint, body: JsonObject): JsonObject =
        json.parseToJsonElement(model.rawRequest(endpoint, body.toString()).single()).jsonObject

    private fun asset(name: String): String =
        // the test APK's assets, not the app's: the files live in src/androidTest/assets
        instrumentation.context.assets
            .open("bench/$name")
            .bufferedReader()
            .use { it.readText() }

    /** The question sent at the pace of a microphone, committed, and the reply timed from the commit. */
    private suspend fun voice(model: Model, loaded: BenchLine): BenchLine {
        val clip = File(context.filesDir, QUESTION_CLIP)
        check(clip.isFile) { "missing $QUESTION_CLIP in the app's files" }
        val pcm = ShortArray(clip.length().toInt() / 2)
        ByteBuffer
            .wrap(clip.readBytes())
            .order(ByteOrder.LITTLE_ENDIAN)
            .asShortBuffer()
            .get(pcm)
        val session =
            model.voiceSession {
                voice = "female"
                language = "en"
                instructions = "You are a helpful voice assistant. Answer the user's spoken question in your own words."
            }
        try {
            return coroutineScope {
                val committedAt = AtomicLong()
                val reply = async(Dispatchers.Default) { listen(session, committedAt) }
                val chunk = session.inputSampleRate / PIECES_PER_SECOND
                val started = SystemClock.elapsedRealtime()
                for ((index, offset) in (pcm.indices step chunk).withIndex()) {
                    delay(started + (index + 1) * PIECE_MS - SystemClock.elapsedRealtime())
                    session.append(pcm, offset, minOf(chunk, pcm.size - offset))
                }
                committedAt.set(SystemClock.elapsedRealtime())
                check(session.commit()) { "the session ended before the commit" }
                val heard = withTimeout(VOICE_TIMEOUT_MS) { reply.await() }
                val seconds = heard.samples.toDouble() / checkNotNull(session.outputSampleRate) { "no speech" }
                loaded.copy(
                    predictedTokens = heard.outputTokens,
                    extra =
                        mapOf(
                            "firstAudioMs" to (heard.firstAudioAt - committedAt.get()).toDouble(),
                            "realtimeFactor" to seconds / ((heard.doneAt - committedAt.get()) / MS_PER_SECOND),
                            "replyAudioSeconds" to seconds,
                        ),
                )
            }
        } finally {
            session.close()
        }
    }

    private class Heard(val firstAudioAt: Long, val doneAt: Long, val samples: Long, val outputTokens: Int?)

    /** Reads the session's events up to the end of the first reply, noting when its audio and its end arrive. */
    private suspend fun listen(session: VoiceSession, committedAt: AtomicLong): Heard {
        var firstAudioAt = -1L
        var samples = 0L
        var done: VoiceEvent.ReplyDone? = null
        var doneAt = 0L
        session.events
            .transformWhile {
                emit(it)
                it !is VoiceEvent.ReplyDone
            }.collect { event ->
                when (event) {
                    is VoiceEvent.ReplyAudio -> {
                        if (firstAudioAt < 0) firstAudioAt = SystemClock.elapsedRealtime()
                        samples += event.pcm.size
                    }

                    is VoiceEvent.ReplyDone -> {
                        done = event
                        doneAt = SystemClock.elapsedRealtime()
                    }

                    is VoiceEvent.Error -> {
                        error("the session refused: ${event.type}: ${event.message}")
                    }

                    else -> {
                        Unit
                    }
                }
            }
        val end = checkNotNull(done) { "the session ended without a reply" }
        check(!end.cancelled && firstAudioAt >= 0) { "the reply had no speech" }
        check(committedAt.get() > 0) { "a reply before the commit" }
        return Heard(firstAudioAt, doneAt, samples, end.outputTokens)
    }

    private fun Double.rounded() = (this * TENTHS).roundToLong() / TENTHS

    /** One arm of the table: what it loads, on which chip, and what it asks. */
    private class Arm(
        val name: String,
        val model: String,
        val device: DeviceKind,
        val workload: Workload,
        val replyTokens: Int,
        val speculative: Speculative = Speculative.Off,
        val draft: String? = null,
        val projector: String? = null,
    )

    private enum class Workload { Summarise, Json, Edit, Stop, Voice }

    /** One line of bench.jsonl; a field an arm doesn't measure is null. */
    @Serializable
    private data class BenchLine(
        val arm: String,
        val round: Int,
        val ok: Boolean,
        val error: String?,
        val model: String,
        val device: String,
        val loadMs: Long? = null,
        val promptTokens: Int? = null,
        val promptTps: Double? = null,
        val predictedTokens: Int? = null,
        val predictedTps: Double? = null,
        val draftAccepted: Int? = null,
        val extra: Map<String, Double> = emptyMap(),
    )

    private companion object {
        const val QWEN = "Qwen3.5-4B-Q4_K_M.gguf"
        const val QWEN_DRAFT = "Qwen3.5-4B-DFlash-Q8_0.gguf"
        const val LFM = "LFM3-Omni-2B-Q4_K_M.gguf"
        const val LFM_PROJECTOR = "mmproj-LFM3-Omni-2B-Q8_0.gguf"
        const val QUESTION_CLIP = "bench-question.pcm"

        const val CONTEXT = 8192
        const val DOCUMENT_TOKENS = 2048
        const val SUMMARY_TOKENS = 128
        const val STRUCTURED_TOKENS = 256
        const val STOP_AFTER_MS = 500L
        const val IDLE_TIMEOUT_MS = 30_000L
        const val IDLE_POLL_MS = 5L
        const val VOICE_TIMEOUT_MS = 180_000L
        const val PIECES_PER_SECOND = 10
        const val PIECE_MS = 100L
        const val MS_PER_SECOND = 1000.0
        const val TENTHS = 10.0

        fun chips(prefix: String, model: String) =
            mapOf("npu" to DeviceKind.Npu, "gpu" to DeviceKind.Gpu, "cpu" to DeviceKind.Cpu).map { (suffix, kind) ->
                Arm("$prefix-$suffix", model, kind, Workload.Summarise, SUMMARY_TOKENS)
            }

        val ARMS =
            chips("qwen", QWEN) +
                chips("lfm", LFM) +
                listOf(
                    // the SDK passes a draft to llama-server only for Speculative.DFlash, so json-off loads none
                    Arm("json-off", QWEN, DeviceKind.Npu, Workload.Json, STRUCTURED_TOKENS),
                    Arm(
                        "json-dflash",
                        QWEN,
                        DeviceKind.Npu,
                        Workload.Json,
                        STRUCTURED_TOKENS,
                        Speculative.DFlash(),
                        draft = QWEN_DRAFT,
                    ),
                    Arm("edit-off", QWEN, DeviceKind.Npu, Workload.Edit, STRUCTURED_TOKENS),
                    Arm(
                        "edit-ngram-mtp",
                        QWEN,
                        DeviceKind.Npu,
                        Workload.Edit,
                        STRUCTURED_TOKENS,
                        Speculative.Ngram(then = Speculative.Mtp(4)),
                    ),
                    Arm("stop", QWEN, DeviceKind.Npu, Workload.Stop, STRUCTURED_TOKENS),
                    Arm("voice", LFM, DeviceKind.Npu, Workload.Voice, 0, projector = LFM_PROJECTOR),
                )
    }
}
