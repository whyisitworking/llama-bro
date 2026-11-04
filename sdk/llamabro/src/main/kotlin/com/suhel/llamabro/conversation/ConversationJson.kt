package com.suhel.llamabro.conversation

import com.suhel.llamabro.chat.Content
import com.suhel.llamabro.chat.Message
import com.suhel.llamabro.chat.ToolCall
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.Base64

/**
 * `Conversation.toJson()` format, version 1: `{"version":1,"system":…,"messages":[…]}`. Each message keeps
 * every field exactly, so a restored conversation sends byte-identical requests (and the server's prompt
 * cache still hits).
 */
internal object ConversationJson {
    const val VERSION = 1

    fun encode(system: String?, messages: List<Message>): String = buildJsonObject {
        put("version", VERSION)
        put("system", system?.let { JsonPrimitive(it) } ?: JsonNull)
        put("messages", JsonArray(messages.map { message(it) }))
    }.toString()

    /** @throws IllegalArgumentException for anything that isn't a version-1 saved conversation. */
    fun decode(text: String): Pair<String?, List<Message>> = try {
        val o = Json.parseToJsonElement(text) as JsonObject
        val version = (o["version"] as? JsonPrimitive)?.int
        require(version == VERSION) { "unsupported conversation version: $version" }
        val system = (o["system"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val messages = (o["messages"] as JsonArray).map { message(it as JsonObject) }
        system to messages
    } catch (e: IllegalArgumentException) {
        throw e
    } catch (e: Exception) {
        // malformed JSON, a missing field (NPE from !!) or a wrong type (ClassCastException)
        throw IllegalArgumentException("not a saved conversation (expected Conversation.toJson() output): ${e.message}", e)
    }

    private fun message(m: Message): JsonObject = buildJsonObject {
        when (m) {
            is Message.System -> { put("role", "system"); put("text", m.text) }
            is Message.User -> {
                put("role", "user")
                put("content", buildJsonArray {
                    m.content.forEach { c ->
                        add(when (c) {
                            is Content.Text -> buildJsonObject { put("type", "text"); put("text", c.text) }
                            is Content.Image -> buildJsonObject {
                                put("type", "image"); put("mime", c.mimeType); put("data", Base64.getEncoder().encodeToString(c.bytes))
                            }
                        })
                    }
                })
            }
            is Message.Assistant -> {
                put("role", "assistant")
                put("content", m.content?.let { JsonPrimitive(it) } ?: JsonNull)
                put("reasoning", m.reasoning?.let { JsonPrimitive(it) } ?: JsonNull)
                put("tool_calls", buildJsonArray {
                    m.toolCalls.forEach { add(buildJsonObject { put("id", it.id); put("name", it.name); put("arguments", it.argumentsJson) }) }
                })
            }
            is Message.ToolResult -> { put("role", "tool"); put("tool_call_id", m.toolCallId); put("content", m.content) }
            is Message.Compaction -> { put("role", "compaction"); put("summary", m.summary); put("kept", m.keptSteps) }
        }
    }

    private fun message(o: JsonObject): Message = when (val role = o.str("role")) {
        "system" -> Message.System(o.str("text")!!)
        "user" -> Message.User((o["content"] as JsonArray).map { p ->
            p as JsonObject
            when (p.str("type")) {
                "text" -> Content.Text(p.str("text")!!)
                "image" -> Content.Image(Base64.getDecoder().decode(p.str("data")!!), p.str("mime")!!)
                else -> throw IllegalArgumentException("unknown content type: ${p.str("type")}")
            }
        })
        "assistant" -> Message.Assistant(
            content = o.str("content"),
            reasoning = o.str("reasoning"),
            toolCalls = (o["tool_calls"] as? JsonArray).orEmpty().map {
                it as JsonObject
                ToolCall(it.str("id")!!, it.str("name")!!, it.str("arguments")!!)
            },
        )
        "tool" -> Message.ToolResult(o.str("tool_call_id")!!, o.str("content")!!)
        "compaction" -> Message.Compaction(o.str("summary")!!, o["kept"]?.jsonPrimitive?.int ?: 0)
        else -> throw IllegalArgumentException("unknown message role: $role")
    }

    private fun JsonObject.str(key: String): String? = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content
}
