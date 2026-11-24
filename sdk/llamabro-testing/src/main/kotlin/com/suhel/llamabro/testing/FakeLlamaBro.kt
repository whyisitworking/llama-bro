package com.suhel.llamabro.testing

import com.suhel.llamabro.InternalLlamaBroApi
import com.suhel.llamabro.LlamaBro
import com.suhel.llamabro.LlamaBroException
import com.suhel.llamabro.spi.EngineAttachment
import com.suhel.llamabro.spi.NativeError
import com.suhel.llamabro.testing.FakeWire.q
import com.suhel.llamabro.testing.FakeWire.serverError
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger

/**
 * A [LlamaBro] for an app's JVM tests: no phone, no model, no native code (D27). Pass [llamaBro] to the code under
 * test. Each chat request takes the next scripted [reply] (or error), answered as llama-server streams it, so the
 * real `Model`, `Conversation`, agent loop and compaction run on it.
 *
 * ```
 * val fake = FakeLlamaBro {
 *     reply { reasoning("…"); content("Hello!") }
 *     reply { toolCall("get_weather", """{"city":"Paris"}""") }
 *     contextExceeded(promptTokens = 9000, contextSize = 8192)
 * }
 * val vm = ChatViewModel(fake.llamaBro)
 * …
 * assertTrue(fake.chatRequests[1].contains("\"role\":\"tool\""))
 * ```
 * Streamed chat completions are scripted (what `Conversation` and `Model.chat` send), and so are `Model.embed`,
 * `Model.rerank` on a model loaded with their `ModelTask`, and `Model.transcribe`: each takes the next [embeddings],
 * [ranking], [inputTooLong] or [transcript], in their own queue. Pictures and clips need [supportsVision] and
 * [supportsAudio], as on a phone. `GET /slots` and the reasoning control answer as an idle server would; any
 * other route fails with `NotSupported`. A voice session (`Model.voiceSession`, on a model that [supportsAudio])
 * answers each asked-for reply with the next [voiceReply], as upstream's realtime conversation sends it. Pre-encodes
 * (`preEncodeAfterReply`) are answered without taking a reply. A transcription session (`Model.transcriptionSession`)
 * opens on a model that [streamsAudio], live or per commit as [transcribesLive] says, and gives each item the next
 * [transcription]; on any other model it is refused, as upstream refuses it. Speech (`Model.speak`) takes the next
 * queued [speech], and [speaks] and [speechError] make it refuse or fail, as a model that doesn't speak, or a failing
 * server, would.
 */
@OptIn(InternalLlamaBroApi::class)
public class FakeLlamaBro(script: FakeLlamaBro.() -> Unit = {}) {
    /** The model's context size (`Model.info.contextSize`); set it before loading. */
    public var contextSize: Int = 8192

    /** Whether the model sees (`Model.info.supportsVision`), so chat takes pictures; set it before loading. */
    public var supportsVision: Boolean = false

    /** Whether the model hears (`Model.info.supportsAudio`), so chat and `transcribe` take clips; set first. */
    public var supportsAudio: Boolean = false

    /** The voices the model speaks with (`Model.info.voices`); empty, the default, for a model that doesn't speak. */
    public var voices: List<String> = emptyList()

    /**
     * Whether upstream transcribes the model in a realtime session (`Model.transcriptionSession`): it has a streaming
     * audio encoder, as LFM3 has. Needs [supportsAudio]; set it before loading.
     */
    public var streamsAudio: Boolean = false

    /** With [streamsAudio], whether a transcription session is live (LFM3) rather than per commit. */
    public var transcribesLive: Boolean = true

    /**
     * Whether the model speaks, so `Model.speak` takes the queued [speech]. False answers every `speak` with upstream's
     * refusal (`this server does not support audio generation`, `NotSupported`), as a model that doesn't speak does.
     */
    public var speaks: Boolean = true

    // the scripts and recordings the engine, the model and the realtime sessions share (see FakeEngine.kt)
    internal val lock = Any()
    internal val queue = ArrayDeque<Scripted>()
    internal val chat = mutableListOf<String>()
    internal val chatMedia = mutableListOf<List<EngineAttachment>>()
    internal val taskQueue = ArrayDeque<TaskAnswer>()
    internal val embed = mutableListOf<String>()
    internal val rerank = mutableListOf<String>()
    internal val transcribe = mutableListOf<String>()
    internal val voiceQueue = ArrayDeque<VoiceReply>()
    internal val realtime = mutableListOf<String>()
    internal val transcriptionQueue = ArrayDeque<String>()
    internal val speechQueue = ArrayDeque<Pair<ShortArray, Int>>()

    /** Errors queued by [speechError]: the HTTP status and the body. */
    internal val speechErrors = ArrayDeque<Pair<Int, String>>()
    internal val speech = mutableListOf<String>()

