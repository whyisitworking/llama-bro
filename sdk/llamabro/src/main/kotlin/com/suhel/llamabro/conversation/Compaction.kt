package com.suhel.llamabro.conversation

import androidx.annotation.CheckResult
import com.suhel.llamabro.chat.ChatEvent
import com.suhel.llamabro.chat.Message
import com.suhel.llamabro.chat.ToolCall
import kotlinx.coroutines.flow.Flow

/** Compaction constants, verbatim from llama.cpp b12052 tools/ui/src/lib/constants/compaction.constants.ts. */
public object Compaction {
    /** The instruction appended to the summary request. */
    public const val DEFAULT_PROMPT: String =
        "Summarize this conversation so it can seamlessly continue in a fresh context. Capture: the overall goal, key facts and decisions, the current state of any ongoing work (including code, file names, and data discussed), and what remains to be done. Write the summary as dense prose addressed to the assistant that will resume the conversation. Return ONLY the summary, nothing else."

    /** Steps a compaction keeps as written: a user message, or an assistant message with its tool results. */
    public const val DEFAULT_PRESERVED_STEPS: Int = 10

    internal const val SUMMARY_PLACEHOLDER: String = "{{SUMMARY}}"

    /** How the model sees a compaction node. */
    internal const val CONTEXT_TEMPLATE: String =
        "This conversation was compacted. Summary of the earlier " +
            "exchange:\n\n$SUMMARY_PLACEHOLDER"
}

/**
 * Summarises the history before the last [preservedSteps] steps, the way tools/ui does. The
 * summary request is the chat's own request cut before the preserved steps plus [instruction], so the server
 * reuses its cached prefix. On success a [Message.Compaction] is appended and the shortened history is
 * pre-encoded in the background (cancelled by the next send).
 *
 * @throws com.suhel.llamabro.LlamaBroException.CompactionTruncated if the summary hit the output limit; the
 *   history is unchanged.
 * @throws IllegalStateException if no step lies before the preserved ones (nothing to compact), or a tool call of
 *   the last reply still has no result (waiting for approval, or a manual tool): approve, deny or answer it first.
 */
@CheckResult
public fun Conversation.compact(
    preservedSteps: Int = Compaction.DEFAULT_PRESERVED_STEPS,
    instruction: String = Compaction.DEFAULT_PROMPT,
): Flow<ChatEvent> = compactInternal(preservedSteps, instruction)

/**
 * Whether [compact] with [preservedSteps] has anything to summarise (tools/ui `canCompactMessages`), e.g. to
 * enable a Compact button. A step is a user message, or an assistant message with its tool results. False while a
 * tool call of the last reply has no result, since a compaction would drop it (D62).
 */
public fun Conversation.canCompact(preservedSteps: Int = Compaction.DEFAULT_PRESERVED_STEPS): Boolean =
    CompactionPlanner.canCompact(messages.value, preservedSteps) && unansweredToolCalls(messages.value).isEmpty()

/**
 * The calls of the last reply still without a result. A compaction closes that batch (D62), so the call would never
 * be approved or answered and the model would later see it without a result; tools/ui never has such calls, since
 * its loop answers every call before the user can compact.
 */
internal fun unansweredToolCalls(history: List<Message>): List<ToolCall> = ToolBatch.of(history)?.unanswered.orEmpty()

/** Port of llama.cpp b12052 tools/ui/src/lib/utils/compaction.ts. */
internal object CompactionPlanner {
    /** tools/ui `isPrefix`: what every request keeps first. */
    private fun isPrefix(m: Message) = m is Message.System

    /** tools/ui `isBoundary`: a compaction node holding a summary (an empty one is an interrupted compaction). */
    private fun isBoundary(m: Message) = m is Message.Compaction && m.summary.isNotBlank()

    /** tools/ui `stepStarts`: a step is a user message, or an assistant message with the tool results after it. */
    private fun stepStarts(messages: List<Message>): List<Int> =
        messages.indices.filter { i ->
            val m = messages[i]
            !(isPrefix(m) || m is Message.Compaction || m is Message.ToolResult)
        }

    /** Each step as an index range (a step runs until the next step starts, or the end). */
    fun steps(messages: List<Message>): List<IntRange> {
        val starts = stepStarts(messages)
        return starts.mapIndexed { k, start ->
            var end = if (k + 1 < starts.size) starts[k + 1] - 1 else messages.size - 1
            while (end > start && (isPrefix(messages[end]) || messages[end] is Message.Compaction)) end--
            start..end
        }
    }

    /**
     * tools/ui `compactionCut`: where the summary request cuts the model view; null = nothing before the preserved
     * steps.
     */
    fun cutIndex(messages: List<Message>, preservedSteps: Int): Int? {
        val starts = stepStarts(messages)
        val folded = starts.size - preservedSteps
        if (folded < 1) return null
        return if (preservedSteps > 0) starts[folded] else messages.size
    }

    /** tools/ui `sliceAtLastCompaction`: system prefix, framed summary, the kept steps, everything after the node. */
    fun modelView(messages: List<Message>): List<Message> {
        val boundary = messages.indexOfLast { isBoundary(it) }
        if (boundary == -1) return messages
        val before = messages.subList(0, boundary)
        val node = messages[boundary] as Message.Compaction
        // String.replace is literal, like tools/ui's replacement function: `$&` in a summary stays as written.
        // The framed copy stays a compaction node (as in tools/ui, where it keeps its type), so it is no step;
        // [forRequest] turns it into the user message the model reads.
        val framed =
            Message.Compaction(
                Compaction.CONTEXT_TEMPLATE.replace(Compaction.SUMMARY_PLACEHOLDER, node.summary),
                node.keptSteps,
            )
        val steps = before.filter { !isPrefix(it) && it !is Message.Compaction }
        val starts = stepStarts(steps)
        val kept = minOf(node.keptSteps, starts.size)
        val from = if (kept > 0) starts[starts.size - kept] else steps.size
        return before.filter { isPrefix(it) } + framed + steps.subList(from, steps.size) +
            messages.subList(boundary + 1, messages.size)
    }

    /** A model view as request messages: the framed compaction node is sent as a user message (its tools/ui role). */
    fun forRequest(view: List<Message>): List<Message> =
        view.map { if (it is Message.Compaction) Message.user(it.summary) else it }

    /** tools/ui `canCompactMessages`. */
    fun canCompact(messages: List<Message>, preservedSteps: Int): Boolean =
        cutIndex(modelView(messages), preservedSteps) != null
}
