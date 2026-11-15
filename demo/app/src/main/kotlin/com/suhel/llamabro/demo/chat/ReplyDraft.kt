package com.suhel.llamabro.demo.chat

import com.suhel.llamabro.chat.ChatEvent
import com.suhel.llamabro.chat.FinishReason
import com.suhel.llamabro.demo.data.MessageChange
import com.suhel.llamabro.demo.data.MessageState
import com.suhel.llamabro.demo.data.db.ReplyStats
import com.suhel.llamabro.demo.models.Fit

/**
 * The reply being written: its text and its reasoning so far, how long the model thought, and whether anything
 * arrived since the last frame. [ChatEngine] feeds it the model's events and reads the stored and the shown form back.
 * [device] is the kind of chip the model runs on ("NPU"), kept with the reply's measurements.
 */
internal class ReplyDraft(
    private val clock: () -> Long,
    private val device: String? = null,
    from: Continued? = null,
    /** The loaded model's context, in tokens: a reply cut where the chat fills it [filledContext]. */
    private val contextSize: Int? = null,
) {
    /**
     * A cut reply that is being continued (D60): what it had written, and how long it had thought. The new tokens are
     * added to it, and a reply cut while still thinking keeps its thinking clock running.
     */
    data class Continued(val text: String, val reasoning: String?, val reasoningMillis: Long?)

    private val text = StringBuilder(from?.text.orEmpty())
    private val reasoning = StringBuilder(from?.reasoning.orEmpty())
    private var thinkStart: Long? =
        from?.reasoning?.takeIf { it.isNotEmpty() }?.let { clock() - (from.reasoningMillis ?: 0L) }
    private var thinkMillis: Long? = from?.reasoningMillis?.takeIf { from.text.isNotEmpty() }

    /** The reply reached its length limit: it ends [MessageState.Cut], not done (D60). */
    private var cut = false

    /**
     * The reply was cut because the chat, prompt and reply, filled the context, not at the reply's own limit (No limit,
     * or a long limit on a small context). Continue would have no room, so it ends as a failure instead of a cut.
     */
    var filledContext: Boolean = false
        private set
    private var wordsPerSecond = 0.0
    private var stats: ReplyStats? = null

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
                cut = event.reason == FinishReason.Length
                val t = event.timings
                // llama-server's usage counts the whole prompt; its timings split it into new and cached tokens
                val used =
                    event.usage?.let { it.promptTokens + it.completionTokens }
                        ?: t?.let { it.promptTokens + it.cachedTokens + it.predictedTokens }
                filledContext = cut && contextSize != null && used != null && used >= contextSize
                wordsPerSecond = (t?.predictedPerSecond ?: 0.0) * Fit.WORDS_PER_TOKEN
                stats =
                    ReplyStats(
                        replyTokens = t?.predictedTokens,
                        tokensPerSecond = t?.predictedPerSecond,
                        promptTokens = t?.promptTokens,
                        cachedTokens = t?.cachedTokens,
                        promptPerSecond = t?.promptPerSecond,
                        draftTokens = t?.draftTokens,
                        draftAccepted = t?.draftAccepted,
                        device = device,
                    )
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

    /**
     * The reply as it ended: finished ([MessageState.Done], with its speed), cut at its length limit
     * ([MessageState.Cut], with its speed too: it ran to the end it was given), or stopped by the user. A reply cut
     * while thinking keeps how long it had thought, so a continue goes on counting from there.
     */
    fun ended(state: MessageState): MessageChange {
        val end = if (state == MessageState.Done && cut) MessageState.Cut else state
        val measured = end == MessageState.Done || end == MessageState.Cut
        if (end == MessageState.Done || text.isNotEmpty()) endThinking()
        return MessageChange(
            text = text.toString(),
            reasoning = reasoningOrNull(),
            reasoningMillis = thinkMillis ?: thinkStart?.takeIf { end == MessageState.Cut }?.let { clock() - it },
            wordsPerSecond = wordsPerSecond.takeIf { measured && it > 0 },
            stats = stats.takeIf { measured },
            state = end,
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