    /**
     * How long each model load takes, reported as progress in four steps. A `loadModel` cancelled meanwhile
     * stops at the next step and throws `CancellationException`, as the phone's load does (D67).
     */
    @Volatile public var loadMillis: Long = 0

    internal val open = AtomicInteger()

    /** Models loaded and not yet closed: zero after a cancelled load, as on the phone. */
    public val openModels: Int get() = open.get()

    /** The body of every chat request that took a reply, in order (pre-encodes left out). */
    public val chatRequests: List<String> get() = synchronized(lock) { chat.toList() }

    /**
     * What went beside each of [chatRequests], at the same index (empty when nothing did): the files whose
     * `lb-attachment:<name>` placeholders the native side would write into the body (D84).
     */
    @InternalLlamaBroApi
    public val chatAttachments: List<List<EngineAttachment>> get() = synchronized(lock) { chatMedia.toList() }

    /** The body of every embeddings request (`Model.embed`), in order. */
    public val embedRequests: List<String> get() = synchronized(lock) { embed.toList() }

    /** The body of every rerank request (`Model.rerank`), in order. */
    public val rerankRequests: List<String> get() = synchronized(lock) { rerank.toList() }

    /** The form fields of every transcription (`Model.transcribe`), as JSON, in order. */
    public val transcribeRequests: List<String> get() = synchronized(lock) { transcribe.toList() }

    /**
     * Every client event of every voice session and every transcription session, as upstream would read it
     * (`session.update`, `input_audio_buffer.append`, `input_audio_buffer.commit`, `response.create`, …), in order.
     */
    public val realtimeEvents: List<String> get() = synchronized(lock) { realtime.toList() }

    /** The body of every speech request (`Model.speak`), as JSON, in order. */
    public val speechRequests: List<String> get() = synchronized(lock) { speech.toList() }

    /** The [LlamaBro] to hand to the code under test. */
    public val llamaBro: LlamaBro =
        LlamaBro.withEngine(
            FakeEngine(this),
            Files.createTempDirectory("llamabro-fake").toFile().apply { deleteOnExit() },
        )

    init {
        script()
    }

    internal var loadFailures = 0
    internal var loadFailure: NativeError? = null

    /**
     * Makes the next [times] model loads fail as the native side does: [reason] is what `ModelLoadFailed.reason` will
     * say (`InsufficientMemory` for a phone with no room, `FileNotFound`, or `Other`), [message] the native text.
     * Later loads succeed again.
     */
    public fun failLoad(
        reason: LlamaBroException.ModelLoadFailed.Reason,
        message: String = "the model could not be loaded",
        times: Int = 1,
    ) {
        val code =
            when (reason) {
                LlamaBroException.ModelLoadFailed.Reason.FileNotFound -> "file_not_found"
                LlamaBroException.ModelLoadFailed.Reason.InsufficientMemory -> "insufficient_memory"
                else -> "other"
            }
        synchronized(lock) {
            loadFailures = times
            loadFailure =
                NativeError(
                    NativeError.LOAD_FAILED,
                    JsonObject(
                        mapOf(
                            "error" to JsonPrimitive(message),
                            "code" to JsonPrimitive(code),
                        ),
                    ).toString(),
                )
        }
    }

    /** Queues a reply for the next chat request. */
    public fun reply(block: Reply.() -> Unit) {
        val r = Reply().apply(block)
        synchronized(lock) { queue += Scripted.Stream(r) }
    }

    /**
     * Queues the reply to the next `response.create` of a voice session (a `commit` after audio, `respond` or
     * `sendText`): its text and speech, in the order given. With none queued the session answers with an error event.
     */
    public fun voiceReply(block: VoiceReply.() -> Unit) {
        val r = VoiceReply().apply(block)
        synchronized(lock) { voiceQueue += r }
    }

    /**
     * Queues the text of the next item of a transcription session: sent as its delta and its completed transcript
     * when the item is committed. With none queued the session answers with an error event.
     */
    public fun transcription(text: String) {
        synchronized(lock) { transcriptionQueue += text }
    }

    /**
     * Queues the speech for the next `Model.speak`: [pcm], 16-bit mono at [sampleRate], streamed as upstream streams
     * WAV (a header, then the samples in two pieces). With none queued the request fails as a server error.
     */
    public fun speech(pcm: ShortArray, sampleRate: Int = 24000) {
        synchronized(lock) { speechQueue += pcm.copyOf() to sampleRate }
    }

    /**
     * Queues an error for the next `Model.speak`, answered before any queued [speech], as upstream answers a request it
     * refuses: e.g. `speechError(400, "invalid_request_error", "this model doesn't take a speaker reference")`.
     */
    public fun speechError(httpStatus: Int, type: String, message: String) {
        val body = """{"error":{"code":$httpStatus,"message":${q(message)},"type":"$type"}}"""
        synchronized(lock) { speechErrors += httpStatus to body }
    }

