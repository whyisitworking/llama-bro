package com.suhel.llamabro.conversation

/**
 * How full the context was after the last reply: `prompt_n + cache_n + predicted_n` from its timings
 * (design §7.2), out of the model's context size.
 */
public data class ContextUsage(public val used: Int, public val size: Int)
