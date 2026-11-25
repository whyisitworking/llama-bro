package com.suhel.llamabro.testing

import com.suhel.llamabro.InternalLlamaBroApi
import com.suhel.llamabro.spi.Engine
import com.suhel.llamabro.spi.EngineAttachment
import com.suhel.llamabro.spi.EngineModel
import com.suhel.llamabro.spi.EngineRequest
import com.suhel.llamabro.spi.NativeError
import com.suhel.llamabro.spi.RequestSink
import com.suhel.llamabro.testing.FakeWire.notSupported
import com.suhel.llamabro.testing.FakeWire.q
import com.suhel.llamabro.testing.FakeWire.serverError
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean

/** The [Engine] behind [FakeLlamaBro.llamaBro]: loads, and routes each request to what the fake scripted. */
@OptIn(InternalLlamaBroApi::class)
internal class FakeEngine(private val fake: FakeLlamaBro) : Engine {
    override fun init(configJson: String): String? {
        fake.checkOpen()
        return null
    }

    override fun readFd(fd: Int): ByteArray = readDevFd(fd)

    override fun setLog(listener: ((level: Int, text: String) -> Unit)?) {}

    override fun devices(): String {
        fake.checkOpen()
        return """[{"name":"CPU","description":"FakeLlamaBro","type":"cpu","memory_free":0,"memory_total":0}]"""
    }

    override fun load(configJson: String, onProgress: (Float) -> Boolean): EngineModel {
        fake.checkOpen()
        synchronized(fake.lock) {
            if (fake.loadFailures > 0) {
                fake.loadFailures--
                throw checkNotNull(fake.loadFailure)
            }
        }
        for (step in 1..LOAD_STEPS) {
            if (fake.loadMillis > 0) Thread.sleep(fake.loadMillis / LOAD_STEPS)
            if (!onProgress(step.toFloat() / LOAD_STEPS)) {
                throw NativeError(NativeError.CANCELLED, """{"error":"model load cancelled"}""")
            }
        }
        fake.open.incrementAndGet()
        val config = runCatching { Json.parseToJsonElement(configJson) as JsonObject }.getOrNull()
        val slots = (config?.get("n_parallel") as? JsonPrimitive)?.intOrNull ?: 1
        return FakeModel(
            fake,
            fake.contextSize,
            fake.supportsVision,
            fake.supportsAudio,
            slots,
            fake.voices.toList(),
            fake.streamsAudio,
            fake.transcribesLive,
        )
    }

    private companion object {
        /** The progress steps of a fake load. */
        const val LOAD_STEPS = 4
    }
}

