package com.suhel.llamabro.testing

import com.suhel.llamabro.InternalLlamaBroApi
import com.suhel.llamabro.LlamaBro
import com.suhel.llamabro.internal.Engine
import com.suhel.llamabro.internal.EngineModel
import com.suhel.llamabro.internal.EngineRequest
import com.suhel.llamabro.internal.RequestSink
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import java.nio.file.Files

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
 * Only streamed chat completions are scripted (what `Conversation` and `Model.chat` send). `GET /slots` and the
 * reasoning control answer as an idle server would; any other route fails with `NotSupported`. Pre-encodes
 * (`preEncodeAfterReply`) are answered without taking a reply.
 */
@OptIn(InternalLlamaBroApi::class)
public class FakeLlamaBro(script: FakeLlamaBro.() -> Unit = {}) {
    /** The model's context size (`Model.info.contextSize`); set it before loading. */
    public var contextSize: Int = 8192

    private val lock = Any()
    private val queue = ArrayDeque<Scripted>()
    private val chat = mutableListOf<String>()

    /** The body of every chat request that took a reply, in order (pre-encodes left out). */
    public val chatRequests: List<String> get() = synchronized(lock) { chat.toList() }

    /** The [LlamaBro] to hand to the code under test. */
    public val llamaBro: LlamaBro = LlamaBro.withEngine(
        FakeEngine(),
        Files.createTempDirectory("llamabro-fake").toFile().apply { deleteOnExit() },
    )

    init {
        script()
    }

    /** Queues a reply for the next chat request. */
    public fun reply(block: Reply.() -> Unit) {
        val r = Reply().apply(block)
        synchronized(lock) { queue += Scripted.Stream(r) }
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

        public fun reasoning(text: String) { reasoning.append(text) }

        public fun content(text: String) { content.append(text) }

        /** A tool call; [arguments] is the JSON the model wrote. [id] defaults to `call_<n>`. */
        public fun toolCall(name: String, arguments: String, id: String? = null) { toolCalls += Triple(id, name, arguments) }

        /** The reply's `timings` (they feed `Conversation.contextUsage`); [predictedTokens] defaults to a guess. */
        public fun timings(promptTokens: Int, cachedTokens: Int = 0, predictedTokens: Int = -1) {
            timings = intArrayOf(promptTokens, cachedTokens, predictedTokens)
        }

        /** Ends the reply at the output limit (`FinishReason.Length`) instead of a stop. */
        public fun finishLength() { finishLength = true }
    }

    private sealed interface Scripted {
        class Stream(val reply: Reply) : Scripted
        class Error(val status: Int, val body: String) : Scripted
    }

    private inner class FakeEngine : Engine {
        override fun init(configJson: String): String? = null
        override fun setLog(listener: ((level: Int, text: String) -> Unit)?) {}
        override fun devices(): String =
            """[{"name":"CPU","description":"FakeLlamaBro","type":"cpu","memory_free":0,"memory_total":0}]"""
        override fun load(configJson: String, onProgress: (Float) -> Unit): EngineModel {
            onProgress(1f)
            return FakeModel(contextSize)
        }
    }

