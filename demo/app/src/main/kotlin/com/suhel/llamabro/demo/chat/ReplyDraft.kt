package com.suhel.llamabro.demo.chat

import com.suhel.llamabro.chat.ChatEvent
import com.suhel.llamabro.demo.data.MessageChange
import com.suhel.llamabro.demo.data.MessageState
import com.suhel.llamabro.demo.models.Fit

/**
 * The reply being written: its text and its reasoning so far, how long the model thought, and whether anything
 * arrived since the last frame. [ChatEngine] feeds it the model's events and reads the stored and the shown form back.
 */
internal class ReplyDraft(private val clock: () -> Long) {
    private val text = StringBuilder()
    private val reasoning = StringBuilder()
    private var thinkStart: Long? = null
    private var thinkMillis: Long? = null
    private var wordsPerSecond = 0.0

    /** True when something arrived since the last [takeIfDirty]. */
    @Volatile
    private var dirty = false

    /** Whether the model is still thinking: reasoning has started and the answer hasn't. */
    val thinking: Boolean get() = thinkStart != null && thinkMillis == null

    fun accept(event: ChatEvent) {
        when (event) {
            is ChatEvent.ReasoningDelta -> {
                if (thinkStart == null) thinkStart = clock()
                reasoning.append(event.delta)
                dirty = true
            }

            is ChatEvent.ContentDelta -> {
                endThinking()
                text.append(event.delta)
                dirty = true
            }

            is ChatEvent.Done -> {
                wordsPerSecond = (event.timings?.predictedPerSecond ?: 0.0) * Fit.WORDS_PER_TOKEN
            }

            else -> {
                Unit
            }
        }
    }

    /** True once, when something arrived since the last call: the frame that shows it should be drawn now. */
    fun takeIfDirty(): Boolean = dirty.also { dirty = false }

    /** What the screen shows while the reply streams. */
    fun shown(id: Long) = StreamingReply(id, text.toString(), reasoningOrNull(), thinkMillis, thinking)

    /** The periodic write of what has arrived: text and reasoning only. */
    fun progress() = MessageChange(text = text.toString(), reasoning = reasoningOrNull())

    /** The reply as it ended: finished ([MessageState.Done], with its speed) or stopped by the user. */
    fun ended(state: MessageState): MessageChange {
        if (state == MessageState.Done) endThinking()
        return MessageChange(
            text = text.toString(),
            reasoning = reasoningOrNull(),
            reasoningMillis = thinkMillis,
            wordsPerSecond = wordsPerSecond.takeIf { state == MessageState.Done && it > 0 },
            state = state,
        )
    }

    /** What had arrived when the model failed: the text and the plain line, no reasoning. */
    fun failed(line: String) = MessageChange(text = text.toString(), state = MessageState.Failed, failure = line)

    private fun endThinking() {
        val start = thinkStart
        if (start != null && thinkMillis == null) thinkMillis = clock() - start
    }

    private fun reasoningOrNull() = reasoning.takeIf { it.isNotEmpty() }?.toString()
}
