package com.suhel.llamabro.conversation

import com.suhel.llamabro.Endpoint
import com.suhel.llamabro.Model
import com.suhel.llamabro.chat.Message
import com.suhel.llamabro.internal.Attachments
import com.suhel.llamabro.internal.ChatJson
import com.suhel.llamabro.internal.ChatStreamDecoder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * A [Conversation]'s background pre-encode (tools/ui triggerPreEncode / triggerCompactionPreEncode): after a
 * compaction, or a reply with `preEncodeAfterReply`, it sends the history the next turn will send so the server
 * encodes it ahead of that turn. It builds the request and reads its timings into [contextUsage]; the model's
 * [com.suhel.llamabro.internal.PreEncodeSlot] runs it, one per model, so any chat request cancels it.
 */
internal class PreEncoder(
    private val model: Model,
    private val registry: ToolRegistry,
    private val contextUsage: MutableStateFlow<ContextUsage>,
) {
    /**
     * When every slot is idle, re-submits [messages] with `n_predict: 0` (chat.service.ts preEncode: messages, tools,
     * no sampling) so the server encodes the prompt ahead of the next turn. The timings update [contextUsage].
     */
    fun start(messages: List<Message>) {
        val media = Attachments()
        val body =
            buildJsonObject {
                put("messages", ChatJson.messages(messages, media))
                put("n_predict", 0)
                put("stream", false)
                if (registry.tools.isNotEmpty()) put("tools", ChatJson.tools(registry.tools))
            }.toString()
        model.preEncodeSlot.start {
            try {
                if (!allSlotsIdle()) return@start
                model.request(Endpoint.ChatCompletions, body, media.all).collect { response ->
                    val t = runCatching { (Json.parseToJsonElement(response) as JsonObject)["timings"] as? JsonObject }
                    t.getOrNull()?.let {
                        contextUsage.value = ContextUsage.of(ChatStreamDecoder.timingsOf(it), model.info.contextSize)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // best effort, like tools/ui: a failed pre-encode only costs the next turn its prefill
            }
        }
    }

    /** tools/ui ChatService.areAllSlotsIdle: GET /slots, no slot `is_processing`. */
    private suspend fun allSlotsIdle(): Boolean {
        var idle = true
        model.rawRequest(Endpoint.Slots, "").collect { body ->
            val slots = runCatching { Json.parseToJsonElement(body) as? JsonArray }.getOrNull() ?: return@collect
            idle = slots.none { ((it as? JsonObject)?.get("is_processing") as? JsonPrimitive)?.booleanOrNull == true }
        }
        return idle
    }
}
