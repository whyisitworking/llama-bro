package com.suhel.llamabro.internal

import com.suhel.llamabro.chat.ChatEvent
import com.suhel.llamabro.chat.FinishReason
import com.suhel.llamabro.chat.Timings
import com.suhel.llamabro.chat.ToolCall
import com.suhel.llamabro.chat.Usage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Turns `chat.completion.chunk` payloads into [ChatEvent]s. Content and reasoning are emitted per delta;
 * tool calls are accumulated by `index` (OpenAI streaming shape, as compare.py's rebuild_chat does) and
 * emitted by [finish] in index order just before [ChatEvent.Done].
 */
internal class ChatStreamDecoder {
    /** The chunk `id`; `Conversation.endReasoning` sends it with `reasoning_end`. */
    @Volatile
    var completionId: String? = null
        private set

    private class PendingCall(var id: String = "", val name: StringBuilder = StringBuilder(), val args: StringBuilder = StringBuilder())

    private val calls = sortedMapOf<Int, PendingCall>()
    private var finishReason: String? = null
    private var timings: Timings? = null
    private var usage: Usage? = null

    fun accept(payload: String): List<ChatEvent> {
        val o = Json.parseToJsonElement(payload) as? JsonObject ?: return emptyList()
        o.str("id")?.let { completionId = it }
        val out = mutableListOf<ChatEvent>()

        (o["prompt_progress"] as? JsonObject)?.let { p ->
            out += ChatEvent.PromptProgress(
                processed = p.int("processed") ?: 0,
                total = p.int("total") ?: 0,
                cached = p.int("cache") ?: 0,
                timeMs = (p["time_ms"] as? JsonPrimitive)?.longOrNull ?: 0,
            )
        }
        (o["choices"] as? JsonArray)?.forEach { c ->
            val choice = c as? JsonObject ?: return@forEach
            val delta = choice["delta"] as? JsonObject
            if (delta != null) {
                delta.str("reasoning_content")?.takeIf { it.isNotEmpty() }?.let { out += ChatEvent.ReasoningDelta(it) }
                delta.str("content")?.takeIf { it.isNotEmpty() }?.let { out += ChatEvent.ContentDelta(it) }
                (delta["tool_calls"] as? JsonArray)?.forEach { t ->
                    val tc = t as? JsonObject ?: return@forEach
                    val call = calls.getOrPut(tc.int("index") ?: 0) { PendingCall() }
                    tc.str("id")?.let { if (it.isNotEmpty()) call.id = it }
                    (tc["function"] as? JsonObject)?.let { fn ->
                        fn.str("name")?.let { call.name.append(it) }
                        fn.str("arguments")?.let { call.args.append(it) }
                    }
                }
            }
            choice.str("finish_reason")?.let { finishReason = it }
        }
        (o["timings"] as? JsonObject)?.let { t ->
            timings = Timings(
                promptTokens = t.int("prompt_n") ?: 0,
                cachedTokens = t.int("cache_n") ?: 0,
                promptPerSecond = (t["prompt_per_second"] as? JsonPrimitive)?.doubleOrNull ?: 0.0,
                predictedTokens = t.int("predicted_n") ?: 0,
                predictedPerSecond = (t["predicted_per_second"] as? JsonPrimitive)?.doubleOrNull ?: 0.0,
                draftTokens = t.int("draft_n"),
                draftAccepted = t.int("draft_n_accepted"),
            )
        }
        (o["usage"] as? JsonObject)?.let { u ->
            usage = Usage(promptTokens = u.int("prompt_tokens") ?: 0, completionTokens = u.int("completion_tokens") ?: 0)
        }
        return out
    }

    /** The tool calls, then [ChatEvent.Done]. Call once, after the stream ends. */
    fun finish(): List<ChatEvent> {
        val out = mutableListOf<ChatEvent>()
        calls.values.forEach { out += ChatEvent.ToolCallReceived(ToolCall(it.id, it.name.toString(), it.args.toString())) }
        val reason = when (finishReason) {
            "length" -> FinishReason.Length
            "tool_calls" -> FinishReason.ToolCalls
            else -> if (calls.isNotEmpty()) FinishReason.ToolCalls else FinishReason.Stop
        }
        out += ChatEvent.Done(reason, timings, usage)
        return out
    }

    private fun JsonObject.str(key: String): String? = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun JsonObject.int(key: String): Int? = (get(key) as? JsonPrimitive)?.intOrNull
}
