package com.suhel.llamabro.chat

/** llama-server's per-request `timings`. Draft fields are set only with speculative decoding. */
public class Timings internal constructor(
    public val promptTokens: Int,
    public val cachedTokens: Int,
    public val promptPerSecond: Double,
    public val predictedTokens: Int,
    public val predictedPerSecond: Double,
    public val draftTokens: Int?,
    public val draftAccepted: Int?,
) {
    override fun equals(other: Any?): Boolean = other is Timings && parts() == other.parts()
    override fun hashCode(): Int = parts().hashCode()
    override fun toString(): String =
        "Timings(promptTokens=$promptTokens, cachedTokens=$cachedTokens, promptPerSecond=$promptPerSecond, " +
            "predictedTokens=$predictedTokens, predictedPerSecond=$predictedPerSecond, draftTokens=$draftTokens, draftAccepted=$draftAccepted)"

    private fun parts() = listOf(promptTokens, cachedTokens, promptPerSecond, predictedTokens, predictedPerSecond, draftTokens, draftAccepted)
}

/** OpenAI `usage`. */
public class Usage internal constructor(public val promptTokens: Int, public val completionTokens: Int) {
    override fun equals(other: Any?): Boolean = other is Usage && other.promptTokens == promptTokens && other.completionTokens == completionTokens
    override fun hashCode(): Int = 31 * promptTokens + completionTokens
    override fun toString(): String = "Usage(promptTokens=$promptTokens, completionTokens=$completionTokens)"
}