    private inner class FakeModel(private val nCtx: Int) : EngineModel {
        override val infoJson: String = """{"n_ctx":$nCtx,"n_ctx_train":$nCtx,"n_params":0,"size":0,"desc":"FakeLlamaBro",
            "metadata":{},"props":{"total_slots":1,"chat_template":"","modalities":{"vision":false,"video":false,"audio":false},
            "chat_template_caps":{"supports_tools":true,"supports_parallel_tool_calls":true,"supports_system_role":true}}}"""

        override fun request(endpoint: String, paramsJson: String?, body: ByteArray, sink: RequestSink): EngineRequest {
            val text = String(body, Charsets.UTF_8)
            fun send(status: Int, data: String) = sink.onChunk(status, data.toByteArray(Charsets.UTF_8))
            when (endpoint) {
                "chat" -> chat(text, ::send)
                "slots" -> send(200, """[{"id":0,"is_processing":false}]""")
                "control" -> send(200, "{}")
                else -> send(501, notSupported("FakeLlamaBro doesn't script the $endpoint route"))
            }
            sink.onDone()
            return Finished
        }

        private fun chat(body: String, send: (Int, String) -> Unit) {
            val json = runCatching { Json.parseToJsonElement(body) as? JsonObject }.getOrNull()
            if ((json?.get("n_predict") as? JsonPrimitive)?.intOrNull == 0) {
                send(200, """{"choices":[],"timings":{"cache_n":0,"prompt_n":10,"predicted_n":0}}""")
                return
            }
            if ((json?.get("stream") as? JsonPrimitive)?.booleanOrNull != true) {
                send(501, notSupported("FakeLlamaBro scripts streamed chat completions only"))
                return
            }
            val next = synchronized(lock) {
                chat += body
                queue.removeFirstOrNull() ?: return send(500, serverError("FakeLlamaBro: no reply scripted for chat request ${chat.size}"))
            }
            when (next) {
                is Scripted.Error -> send(next.status, next.body)
                is Scripted.Stream -> stream(next.reply).forEach { send(200, it) }
            }
        }

        override fun close() {}
    }

    private object Finished : EngineRequest {
        override fun cancel() {}
        override fun free() {}
    }

    private companion object {
        private var callIds = 0

        fun q(s: String): String = JsonPrimitive(s).toString()

        fun notSupported(message: String) = """{"error":{"code":501,"message":${q(message)},"type":"not_supported_error"}}"""

        fun serverError(message: String) = """{"error":{"code":500,"message":${q(message)},"type":"server_error"}}"""

        /** llama-server's chat stream (server-task.cpp `to_json_oaicompat_chat_stream`), in its order. */
        fun stream(r: Reply): List<String> {
            val id = "chatcmpl-fake"
            fun chunk(delta: String, finish: String? = null, extra: String = "") =
                "data: {\"choices\":[{\"finish_reason\":${finish?.let { "\"$it\"" } ?: "null"},\"index\":0,\"delta\":$delta}]," +
                    "\"id\":\"$id\",\"object\":\"chat.completion.chunk\"$extra}\n\n"
            val out = mutableListOf(chunk("{\"role\":\"assistant\",\"content\":null}"))
            r.reasoning.toString().chunked(4).forEach { out += chunk("{\"reasoning_content\":${q(it)}}") }
            r.content.toString().chunked(4).forEach { out += chunk("{\"content\":${q(it)}}") }
            r.toolCalls.forEachIndexed { i, (callId, name, args) ->
                val cid = callId ?: synchronized(this) { "call_${++callIds}" }
                out += chunk("{\"tool_calls\":[{\"index\":$i,\"id\":${q(cid)},\"type\":\"function\",\"function\":{\"name\":${q(name)},\"arguments\":\"\"}}]}")
                args.chunked(8).forEach { out += chunk("{\"tool_calls\":[{\"index\":$i,\"function\":{\"arguments\":${q(it)}}}]}") }
            }
            val (promptN, cacheN, predicted) = r.timings.let { Triple(it[0], it[1], it[2]) }
            val predictedN = if (predicted >= 0) predicted else maxOf(1, (r.reasoning.length + r.content.length) / 4)
            val finish = when {
                r.finishLength -> "length"
                r.toolCalls.isNotEmpty() -> "tool_calls"
                else -> "stop"
            }
            out += chunk(
                "{}", finish,
                ",\"timings\":{\"cache_n\":$cacheN,\"prompt_n\":$promptN,\"prompt_per_second\":100.0," +
                    "\"predicted_n\":$predictedN,\"predicted_per_second\":10.0}",
            )
            out += "data: [DONE]\n\n"
            return out
        }
    }
}
