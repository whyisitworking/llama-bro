package com.suhel.llamabro.chat

/** llama-server's per-request `timings`. Draft fields are set only with speculative decoding. */
public data class Timings internal constructor(
    public val promptTokens: Int,
    public val cachedTokens: Int,
    public val promptPerSecond: Double,
    public val predictedTokens: Int,
    public val predictedPerSecond: Double,
    public val draftTokens: Int?,
    public val draftAccepted: Int?,
)

/** OpenAI `usage`. */
public data class Usage internal constructor(public val promptTokens: Int, public val completionTokens: Int)
