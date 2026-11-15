package com.suhel.llamabro.conversation

import com.suhel.llamabro.chat.Message
import com.suhel.llamabro.chat.ToolCall

/**
 * The last batch (D62): the last assistant message with tool calls, while only tool results follow it. Anything else
 * after it (a user message, a reply, a compaction) closes the batch, and the loop never returns to it. Derived from
 * the history alone, so a restored conversation finds the same batch.
 */
internal class ToolBatch private constructor(val calls: List<ToolCall>, private val answered: Set<String>) {
    /** The calls without a result yet, in order. */
    val unanswered: List<ToolCall> get() = calls.filter { it.id !in answered }

    /**
     * The call waiting for approval, with its index in [calls]: the first unanswered call that isn't manual, when its
     * tool needs approval. An unanswered automatic call before it means the batch was interrupted (an abort), not
     * that it waits. Manual calls are skipped over: the app can answer them in any order.
     */
    fun awaitingApproval(registry: ToolRegistry): IndexedValue<ToolCall>? =
        calls
            .withIndex()
            .firstOrNull { (_, call) ->
                call.id !in answered && registry.entry(call.name).let { it == null || it.handler != null }
            }?.takeIf { registry.entry(it.value.name)?.requiresApproval == true }

    companion object {
        fun of(history: List<Message>): ToolBatch? {
            val at = history.indexOfLast { it is Message.Assistant && it.toolCalls.isNotEmpty() }
            if (at < 0) return null
            val after = history.subList(at + 1, history.size)
            if (!after.all { it is Message.ToolResult }) return null
            val answered = after.mapTo(mutableSetOf()) { (it as Message.ToolResult).toolCallId }
            return ToolBatch((history[at] as Message.Assistant).toolCalls, answered)
        }
    }
}
