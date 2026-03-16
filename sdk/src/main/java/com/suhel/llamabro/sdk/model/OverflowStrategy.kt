package com.suhel.llamabro.sdk.model

/**
 * Defines how the session behaves when the context window fills up.
 *
 * The context window is the finite memory buffer ([SessionConfig.contextSize]) 
 * that holds the system prompt, conversation history, and the new tokens being 
 * generated. When this limit is reached, a strategy must be chosen to make 
 * room for more tokens.
 */
sealed interface OverflowStrategy {
    /**
     * Halts generation and throws a [LlamaError.ContextOverflow].
     * 
     * This is useful for strict data extraction or summarization tasks where 
     * losing any part of the input context would invalidate the result.
     */
    data object Halt : OverflowStrategy

    /**
     * Clears the entire conversation history, keeping only the system prompt.
     * 
     * This starts the conversation from a clean slate once the limit is hit. 
     * Note that the user message that caused the overflow is also cleared.
     */
    data object ClearHistory : OverflowStrategy

    /**
     * The smart default. Natively shifts the KV cache to the left, dropping
     * the oldest messages while perfectly preserving the system prompt.
     * 
     * This allows for "infinite" feeling conversations by always keeping the 
     * most recent context.
     * 
     * @property dropTokens The number of tokens to clear from the oldest part 
     *                      of the history when an overflow occurs. Default: 500.
     */
    data class RollingWindow(val dropTokens: Int = 500) : OverflowStrategy
}
