package com.suhel.llamabro.conversation

import com.suhel.llamabro.chat.Timings

/**
 * How full the context was after the last reply: `prompt_n + cache_n + predicted_n` from its timings, out of the
 * model's context size.
 */
public data class ContextUsage(public val used: Int, public val size: Int) {
    internal companion object {
        /** From a reply's or a pre-encode's [timings], in a context of [size] tokens. */
        fun of(timings: Timings, size: Int): ContextUsage =
            ContextUsage(timings.promptTokens + timings.cachedTokens + timings.predictedTokens, size)
    }
}
