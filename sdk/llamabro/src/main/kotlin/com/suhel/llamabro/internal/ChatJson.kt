package com.suhel.llamabro.internal

import com.suhel.llamabro.chat.ChatRequest
import com.suhel.llamabro.chat.Content
import com.suhel.llamabro.chat.Message
import com.suhel.llamabro.chat.ReasoningFormat
import com.suhel.llamabro.chat.RequestOptions
import com.suhel.llamabro.chat.ResponseFormat
import com.suhel.llamabro.chat.Sampling
import com.suhel.llamabro.chat.Tool
import com.suhel.llamabro.chat.ToolChoice
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.util.Base64

/**
 * [ChatRequest] → the `/v1/chat/completions` body. Field names are llama-server's
 * (tools/server/server-schema.cpp; messages and tools follow the OpenAI shape llama-server parses in
 * server-common.cpp oaicompat_chat_params_parse). Null fields are left out.
 */
internal object ChatJson {
    fun body(r: ChatRequest): String {
        val base =
            buildJsonObject {
                put("messages", messages(r.messages))
                if (r.tools.isNotEmpty()) put("tools", JsonArray(r.tools.map { tool(it) }))
                options(r.options).forEach { (k, v) -> put(k, v) }
                put("stream", true)
                put("return_progress", true)
                putJsonObject("stream_options") { put("include_usage", true) }
            }
        val extra = r.extra ?: return base.toString()
        return JsonMergePatch.apply(base, JsonMergePatch.checkPatch(extra, RequestOptions.OWNED)).toString()
    }

    /** The body fields of [o], in P1's order; `extra` is not among them (it is applied to the whole body). */
    fun options(o: RequestOptions): JsonObject =
        buildJsonObject {
            o.toolChoice?.let { put("tool_choice", toolChoice(it)) }
            o.parallelToolCalls?.let { put("parallel_tool_calls", it) }
            o.responseFormat?.let { put("response_format", responseFormat(it)) }
            if (o.stop.isNotEmpty()) putJsonArray("stop") { o.stop.forEach { add(it) } }

            for ((wire, value) in o.sampling.wireValues()) put(wire, samplingValue(value))

            o.reasoning.format?.let { put("reasoning_format", it.wire) }
            o.reasoning.budgetTokens?.let { put("reasoning_budget_tokens", it) }
            o.reasoning.budgetMessage?.let { put("reasoning_budget_message", it) }
            o.reasoning.enableThinking?.let { putJsonObject("chat_template_kwargs") { put("enable_thinking", it) } }
        }

    /** The inverse of [options], for saved conversations (ConversationJson v2). */
    fun parseOptions(o: JsonObject, extra: String?): RequestOptions {
        val sampling =
            Sampling.of(
                Sampling.FIELDS
                    .mapNotNull { f ->
                        o[f.wire]?.let {
                            f.wire to
                                samplingValue(f.kind, it)
                        }
                    }.toMap(),
            )
        val kwargs = o["chat_template_kwargs"] as? JsonObject
        return RequestOptions {
            toolChoice = o["tool_choice"]?.let { parseToolChoice(it) }
            parallelToolCalls = o["parallel_tool_calls"]?.jsonPrimitive?.boolean
            responseFormat = (o["response_format"] as? JsonObject)?.let { parseResponseFormat(it) }
            stop = (o["stop"] as? JsonArray).orEmpty().map { it.jsonPrimitive.content }
            this.sampling = sampling
            reasoning {
                format =
                    o["reasoning_format"]?.jsonPrimitive?.content?.let { w ->
                        ReasoningFormat.entries.first {
                            it.wire ==
                                w
                        }
                    }
                budgetTokens = o["reasoning_budget_tokens"]?.jsonPrimitive?.int
                budgetMessage = o["reasoning_budget_message"]?.jsonPrimitive?.content
                enableThinking = kwargs?.get("enable_thinking")?.jsonPrimitive?.boolean
            }
            this.extra = extra
        }
    }

    private fun samplingValue(kind: Sampling.Kind, e: JsonElement): Any =
        when (kind) {
            Sampling.Kind.Float -> {
                e.jsonPrimitive.float
            }

            Sampling.Kind.Int -> {
                e.jsonPrimitive.int
            }

            Sampling.Kind.Long -> {
                e.jsonPrimitive.long
            }

            Sampling.Kind.Bool -> {
                e.jsonPrimitive.boolean
            }

            Sampling.Kind.Strings -> {
                e.jsonArray.map { it.jsonPrimitive.content }
            }

            Sampling.Kind.Bias -> {
                e.jsonObject.mapValues { (_, v) ->
                    v.jsonPrimitive.booleanOrNull?.let { Float.NEGATIVE_INFINITY } ?: v.jsonPrimitive.float
                }
            }
        }

    private fun parseToolChoice(e: JsonElement): ToolChoice =
        when {
            e is JsonPrimitive && e.content == "auto" -> {
                ToolChoice.Auto
            }

            e is JsonPrimitive && e.content == "none" -> {
                ToolChoice.None
            }

            e is JsonPrimitive && e.content == "required" -> {
                ToolChoice.Required
            }

            else -> {
                ToolChoice.Function(
                    e.jsonObject
                        .getValue("function")
                        .jsonObject
                        .getValue("name")
                        .jsonPrimitive.content,
                )
            }
        }

