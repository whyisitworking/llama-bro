package com.suhel.llamabro.testing

import com.suhel.llamabro.InternalLlamaBroApi
import com.suhel.llamabro.LlamaBro
import com.suhel.llamabro.LlamaBroException
import com.suhel.llamabro.spi.Engine
import com.suhel.llamabro.spi.EngineAttachment
import com.suhel.llamabro.spi.EngineModel
import com.suhel.llamabro.spi.EngineRequest
import com.suhel.llamabro.spi.NativeError
import com.suhel.llamabro.spi.RequestSink
import com.suhel.llamabro.spi.SEND_CLOSED
import com.suhel.llamabro.spi.SEND_OK
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean
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
 * [transcription]; on any other model it is refused, as upstream refuses it.
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

    private val lock = Any()
    private val queue = ArrayDeque<Scripted>()
    private val chat = mutableListOf<String>()
    private val chatMedia = mutableListOf<List<EngineAttachment>>()
    private val taskQueue = ArrayDeque<TaskAnswer>()
    private val embed = mutableListOf<String>()
    private val rerank = mutableListOf<String>()
    private val transcribe = mutableListOf<String>()
    private val voiceQueue = ArrayDeque<VoiceReply>()
    private val voice = mutableListOf<String>()
    private val transcriptionQueue = ArrayDeque<String>()
    private val speechQueue = ArrayDeque<Pair<ShortArray, Int>>()
    private val speech = mutableListOf<String>()

    /**
     * How long each model load takes, reported as progress in four steps. A `loadModel` cancelled meanwhile
     * stops at the next step and throws `CancellationException`, as the phone's load does (D67).
     */
    @Volatile public var loadMillis: Long = 0

    private val open = AtomicInteger()

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
     * Every client event of every voice and transcription session, as upstream would read it (`session.update`,
     * `input_audio_buffer.append`, `input_audio_buffer.commit`, `response.create`, …), in order.
     */
    public val voiceEvents: List<String> get() = synchronized(lock) { voice.toList() }

    /** The body of every speech request (`Model.speak`), as JSON, in order. */
    public val speechRequests: List<String> get() = synchronized(lock) { speech.toList() }

    /** The [LlamaBro] to hand to the code under test. */
    public val llamaBro: LlamaBro =
        LlamaBro.withEngine(
            FakeEngine(),
            Files.createTempDirectory("llamabro-fake").toFile().apply { deleteOnExit() },
        )

    init {
        script()
    }

    private var loadFailures = 0
    private var loadFailure: NativeError? = null

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
                    LOAD_FAILED_STATUS,
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

    private sealed interface Scripted {
        class Stream(val reply: Reply) : Scripted

        class Error(val status: Int, val body: String) : Scripted
    }

    /** An answer for `Model.embed` or `Model.rerank`, which have their own queue. */
    private sealed interface TaskAnswer {
        class Vectors(val vectors: List<FloatArray>) : TaskAnswer

        class Scores(val scores: FloatArray) : TaskAnswer

        class Transcript(val text: String, val cut: Boolean) : TaskAnswer

        class Error(val body: String) : TaskAnswer
    }

    private inner class FakeEngine : Engine {
        override fun init(configJson: String): String? = null

        override fun readFd(fd: Int): ByteArray = readDevFd(fd)

        override fun setLog(listener: ((level: Int, text: String) -> Unit)?) {}

        override fun devices(): String =
            """[{"name":"CPU","description":"FakeLlamaBro","type":"cpu","memory_free":0,"memory_total":0}]"""

        override fun load(configJson: String, onProgress: (Float) -> Boolean): EngineModel {
            synchronized(lock) {
                if (loadFailures > 0) {
                    loadFailures--
                    throw checkNotNull(loadFailure)
                }
            }
            for (step in 1..LOAD_STEPS) {
                if (loadMillis > 0) Thread.sleep(loadMillis / LOAD_STEPS)
                if (!onProgress(step.toFloat() / LOAD_STEPS)) {
                    throw NativeError(NativeError.CANCELLED, """{"error":"model load cancelled"}""")
                }
            }
            open.incrementAndGet()
            val config = runCatching { Json.parseToJsonElement(configJson) as JsonObject }.getOrNull()
            val slots = (config?.get("n_parallel") as? JsonPrimitive)?.intOrNull ?: 1
            return FakeModel(
                contextSize,
                supportsVision,
                supportsAudio,
                slots,
                voices.toList(),
                streamsAudio,
                transcribesLive,
            )
        }
    }

    private inner class FakeModel(
        private val nCtx: Int,
        vision: Boolean,
        private val audio: Boolean,
        slots: Int,
        voices: List<String>,
        private val streams: Boolean,
        private val live: Boolean,
    ) : EngineModel {
        override val infoJson: String = """{"n_ctx":$nCtx,"n_ctx_train":$nCtx,"n_params":0,"size":0,"desc":"FakeLlamaBro",
            "metadata":{},"props":{"total_slots":$slots,"voices":${voices.joinToString(
            ",",
            "[",
            "]",
        ) { q(it) }},"chat_template":"","modalities":{"vision":$vision,"video":false,"audio":$audio},
            "chat_template_caps":{"supports_tools":true,"supports_parallel_tool_calls":true,"supports_system_role":true}}}"""

        override fun request(
            endpoint: String,
            paramsJson: String?,
            body: ByteArray,
            attachments: List<EngineAttachment>,
            sink: RequestSink,
        ): EngineRequest {
            val text = String(body, Charsets.UTF_8)

            fun send(status: Int, data: String) = sink.onChunk(status, data.toByteArray(Charsets.UTF_8))
            when (endpoint) {
                "chat" -> {
                    chat(text, attachments, ::send)
                }

                "embeddings", "rerank", "transcriptions" -> {
                    task(endpoint, text, ::send)
                }

                "slots" -> {
                    send(200, """[{"id":0,"is_processing":false}]""")
                }

                "control" -> {
                    send(200, "{}")
                }

                "speech" -> {
                    speak(text, sink)
                }

                "realtime" -> {
                    val intent = paramsJson?.let { Json.parseToJsonElement(it) as? JsonObject }?.get("intent")
                    return if ((intent as? JsonPrimitive)?.content == "transcription") {
                        Transcription(sink, audio && streams, live)
                    } else {
                        VoiceConversation(sink, audio)
                    }
                }

                else -> {
                    send(501, notSupported("FakeLlamaBro doesn't script the $endpoint route"))
                }
            }
            sink.onDone()
            return Finished
        }

        private fun chat(body: String, attachments: List<EngineAttachment>, send: (Int, String) -> Unit) {
            val json = runCatching { Json.parseToJsonElement(body) as? JsonObject }.getOrNull()
            if ((json?.get("n_predict") as? JsonPrimitive)?.intOrNull == 0) {
                send(200, """{"choices":[],"timings":{"cache_n":0,"prompt_n":10,"predicted_n":0}}""")
                return
            }
            if ((json?.get("stream") as? JsonPrimitive)?.booleanOrNull != true) {
                send(501, notSupported("FakeLlamaBro scripts streamed chat completions only"))
                return
            }
            val next =
                synchronized(lock) {
                    chat += body
                    chatMedia += attachments
                    queue.removeFirstOrNull()
                        ?: return send(
                            500,
                            serverError("FakeLlamaBro: no reply scripted for chat request ${chat.size}"),
                        )
                }
            when (next) {
                is Scripted.Error -> send(next.status, next.body)
                is Scripted.Stream -> stream(next.reply).forEach { send(200, it) }
            }
        }

        /** Upstream's streamed WAV (handle_tts_impl): a header with unknown sizes, then the samples. */
        private fun speak(body: String, sink: RequestSink) {
            val next =
                synchronized(lock) {
                    speech += body
                    speechQueue.removeFirstOrNull()
                }
            if (next == null) {
                sink.onChunk(500, serverError("FakeLlamaBro: no speech scripted").toByteArray(Charsets.UTF_8))
                return
            }
            val (pcm, rate) = next
            val header = ByteBuffer.allocate(WAV_HEADER).order(ByteOrder.LITTLE_ENDIAN)
            header.put("RIFF".toByteArray()).putInt(-1).put("WAVE".toByteArray())
            header
                .put("fmt ".toByteArray())
                .putInt(16)
                .putShort(1)
                .putShort(1)
                .putInt(rate)
            header.putInt(rate * 2).putShort(2).putShort(16)
            header.put("data".toByteArray()).putInt(-1)
            sink.onChunk(200, header.array())
            val bytes = ByteBuffer.allocate(pcm.size * 2).order(ByteOrder.LITTLE_ENDIAN)
            pcm.forEach { bytes.putShort(it) }
            val all = bytes.array()
            val half = all.size / 2
            if (half > 0) sink.onChunk(200, all.copyOfRange(0, half))
            if (all.size > half) sink.onChunk(200, all.copyOfRange(half, all.size))
        }

        private fun task(endpoint: String, body: String, send: (Int, String) -> Unit) {
            val next =
                synchronized(lock) {
                    val log =
                        when (endpoint) {
                            "rerank" -> rerank
                            "transcriptions" -> transcribe
                            else -> embed
                        }
                    log += body
                    taskQueue.removeFirstOrNull()
                        ?: return send(
                            500,
                            serverError("FakeLlamaBro: nothing scripted for $endpoint request ${log.size}"),
                        )
                }
            val mismatch = serverError("FakeLlamaBro: the next scripted answer isn't for $endpoint")
            when (next) {
                is TaskAnswer.Error -> {
                    send(500, next.body)
                }

                is TaskAnswer.Vectors -> {
                    if (endpoint == "embeddings") send(200, embeddingsJson(next.vectors)) else send(500, mismatch)
                }

                is TaskAnswer.Scores -> {
                    if (endpoint == "rerank") send(200, rankingJson(next.scores, body)) else send(500, mismatch)
                }

                is TaskAnswer.Transcript -> {
                    when {
                        endpoint != "transcriptions" -> send(500, mismatch)
                        next.cut && maxTokens(body) == null -> send(500, serverError(CUT_WITHOUT_LIMIT))
                        else -> send(200, transcriptJson(next, body))
                    }
                }
            }
        }

        private val closed = AtomicBoolean(false)

        override fun close() {
            if (closed.compareAndSet(false, true)) open.decrementAndGet()
        }
    }

    /**
     * Upstream's realtime conversation as far as a voice app sees it (server-realtime.cpp): session setup, audio
     * turns, replies from [voiceQueue] and errors, answered on the sending thread, one event at a time.
     */
    private inner class VoiceConversation(private val sink: RequestSink, private val hears: Boolean) : EngineRequest {
        private val open = AtomicBoolean(true)
        private var hasAudio = false
        private var items = 0
        private var voiceName: String? = null
        private var language: String? = null

        init {
            if (hears) {
                event("""{"type":"session.created","session":${session()}}""")
            } else {
                event(error("The current model does not support realtime transcription."))
                end()
            }
        }

        override fun send(message: ByteArray): Int =
            synchronized(this) {
                if (!open.get()) return SEND_CLOSED
                val text = String(message, Charsets.UTF_8)
                synchronized(lock) { voice += text }
                handle(text)
                SEND_OK
            }

        private fun handle(text: String) {
            val ev = runCatching { Json.parseToJsonElement(text) as JsonObject }.getOrNull()
            when ((ev?.get("type") as? JsonPrimitive)?.content) {
                null -> {
                    event(error("invalid JSON"))
                }

                "session.update" -> {
                    val out =
                        ((ev["session"] as? JsonObject)?.get("audio") as? JsonObject)?.get("output") as? JsonObject
                    out?.get("voice")?.let { voiceName = (it as? JsonPrimitive)?.content }
                    out?.get("language")?.let { language = (it as? JsonPrimitive)?.content }
                    event("""{"type":"session.updated","session":${session()}}""")
                }

                "input_audio_buffer.append" -> {
                    hasAudio = true
                }

                "input_audio_buffer.commit" -> {
                    if (!hasAudio) {
                        event(error("input_audio_buffer.commit: the buffer is empty", COMMIT_EMPTY))
                    } else {
                        hasAudio = false
                        event(
                            """{"type":"input_audio_buffer.committed","previous_item_id":null,""" +
                                """"item_id":"item_${++items}"}""",
                        )
                    }
                }

                "conversation.item.create" -> {
                    event("""{"type":"conversation.item.added","item":{"id":"item_${++items}"}}""")
                }

                "response.create" -> {
                    reply()
                }

                "response.cancel" -> {
                    Unit // replies are sent whole, so there is nothing left to stop
                }

                else -> {
                    event(error("unknown event type: ${(ev["type"] as JsonPrimitive).content}"))
                }
            }
        }

        private fun reply() {
            val next =
                synchronized(lock) { voiceQueue.removeFirstOrNull() }
                    ?: return event(error("FakeLlamaBro: no voice reply scripted", "server_error"))
            val id = "resp_${++items}"
            event("""{"type":"response.created","response":{"id":"$id","status":"in_progress","output":[]}}""")
            val text = StringBuilder()
            for (part in next.parts) {
                if (part is String) {
                    text.append(part)
                    event("""{"type":"response.output_text.delta","response_id":"$id","delta":${q(part)}}""")
                } else {
                    val pcm = part as ShortArray
                    val bytes = ByteBuffer.allocate(pcm.size * 2).order(ByteOrder.LITTLE_ENDIAN)
                    pcm.forEach { bytes.putShort(it) }
                    val b64 = Base64.getEncoder().encodeToString(bytes.array())
                    event("""{"type":"response.output_audio.delta","response_id":"$id","delta":"$b64"}""")
                }
            }
            val tokens = next.outputTokens ?: maxOf(1, text.length / 4)
            event(
                """{"type":"response.done","response":{"id":"$id","status":"completed","output":[{"type":"message",""" +
                    """"role":"assistant","content":[{"type":"output_text","text":${q(text.toString())}}]}],""" +
                    """"usage":{"output_tokens":$tokens}}}""",
            )
        }

        private fun session(): String =
            """{"id":"sess_fake","type":"realtime","audio":{"input":{"format":{"type":"audio/pcm","rate":16000}},""" +
                """"output":{"format":{"type":"audio/pcm","rate":24000},"voice":${voiceName?.let(::q)},""" +
                """"language":${language?.let(::q)}}}}"""

        private fun error(message: String, type: String = "invalid_request_error") =
            """{"type":"error","error":{"type":"$type","code":null,"message":${q(message)},"param":null}}"""

        private fun event(json: String) = sink.onChunk(200, json.toByteArray(Charsets.UTF_8))

        private fun end() {
            if (open.compareAndSet(true, false)) sink.onDone()
        }

        override fun cancel() {
            synchronized(this) {
                if (open.compareAndSet(true, false)) sink.onCancelled()
            }
        }

        override fun free() {}
    }

    /**
     * Upstream's realtime transcription as far as a dictating app sees it (server-context.cpp ws_realtime and
     * server-realtime.cpp): refused on a model that doesn't stream audio, else a live or a per-commit session whose
     * items take [transcriptionQueue]'s texts. A live item's text is sent when it is committed, not as audio arrives.
     */
    private inner class Transcription(private val sink: RequestSink, streams: Boolean, private val live: Boolean) :
        EngineRequest {
        private val open = AtomicBoolean(true)
        private var hasAudio = false
        private var items = 0
        private var rate = 16000

        init {
            if (streams) {
                event("""{"type":"session.created","session":${session(if (live) "transcription" else "realtime")}}""")
            } else {
                event(error("The current model does not support realtime transcription."))
                end()
            }
        }

        override fun send(message: ByteArray): Int =
            synchronized(this) {
                if (!open.get()) return SEND_CLOSED
                val text = String(message, Charsets.UTF_8)
                synchronized(lock) { voice += text }
                handle(text)
                SEND_OK
            }

        private fun handle(text: String) {
            val ev = runCatching { Json.parseToJsonElement(text) as JsonObject }.getOrNull()
            when ((ev?.get("type") as? JsonPrimitive)?.content) {
                null -> {
                    event(error("invalid JSON"))
                }

                "session.update" -> {
                    val input =
                        ((ev["session"] as? JsonObject)?.get("audio") as? JsonObject)?.get("input") as? JsonObject
                    val format = input?.get("format") as? JsonObject
                    (format?.get("rate") as? JsonPrimitive)?.content?.toIntOrNull()?.let { rate = it }
                    event("""{"type":"session.updated","session":${session("transcription")}}""")
                }

                "input_audio_buffer.append" -> {
                    hasAudio = true
                }

                "input_audio_buffer.commit" -> {
                    commit((ev["final"] as? JsonPrimitive)?.content == "true")
                }

                else -> {
                    event(error("unknown event type: ${(ev["type"] as JsonPrimitive).content}"))
                }
            }
        }

        private fun commit(final: Boolean) {
            if (!hasAudio && !live) {
                if (!final) event(error("input_audio_buffer.commit: the buffer is empty", COMMIT_EMPTY))
                return
            }
            hasAudio = false
            val id = "item_${++items}"
            // upstream's live path ends the input without a committed event (server-context.cpp ws_realtime)
            if (!(live && final)) {
                event("""{"type":"input_audio_buffer.committed","previous_item_id":null,"item_id":"$id"}""")
            }
            val text =
                synchronized(lock) { transcriptionQueue.removeFirstOrNull() }
                    ?: return event(error("FakeLlamaBro: no transcription scripted", "server_error"))
            if (text.isNotEmpty()) {
                event(
                    """{"type":"conversation.item.input_audio_transcription.delta","item_id":"$id",""" +
                        """"content_index":0,"delta":${q(text)}}""",
                )
            }
            event(
                """{"type":"conversation.item.input_audio_transcription.completed","item_id":"$id",""" +
                    """"content_index":0,"transcript":${q(text)}}""",
            )
        }

        private fun session(type: String): String =
            """{"id":"sess_fake","object":"realtime.transcription_session","type":"$type",""" +
                """"audio":{"input":{"format":{"type":"audio/pcm","rate":$rate},"turn_detection":null}}}"""

        private fun error(message: String, type: String = "invalid_request_error") =
            """{"type":"error","error":{"type":"$type","code":null,"message":${q(message)},"param":null}}"""

        private fun event(json: String) = sink.onChunk(200, json.toByteArray(Charsets.UTF_8))

        private fun end() {
            if (open.compareAndSet(true, false)) sink.onDone()
        }

        override fun cancel() {
            synchronized(this) {
                if (open.compareAndSet(true, false)) sink.onCancelled()
            }
        }

        override fun free() {}
    }

    private object Finished : EngineRequest {
        override fun cancel() {}

        override fun free() {}
    }

    private companion object {
        /** A WAV header's bytes: RIFF, fmt and data. */
        private const val WAV_HEADER = 44

        /** Upstream's error type for a commit with no audio. */
        private const val COMMIT_EMPTY = "input_audio_buffer_commit_empty"

        /** The progress steps of a fake load. */
        private const val LOAD_STEPS = 4

        private var callIds = 0

        /** `lb_status` for a failed load (the C API's generic error). */
        private const val LOAD_FAILED_STATUS = 1

        fun q(s: String): String = JsonPrimitive(s).toString()

        fun notSupported(message: String) =
            """{"error":{"code":501,"message":${q(message)},"type":"not_supported_error"}}"""

        /** `format_embeddings_response_oaicompat` with base64: each vector's floats, little-endian. */
        fun embeddingsJson(vectors: List<FloatArray>): String =
            vectors
                .mapIndexed { i, v ->
                    val bytes = ByteBuffer.allocate(v.size * Float.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
                    v.forEach { bytes.putFloat(it) }
                    val b64 = Base64.getEncoder().encodeToString(bytes.array())
                    """{"embedding":"$b64","index":$i,"object":"embedding","encoding_format":"base64"}"""
                }.joinToString(",", """{"model":"FakeLlamaBro","object":"list","data":[""", "]}")

        /** `format_response_rerank`: best first, cut at the request's `top_n`. */
        fun rankingJson(scores: FloatArray, body: String): String {
            val topN =
                runCatching { ((Json.parseToJsonElement(body) as JsonObject)["top_n"] as JsonPrimitive).intOrNull }
                    .getOrNull() ?: scores.size
            return scores
                .withIndex()
                .sortedByDescending { it.value }
                .take(topN)
                .joinToString(",", """{"model":"FakeLlamaBro","object":"list","results":[""", "]}") {
                    """{"index":${it.index},"relevance_score":${it.value}}"""
                }
        }

        /** `to_json_oaicompat_asr`; a cut one wrote as many tokens as the request's `max_tokens` allowed. */
        fun transcriptJson(t: TaskAnswer.Transcript, body: String): String {
            val limit = maxTokens(body)
            val written = if (t.cut && limit != null) limit else maxOf(1, t.text.length / 4)
            return """{"type":"transcript.text.done","text":${q(
                t.text,
            )},"usage":{"type":"tokens","input_tokens":60,""" +
                """"output_tokens":$written,"total_tokens":${60 + written},"input_tokens_details":{"cached_tokens":0}}}"""
        }

        /** A transcription's `max_tokens`, null when the request has none. */
        fun maxTokens(body: String): Int? =
            runCatching {
                ((Json.parseToJsonElement(body) as JsonObject)["max_tokens"] as JsonPrimitive).content.toInt()
            }.getOrNull()

        /** A cut transcript ends at `max_tokens`; without one, nothing says where, so the script is wrong. */
        private const val CUT_WITHOUT_LIMIT =
            "FakeLlamaBro: a cut transcript ends at the request's max_tokens; set maxTokens to script a cut transcript"

        fun serverError(message: String) = """{"error":{"code":500,"message":${q(message)},"type":"server_error"}}"""

        /** llama-server's chat stream (server-task.cpp `to_json_oaicompat_chat_stream`), in its order. */
        fun stream(r: Reply): List<String> {
            val id = "chatcmpl-fake"

            fun chunk(delta: String, finish: String? = null, extra: String = "") =
                "data: {\"choices\":[{\"finish_reason\":${finish?.let {
                    "\"$it\""
                } ?: "null"},\"index\":0,\"delta\":$delta}]," +
                    "\"id\":\"$id\",\"object\":\"chat.completion.chunk\"$extra}\n\n"
            val out = mutableListOf(chunk("{\"role\":\"assistant\",\"content\":null}"))
            r.reasoning
                .toString()
                .chunked(4)
                .forEach { out += chunk("{\"reasoning_content\":${q(it)}}") }
            r.content
                .toString()
                .chunked(4)
                .forEach { out += chunk("{\"content\":${q(it)}}") }
            r.toolCalls.forEachIndexed { i, (callId, name, args) ->
                val cid = callId ?: synchronized(this) { "call_${++callIds}" }
                out +=
                    chunk(
                        "{\"tool_calls\":[{\"index\":$i,\"id\":${q(
                            cid,
                        )},\"type\":\"function\",\"function\":{\"name\":${q(name)},\"arguments\":\"\"}}]}",
                    )
                args.chunked(8).forEach {
                    out +=
                        chunk("{\"tool_calls\":[{\"index\":$i,\"function\":{\"arguments\":${q(it)}}}]}")
                }
            }
            val (promptN, cacheN, predicted) = r.timings.let { Triple(it[0], it[1], it[2]) }
            val predictedN = if (predicted >= 0) predicted else maxOf(1, (r.reasoning.length + r.content.length) / 4)
            val finish =
                when {
                    r.finishLength -> "length"
                    r.toolCalls.isNotEmpty() -> "tool_calls"
                    else -> "stop"
                }
            out +=
                chunk(
                    "{}",
                    finish,
                    ",\"timings\":{\"cache_n\":$cacheN,\"prompt_n\":$promptN,\"prompt_per_second\":100.0," +
                        "\"predicted_n\":$predictedN,\"predicted_per_second\":10.0}",
                )
            out += "data: [DONE]\n\n"
            return out
        }
    }
}
