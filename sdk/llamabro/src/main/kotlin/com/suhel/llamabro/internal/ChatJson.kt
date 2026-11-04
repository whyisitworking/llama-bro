package com.suhel.llamabro.internal

import com.suhel.llamabro.chat.ChatRequest
import com.suhel.llamabro.chat.Content
import com.suhel.llamabro.chat.Message
import com.suhel.llamabro.chat.ResponseFormat
import com.suhel.llamabro.chat.Tool
import com.suhel.llamabro.chat.ToolChoice
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
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
        val base = buildJsonObject {
            put("messages", messages(r.messages))
            if (r.tools.isNotEmpty()) put("tools", JsonArray(r.tools.map { tool(it) }))
            r.toolChoice?.let { put("tool_choice", toolChoice(it)) }
            r.parallelToolCalls?.let { put("parallel_tool_calls", it) }
            r.responseFormat?.let { put("response_format", responseFormat(it)) }
            if (r.stop.isNotEmpty()) putJsonArray("stop") { r.stop.forEach { add(it) } }

            val s = r.sampling
            s.temperature?.let { put("temperature", it) }
            s.topK?.let { put("top_k", it) }
            s.topP?.let { put("top_p", it) }
            s.minP?.let { put("min_p", it) }
            s.presencePenalty?.let { put("presence_penalty", it) }
            s.frequencyPenalty?.let { put("frequency_penalty", it) }
            s.repeatPenalty?.let { put("repeat_penalty", it) }
            s.repeatLastN?.let { put("repeat_last_n", it) }
            s.seed?.let { put("seed", it) }
            s.samplers?.let { list -> putJsonArray("samplers") { list.forEach { add(it) } } }
            s.maxTokens?.let { put("max_tokens", it) }

            r.reasoning.format?.let { put("reasoning_format", it.wire) }
            r.reasoning.budgetTokens?.let { put("reasoning_budget_tokens", it) }
            r.reasoning.enableThinking?.let { putJsonObject("chat_template_kwargs") { put("enable_thinking", it) } }

            put("stream", true)
            put("return_progress", true)
            putJsonObject("stream_options") { put("include_usage", true) }
        }
        val extra = r.extraJson?.let { Json.parseToJsonElement(it) as? JsonObject
            ?: throw IllegalArgumentException("extraJson must be a JSON object") } ?: return base.toString()
        return JsonObject(base + extra).toString()
    }

    fun messages(list: List<Message>): JsonArray = JsonArray(list.map { message(it) })

    fun message(m: Message): JsonObject = when (m) {
        is Message.System -> buildJsonObject { put("role", "system"); put("content", m.text) }
        is Message.User -> buildJsonObject {
            put("role", "user")
            val only = m.content.singleOrNull()
            if (only is Content.Text) {
                put("content", only.text)
            } else {
                put("content", JsonArray(m.content.map { part(it) }))
            }
        }
        is Message.Assistant -> buildJsonObject {
            put("role", "assistant")
            // null content is left out, as tools/ui's agent loop does (`content: turnContent || undefined`,
            // stores/agentic/index.svelte.ts); llama-server accepts an assistant message with only tool_calls
            m.content?.let { put("content", it) }
            m.reasoning?.let { put("reasoning_content", it) }
            if (m.toolCalls.isNotEmpty()) {
                put("tool_calls", buildJsonArray {
                    m.toolCalls.forEach { c ->
                        add(buildJsonObject {
                            put("id", c.id)
                            put("type", "function")
                            putJsonObject("function") { put("name", c.name); put("arguments", c.argumentsJson) }
                        })
                    }
                })
            }
        }
        is Message.ToolResult -> buildJsonObject {
            put("role", "tool")
            put("tool_call_id", m.toolCallId)
            put("content", m.content)
        }
        is Message.Compaction -> throw IllegalArgumentException("a Compaction message is never sent; send the conversation's model view")
    }

    private fun part(c: Content): JsonObject = when (c) {
        is Content.Text -> buildJsonObject { put("type", "text"); put("text", c.text) }
        is Content.Image -> buildJsonObject {
            put("type", "image_url")
            putJsonObject("image_url") {
                put("url", "data:${c.mimeType};base64," + Base64.getEncoder().encodeToString(c.bytes))
            }
        }
    }

    fun tools(list: List<Tool>): JsonArray = JsonArray(list.map { tool(it) })

    private fun tool(t: Tool): JsonObject = buildJsonObject {
        put("type", "function")
        putJsonObject("function") {
            put("name", t.name)
            put("description", t.description)
            put("parameters", parseObject(t.parametersSchema, "parametersSchema"))
        }
    }

    private fun toolChoice(c: ToolChoice): JsonElement = when (c) {
        ToolChoice.Auto -> JsonPrimitive("auto")
        ToolChoice.None -> JsonPrimitive("none")
        ToolChoice.Required -> JsonPrimitive("required")
        is ToolChoice.Function -> buildJsonObject { put("type", "function"); putJsonObject("function") { put("name", c.name) } }
    }

    private fun responseFormat(f: ResponseFormat): JsonObject = when (f) {
        ResponseFormat.JsonObject -> buildJsonObject { put("type", "json_object") }
        is ResponseFormat.JsonSchema -> buildJsonObject {
            put("type", "json_schema")
            putJsonObject("json_schema") {
                f.name?.let { put("name", it) }
                put("schema", parseObject(f.schema, "schema"))
            }
        }
    }

    private fun parseObject(s: String, what: String): JsonObject =
        runCatching { Json.parseToJsonElement(s) as JsonObject }.getOrElse { throw IllegalArgumentException("$what must be a JSON object") }
}
