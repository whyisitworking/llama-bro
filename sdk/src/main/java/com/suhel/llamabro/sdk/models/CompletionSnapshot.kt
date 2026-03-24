package com.suhel.llamabro.sdk.models

/**
 * A snapshot of the model's completion at a point in time.
 *
 * Emitted progressively during inference. The final emission has [isComplete] = true.
 */
data class CompletionSnapshot(
    val message: ChatEvent.AssistantEvent,
    val isComplete: Boolean,
    val isError: Boolean,
    val error: String?,
    /** Tokens generated per second. Only meaningful when [isComplete] is true. */
    val tokensPerSecond: Float = 0f,
)
