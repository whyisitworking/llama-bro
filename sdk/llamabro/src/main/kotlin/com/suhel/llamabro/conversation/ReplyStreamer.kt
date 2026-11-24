package com.suhel.llamabro.conversation

import com.suhel.llamabro.Model
import com.suhel.llamabro.chat.ChatEvent
import com.suhel.llamabro.chat.ChatRequest
import com.suhel.llamabro.chat.Message
import com.suhel.llamabro.chat.ToolCall
import com.suhel.llamabro.chatInternal
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Streams one model request of a [Conversation] into its state: the reply as it arrives ([reply]), the finished
 * assistant message into the history ([append]), and the context in use from the reply's timings ([contextUsage]).
 * It owns how a reply is folded from its events, and what a stop or an error keeps of it, so the agent loop sees only
 * whole replies.
 */
internal class ReplyStreamer(
    private val model: Model,
    private val reply: MutableStateFlow<Message.Assistant?>,
    private val contextUsage: MutableStateFlow<ContextUsage>,
    /** Where the stream's completion id goes while it runs (`endReasoning`), and null once it ends. */
    private val completionId: (String?) -> Unit,
    private val append: (Message) -> Unit,
) {
    /** A finished reply: its closing event, not emitted yet, and the tool calls it made. */
    class Reply(val done: ChatEvent.Done, val toolCalls: List<ToolCall>)

    /**
     * One model request. Appends the reply to the history and returns its Done (not emitted). A reply that
     * stops early keeps what was streamed, as tools/ui stores/chat/index.svelte.ts savePartialResponseIfNeeded
     * does: content and reasoning stay, partial tool calls are dropped.
     */
    suspend fun stream(out: FlowCollector<ChatEvent>, req: ChatRequest): Reply {
        val draft = ReplyDraft()
        try {
            model.chatInternal(req) { completionId(it) }.collect { ev ->
                take(draft, ev)
                if (ev !is ChatEvent.Done && ev !is ChatEvent.ToolCallReceived) out.emit(ev)
            }
        } catch (e: CancellationException) {
            // A stop: tools/ui created the assistant message before streaming and never removes it, so the reply
            // stays, empty if nothing came (roles keep alternating); partial tool calls are dropped.
            append(draft.partial())
            reply.value = null // after the append, so a UI never loses the text in between
            throw e
        } catch (e: Throwable) {
            // An error (e.g. ContextExceeded): keep what was streamed, nothing when it's empty, so resume()
            // sends the same request again.
            if (draft.hasAnything()) append(draft.partial())
            reply.value = null
            throw e
        } finally {
            completionId(null)
        }
        append(draft.finished())
        reply.value = null
        return Reply(checkNotNull(draft.done), draft.calls)
    }

    /** Folds one event into the draft and the live state the UI reads. */
    private fun take(draft: ReplyDraft, ev: ChatEvent) {
        when (ev) {
            is ChatEvent.ContentDelta -> {
                draft.content.append(ev.delta)
                reply.value = draft.shown()
            }

            is ChatEvent.ReasoningDelta -> {
                draft.reasoning.append(ev.delta)
                reply.value = draft.shown()
            }

            is ChatEvent.ToolCallReceived -> {
                draft.calls += ev.call
            }

            is ChatEvent.Done -> {
                draft.done = ev
                ev.timings?.let { contextUsage.value = ContextUsage.of(it, model.info.contextSize) }
            }

            else -> {
                Unit
            }
        }
    }

    /** The assistant message as it arrives: text, reasoning and tool calls so far, and the closing event. */
    private class ReplyDraft {
        val content = StringBuilder()
        val reasoning = StringBuilder()
        val calls = mutableListOf<ToolCall>()
        var done: ChatEvent.Done? = null

        fun shown() = Message.Assistant(content.toString(), reasoning.toString().ifEmpty { null })

        fun hasAnything() = content.isNotBlank() || reasoning.isNotBlank() || calls.isNotEmpty()

        /** What a stop or an error keeps: the text and reasoning so far, no partial tool calls. */
        fun partial() =
            Message.Assistant(content = content.toString(), reasoning = reasoning.toString().ifBlank { null })

        // tools/ui stores/agentic/index.svelte.ts: `content: turnContent || undefined`,
        // `reasoning_content: … || undefined`
        fun finished() =
            if (calls.isEmpty()) {
                Message.Assistant(content = content.toString(), reasoning = reasoning.toString().ifEmpty { null })
            } else {
                Message.Assistant(
                    content = content.toString().ifEmpty { null },
                    reasoning = reasoning.toString().ifEmpty { null },
                    toolCalls = calls.toList(),
                )
            }
    }
}
