package com.suhel.llamabro.conversation

import com.suhel.llamabro.MediaSource
import com.suhel.llamabro.chat.Content
import com.suhel.llamabro.chat.Message
import com.suhel.llamabro.chat.ToolCall
import com.suhel.llamabro.internal.ChatJson
import com.suhel.llamabro.internal.Media
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.io.encoding.Base64

/**
 * `Conversation.toJson()` format: `{"version":3,"system":…,"settings":{…},"messages":[…]}`, where settings holds the
 * defaults as body fields (ChatJson.options), `extra` as written, `max_turns` and `pre_encode_after_reply`. Only this
 * version is read; the earlier two never left development.
 * Each message keeps every field exactly, so a restored conversation sends byte-identical requests (and the server's
 * prompt cache still hits).
 */
internal object ConversationJson {
    const val VERSION = 3

    class Saved(val system: String?, val messages: List<Message>, val settings: (Conversation.Builder.() -> Unit))

    /** [read] gives a descriptor's bytes, for one still in a `history` no request has sent yet. */
    fun encode(
        system: String?,
        messages: List<Message>,
        config: Conversation.Builder,
        read: (MediaSource.Descriptor) -> ByteArray,
    ): String =
        buildJsonObject {
            put("version", VERSION)
            put("system", system?.let { JsonPrimitive(it) } ?: JsonNull)
            put(
                "settings",
                buildJsonObject {
                    put("options", ChatJson.options(config.defaults))
                    put("extra", config.defaults.extra?.let { JsonPrimitive(it) } ?: JsonNull)
                    put("max_turns", config.maxTurns)
                    put("pre_encode_after_reply", config.preEncodeAfterReply)
                },
            )
            put("messages", JsonArray(messages.map { message(it, read) }))
        }.toString()

    /** @throws IllegalArgumentException for anything that isn't a version-3 saved conversation. */
    fun decode(text: String): Saved =
        try {
            val o = Json.parseToJsonElement(text) as JsonObject
            val version = (o["version"] as? JsonPrimitive)?.int
            require(version == VERSION) { "unsupported conversation version: $version" }
            val system = (o["system"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            val messages = (o["messages"] as JsonArray).map { message(it as JsonObject) }
            val settings = requireNotNull(o["settings"] as? JsonObject) { "a saved conversation without settings" }
            val defaults = ChatJson.parseOptions(settings["options"] as JsonObject, settings.str("extra"))
            val maxTurns = settings.getValue("max_turns").jsonPrimitive.int
            val preEncode = settings.getValue("pre_encode_after_reply").jsonPrimitive.boolean
            val apply: Conversation.Builder.() -> Unit = {
                this.defaults = defaults
                this.maxTurns = maxTurns
                this.preEncodeAfterReply = preEncode
            }
            Saved(system, messages, apply)
        } catch (e: IllegalArgumentException) {
            throw e
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            // malformed JSON, a missing field (NPE from !!) or a wrong type (ClassCastException)
            throw IllegalArgumentException(
                "not a saved conversation (expected Conversation.toJson() output): ${e.message}",
                e,
            )
        }

    private fun message(m: Message, read: (MediaSource.Descriptor) -> ByteArray): JsonObject =
        buildJsonObject {
            when (m) {
                is Message.System -> {
                    put("role", "system")
                    put("text", m.text)
                }

                is Message.User -> {
                    put("role", "user")
                    put("content", buildJsonArray { m.content.forEach { add(contentJson(it, read)) } })
                }

                is Message.Assistant -> {
                    put("role", "assistant")
                    put("content", m.content?.let { JsonPrimitive(it) } ?: JsonNull)
                    put("reasoning", m.reasoning?.let { JsonPrimitive(it) } ?: JsonNull)
                    put("tool_calls", buildJsonArray { m.toolCalls.forEach { add(toolCallJson(it)) } })
                }

                is Message.ToolResult -> {
                    put("role", "tool")
                    put("tool_call_id", m.toolCallId)
                    put("content", m.content)
                }

                is Message.Compaction -> {
                    put("role", "compaction")
                    put("summary", m.summary)
                    put("kept", m.keptSteps)
                }
            }
        }

    /**
     * A part as saved: media as `{"type":"image"|"audio","mime":…}` with the file's `path` (the file must still be
     * there when the chat is restored) or its bytes as base64 `data`, the saved chat's own format (not a request).
     */
    private fun contentJson(c: Content, read: (MediaSource.Descriptor) -> ByteArray): JsonObject =
        buildJsonObject {
            when (c) {
                is Content.Text -> {
                    put("type", "text")
                    put("text", c.text)
                }

                is Content.Image -> {
                    put("type", "image")
                    media(c.mimeType, c.source, read)
                }

                is Content.Audio -> {
                    put("type", "audio")
                    // saved as its MIME type, as before AudioFormat, so older and newer saves read alike
                    media(c.format.mimeType, c.source, read)
                }
            }
        }

    private fun JsonObjectBuilder.media(
        mime: String,
        source: MediaSource,
        read: (MediaSource.Descriptor) -> ByteArray,
    ) {
        put("mime", mime)
        when (source) {
            is MediaSource.File -> put("path", source.path)
            is MediaSource.Bytes -> put("data", Base64.encode(source.bytes))
            is MediaSource.Descriptor -> put("data", Base64.encode(read(source)))
        }
    }

    private fun mediaSource(p: JsonObject): MediaSource =
        p.str("path")?.let { MediaSource.file(it) } ?: MediaSource.bytes(Base64.decode(p.need("data")))

    private fun toolCallJson(call: ToolCall): JsonObject =
        buildJsonObject {
            put("id", call.id)
            put("name", call.name)
            put("arguments", call.argumentsJson)
        }

    private fun message(o: JsonObject): Message =
        when (val role = o.str("role")) {
            "system" -> {
                Message.System(o.need("text"))
            }

            "user" -> {
                Message.User(
                    (o["content"] as JsonArray).map { p ->
                        p as JsonObject
                        when (p.str("type")) {
                            "text" -> Content.Text(p.need("text"))
                            "image" -> Content.Image(mediaSource(p), p.need("mime"))
                            "audio" -> Content.Audio(mediaSource(p), Media.audioFormatOf(p.need("mime")))
                            else -> throw IllegalArgumentException("unknown content type: ${p.str("type")}")
                        }
                    },
                )
            }

            "assistant" -> {
                Message.Assistant(
                    content = o.str("content"),
                    reasoning = o.str("reasoning"),
                    toolCalls =
                        (o["tool_calls"] as? JsonArray).orEmpty().map {
                            it as JsonObject
                            ToolCall(it.need("id"), it.need("name"), it.need("arguments"))
                        },
                )
            }

            "tool" -> {
                Message.ToolResult(o.need("tool_call_id"), o.need("content"))
            }

            "compaction" -> {
                Message.Compaction(o.need("summary"), o["kept"]?.jsonPrimitive?.int ?: 0)
            }

            else -> {
                throw IllegalArgumentException("unknown message role: $role")
            }
        }

    private fun JsonObject.str(key: String): String? = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.need(key: String): String = requireNotNull(str(key)) { "a saved message without \"$key\"" }
}