/** A loaded fake model: answers each endpoint from the fake's queues and records what it was sent. */
@OptIn(InternalLlamaBroApi::class)
internal class FakeModel(
    private val fake: FakeLlamaBro,
    private val nCtx: Int,
    vision: Boolean,
    private val audio: Boolean,
    slots: Int,
    voices: List<String>,
    private val streams: Boolean,
    private val live: Boolean,
) : EngineModel {
    override val infoJson: String =
        buildJsonObject {
            put("n_ctx", nCtx)
            put("n_ctx_train", nCtx)
            put("n_params", 0)
            put("size", 0)
            put("desc", "FakeLlamaBro")
            put("metadata", JsonObject(emptyMap()))
            put(
                "props",
                buildJsonObject {
                    put("total_slots", slots)
                    put("voices", JsonArray(voices.map(::JsonPrimitive)))
                    put("chat_template", "")
                    put(
                        "modalities",
                        buildJsonObject {
                            put("vision", vision)
                            put("video", false)
                            put("audio", audio)
                        },
                    )
                    put(
                        "chat_template_caps",
                        buildJsonObject {
                            put("supports_tools", true)
                            put("supports_parallel_tool_calls", true)
                            put("supports_system_role", true)
                        },
                    )
                },
            )
        }.toString()

    override fun request(
        endpoint: String,
        paramsJson: String?,
        body: ByteArray,
        attachments: List<EngineAttachment>,
        sink: RequestSink,
    ): EngineRequest {
        fake.checkOpen()
        val text = String(body, Charsets.UTF_8)

        fun send(status: Int, data: String) = sink.onChunk(status, data.toByteArray(Charsets.UTF_8))
        synchronized(fake.lock) { fake.rawQueue.removeFirstOrNull() }?.let { raw ->
            raw.chunks.forEach { send(raw.status, it) }
            if (raw.holdOpen) return Held(fake, sink)
            sink.onDone()
            return Finished
        }
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
                    FakeTranscription(fake, sink, audio && streams, live)
                } else {
                    FakeVoiceConversation(fake, sink)
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
            synchronized(fake.lock) {
                fake.chat += body
                fake.chatMedia += attachments
                fake.queue.removeFirstOrNull()
                    ?: return send(
                        500,
                        serverError("FakeLlamaBro: no reply scripted for chat request ${fake.chat.size}"),
                    )
            }
        when (next) {
            is FakeLlamaBro.Scripted.Error -> send(next.status, next.body)
            is FakeLlamaBro.Scripted.Stream -> FakeWire.stream(next.reply).forEach { send(200, it) }
        }
    }

    /** Upstream's streamed WAV (handle_tts_impl): a header with unknown sizes, then the samples. */
    private fun speak(body: String, sink: RequestSink) {
        val (refusal, next) =
            synchronized(fake.lock) {
                fake.speech += body
                // a model that doesn't speak refuses every request; a queued error answers the next one
                val refused = if (fake.speaks) fake.speechErrors.removeFirstOrNull() else NO_SPEECH
                refused to if (refused == null) fake.speechQueue.removeFirstOrNull() else null
            }
        if (refusal != null) {
            sink.onChunk(refusal.first, refusal.second.toByteArray(Charsets.UTF_8))
            return
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
            synchronized(fake.lock) {
                val log =
                    when (endpoint) {
                        "rerank" -> fake.rerank
                        "transcriptions" -> fake.transcribe
                        else -> fake.embed
                    }
                log += body
                fake.taskQueue.removeFirstOrNull()
                    ?: return send(
                        500,
                        serverError("FakeLlamaBro: nothing scripted for $endpoint request ${log.size}"),
                    )
            }
        val mismatch = serverError("FakeLlamaBro: the next scripted answer isn't for $endpoint")
        when (next) {
            is FakeLlamaBro.TaskAnswer.Error -> {
                send(500, next.body)
            }

            is FakeLlamaBro.TaskAnswer.Vectors -> {
                if (endpoint == "embeddings") send(200, FakeWire.embeddingsJson(next.vectors)) else send(500, mismatch)
            }

            is FakeLlamaBro.TaskAnswer.Scores -> {
                if (endpoint == "rerank") send(200, FakeWire.rankingJson(next.scores, body)) else send(500, mismatch)
            }

            is FakeLlamaBro.TaskAnswer.Transcript -> {
                when {
                    endpoint != "transcriptions" -> send(500, mismatch)
                    next.cut && FakeWire.maxTokens(body) == null -> send(500, serverError(CUT_WITHOUT_LIMIT))
                    else -> send(200, FakeWire.transcriptJson(next, body))
                }
            }
        }
    }

    private val closed = AtomicBoolean(false)

    override fun close() {
        if (closed.compareAndSet(false, true)) fake.open.decrementAndGet()
    }

    private companion object {
        /** A WAV header's bytes: RIFF, fmt and data. */
        const val WAV_HEADER = 44

        /** What upstream answers a speech request on a model that doesn't speak (server-context.cpp, handle_tts). */
        val NO_SPEECH = FakeWire.NOT_SUPPORTED_STATUS to notSupported("this server does not support audio generation")

        /** A cut transcript ends at `max_tokens`; without one, nothing says where, so the script is wrong. */
        const val CUT_WITHOUT_LIMIT =
            "FakeLlamaBro: a cut transcript ends at the request's max_tokens; set maxTokens to script a cut transcript"
    }
}

/** A request that was answered whole: nothing is left to cancel or free. */
@OptIn(InternalLlamaBroApi::class)
internal object Finished : EngineRequest {
    override fun cancel() {}

    override fun free() {}
}

/** A request [FakeLlamaBro.raw] holds open: it ends when cancelled, as a streaming one does. */
@OptIn(InternalLlamaBroApi::class)
internal class Held(private val fake: FakeLlamaBro, private val sink: RequestSink) : EngineRequest {
    private val ended = AtomicBoolean(false)

    override fun cancel() {
        if (ended.compareAndSet(false, true)) {
            fake.cancelled.incrementAndGet()
            sink.onCancelled()
        }
    }

    override fun free() {}
}

/** The JSON the fake answers with, as llama-server writes it. */
internal object FakeWire {
    const val NOT_SUPPORTED_STATUS = 501

    private var callIds = 0

    fun q(s: String): String = JsonPrimitive(s).toString()

    fun notSupported(message: String) =
        """{"error":{"code":$NOT_SUPPORTED_STATUS,"message":${q(message)},"type":"not_supported_error"}}"""

    fun serverError(message: String) = """{"error":{"code":500,"message":${q(message)},"type":"server_error"}}"""

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
    fun transcriptJson(t: FakeLlamaBro.TaskAnswer.Transcript, body: String): String {
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

    /** llama-server's chat stream (server-task.cpp `to_json_oaicompat_chat_stream`), in its order. */
    fun stream(r: FakeLlamaBro.Reply): List<String> {
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
