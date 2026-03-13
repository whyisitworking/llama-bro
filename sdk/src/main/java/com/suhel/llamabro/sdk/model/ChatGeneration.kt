package com.suhel.llamabro.sdk.model

/**
 * Accumulated snapshot of an in-progress or completed generation.
 *
 * Each emission from [com.suhel.llamabro.sdk.LlamaChatSession.chat] is a progressively
 * growing instance. The final emission has [isComplete] = true and includes [tokensPerSecond].
 * Errors propagate as Flow exceptions (typed [LlamaError] subclasses).
 */
data class ChatGeneration(
    val thinkingText: String? = null,
    val contentText: String? = null,
    val tokensPerSecond: Float? = null,
    val isComplete: Boolean = false,
)