    private fun parseResponseFormat(o: JsonObject): ResponseFormat =
        when (o["type"]?.jsonPrimitive?.content) {
            "json_object" -> {
                ResponseFormat.JsonObject
            }

            else -> {
                val s = o.getValue("json_schema").jsonObject
                ResponseFormat.JsonSchema(s.getValue("schema").toString(), (s["name"] as? JsonPrimitive)?.content)
            }
        }

    /** A [com.suhel.llamabro.chat.Sampling] value as llama-server reads it. */
    private fun samplingValue(v: Any): JsonElement =
        when (v) {
            is Boolean -> {
                JsonPrimitive(v)
            }

            is Number -> {
                JsonPrimitive(v)
            }

            is String -> {
                JsonPrimitive(v)
            }

            is List<*> -> {
                JsonArray(v.map { JsonPrimitive(it as String) })
            }

            // logit_bias: -infinity is a ban, which llama-server reads as `false` (server-schema.cpp parse_bias)
            is Map<*, *> -> {
                JsonObject(
                    v.entries.associate { (k, b) ->
                        k as String to
                            if (b == Float.NEGATIVE_INFINITY) JsonPrimitive(false) else JsonPrimitive(b as Float)
                    },
                )
            }

            else -> {
                error("unexpected sampling value ${v::class}")
            }
        }

    fun messages(list: List<Message>): JsonArray = JsonArray(list.map { message(it) })

    fun message(m: Message): JsonObject =
        when (m) {
            is Message.System -> {
                buildJsonObject {
                    put("role", "system")
                    put("content", m.text)
                }
            }

            is Message.User -> {
                buildJsonObject {
                    put("role", "user")
                    val only = m.content.singleOrNull()
                    if (only is Content.Text) {
                        put("content", only.text)
                    } else {
                        put("content", JsonArray(m.content.map { part(it) }))
                    }
                }
            }

            is Message.Assistant -> {
                buildJsonObject {
                    put("role", "assistant")
                    // null content is left out, as tools/ui's agent loop does (`content: turnContent || undefined`,
                    // stores/agentic/index.svelte.ts); llama-server accepts an assistant message with only tool_calls
                    m.content?.let { put("content", it) }
                    m.reasoning?.let { put("reasoning_content", it) }
                    if (m.toolCalls.isNotEmpty()) {
                        put(
                            "tool_calls",
                            buildJsonArray {
                                m.toolCalls.forEach { c ->
                                    add(
                                        buildJsonObject {
                                            put("id", c.id)
                                            put("type", "function")
                                            putJsonObject("function") {
                                                put("name", c.name)
                                                put("arguments", c.argumentsJson)
                                            }
                                        },
                                    )
                                }
                            },
                        )
                    }
                }
            }

            is Message.ToolResult -> {
                buildJsonObject {
                    put("role", "tool")
                    put("tool_call_id", m.toolCallId)
                    put("content", m.content)
                }
            }

            is Message.Compaction -> {
                throw IllegalArgumentException("a Compaction message is never sent; send the conversation's model view")
            }
        }

    private fun part(c: Content): JsonObject =
        when (c) {
            is Content.Text -> {
                buildJsonObject {
                    put("type", "text")
                    put("text", c.text)
                }
            }

            is Content.Image -> {
                buildJsonObject {
                    put("type", "image_url")
                    putJsonObject("image_url") {
                        put("url", "data:${c.mimeType};base64," + Base64.getEncoder().encodeToString(c.bytes))
                    }
                }
            }
        }

    fun tools(list: List<Tool>): JsonArray = JsonArray(list.map { tool(it) })

    private fun tool(t: Tool): JsonObject =
        buildJsonObject {
            put("type", "function")
            putJsonObject("function") {
                put("name", t.name)
                put("description", t.description)
                put("parameters", parseObject(t.parametersSchema, "parametersSchema"))
            }
        }

    private fun toolChoice(c: ToolChoice): JsonElement =
        when (c) {
            ToolChoice.Auto -> {
                JsonPrimitive("auto")
            }

            ToolChoice.None -> {
                JsonPrimitive("none")
            }

            ToolChoice.Required -> {
                JsonPrimitive("required")
            }

            is ToolChoice.Function -> {
                buildJsonObject {
                    put("type", "function")
                    putJsonObject("function") { put("name", c.name) }
                }
            }
        }

    private fun responseFormat(f: ResponseFormat): JsonObject =
        when (f) {
            ResponseFormat.JsonObject -> {
                buildJsonObject { put("type", "json_object") }
            }

            is ResponseFormat.JsonSchema -> {
                buildJsonObject {
                    put("type", "json_schema")
                    putJsonObject("json_schema") {
                        f.name?.let { put("name", it) }
                        put("schema", parseObject(f.schema, "schema"))
                    }
                }
            }
        }

    private fun parseObject(s: String, what: String): JsonObject =
        runCatching {
            Json.parseToJsonElement(s) as JsonObject
        }.getOrElse { throw IllegalArgumentException("$what must be a JSON object") }
}