    /** Queues the vectors for the next `Model.embed`: one per input, in the inputs' order. */
    public fun embeddings(vararg vectors: FloatArray) {
        val copy = vectors.map { it.copyOf() }
        synchronized(lock) { taskQueue += TaskAnswer.Vectors(copy) }
    }

    /**
     * Queues the next `Model.rerank`'s scores, one per document in the documents' order. The answer is best first and
     * cut at `topN`, as llama-server does.
     */
    public fun ranking(vararg scores: Float) {
        val copy = scores.copyOf()
        synchronized(lock) { taskQueue += TaskAnswer.Scores(copy) }
    }

    /**
     * Queues the next `Model.transcribe`'s text. [truncated] ends it at the request's `maxTokens`, so `transcribe`
     * gives a `Transcript` with `truncated`; a truncated one sent without `maxTokens` fails that `transcribe` with a
     * `LlamaBroException.ServerError` saying to set it.
     */
    public fun transcript(text: String, truncated: Boolean = false) {
        synchronized(lock) { taskQueue += TaskAnswer.Transcript(text, truncated) }
    }

    /**
     * Queues llama-server's error for an input longer than the batch (`LlamaBroException.InputTooLong`) for the next
     * `Model.embed` or `Model.rerank`.
     */
    public fun inputTooLong(tokens: Int, limit: Int = 512) {
        val message =
            "input ($tokens tokens) is too large to process. increase the physical batch size " +
                "(current batch size: $limit)"
        synchronized(lock) { taskQueue += TaskAnswer.Error(serverError(message)) }
    }

    /** Queues llama-server's `exceed_context_size_error` (`LlamaBroException.ContextExceeded`). */
    public fun contextExceeded(promptTokens: Int, contextSize: Int) {
        error(
            400,
            "exceed_context_size_error",
            "request ($promptTokens tokens) exceeds the available context size ($contextSize tokens)",
            mapOf("n_prompt_tokens" to promptTokens, "n_ctx" to contextSize),
        )
    }

    /** Queues an error response, e.g. `error(503, "unavailable_error", "Loading model")`. */
    public fun error(httpStatus: Int, type: String, message: String) {
        error(httpStatus, type, message, emptyMap())
    }

    private fun error(httpStatus: Int, type: String, message: String, extra: Map<String, Int>) {
        val more = extra.entries.joinToString("") { (k, v) -> ",\"$k\":$v" }
        val body = """{"error":{"code":$httpStatus,"message":${q(message)},"type":"$type"$more}}"""
        synchronized(lock) { queue += Scripted.Error(httpStatus, body) }
    }

    /** One scripted reply, built in the order llama-server streams it: reasoning, content, tool calls. */
    public class Reply internal constructor() {
        internal val reasoning = StringBuilder()
        internal val content = StringBuilder()
        internal val toolCalls = mutableListOf<Triple<String?, String, String>>()
        internal var timings = intArrayOf(10, 0, -1)
        internal var finishLength = false

        public fun reasoning(text: String) {
            reasoning.append(text)
        }

        public fun content(text: String) {
            content.append(text)
        }

        /** A tool call; [arguments] is the JSON the model wrote. [id] defaults to `call_<n>`. */
        public fun toolCall(name: String, arguments: String, id: String? = null) {
            toolCalls +=
                Triple(id, name, arguments)
        }

        /** The reply's `timings` (they feed `Conversation.contextUsage`); [predictedTokens] defaults to a guess. */
        public fun timings(promptTokens: Int, cachedTokens: Int = 0, predictedTokens: Int = -1) {
            timings = intArrayOf(promptTokens, cachedTokens, predictedTokens)
        }

        /** Ends the reply at the output limit (`FinishReason.Length`) instead of a stop. */
        public fun finishLength() {
            finishLength = true
        }
    }

    /** One scripted voice reply: text (the captions) and speech, sent as deltas in the order given. */
    public class VoiceReply internal constructor() {
        internal val parts = mutableListOf<Any>()

        /** The next piece of the reply's text. */
        public fun text(delta: String) {
            parts += delta
        }

        /** The next piece of the reply's speech: 16-bit mono PCM at 24 kHz, as LFM3 speaks. */
        public fun audio(pcm: ShortArray) {
            parts += pcm.copyOf()
        }

        internal var outputTokens: Int? = null

        /** The reply's `usage.output_tokens`; by default a guess from its text. */
        public fun outputTokens(tokens: Int) {
            outputTokens = tokens
        }
    }

    internal sealed interface Scripted {
        class Stream(val reply: Reply) : Scripted

        class Error(val status: Int, val body: String) : Scripted
    }

    /** An answer for `Model.embed`, `Model.rerank` or `Model.transcribe`, which have their own queue. */
    internal sealed interface TaskAnswer {
        class Vectors(val vectors: List<FloatArray>) : TaskAnswer

        class Scores(val scores: FloatArray) : TaskAnswer

        class Transcript(val text: String, val cut: Boolean) : TaskAnswer

        class Error(val body: String) : TaskAnswer
    }
}
