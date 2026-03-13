package com.suhel.llamabro.sdk.model

/**
 * Defines how the session behaves when the context window fills up.
 */
sealed interface OverflowStrategy {
    /**
     * Halts generation and throws an exception. Useful for strict data
     * extraction tasks where context loss invalidates the result.
     */
    data object Halt : OverflowStrategy

    /**
     * Clears the entire conversation history, keeping only the system prompt.
     */
    data object ClearHistory : OverflowStrategy

    /**
     * The smart default. Natively shifts the KV cache to the left, dropping
     * the oldest messages while perfectly preserving the system prompt.
     * * @param dropTokens How many tokens to clear when an overflow occurs.
     */
    data class RollingWindow(val dropTokens: Int = 500) : OverflowStrategy
}