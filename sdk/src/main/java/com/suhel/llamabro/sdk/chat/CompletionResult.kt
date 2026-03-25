package com.suhel.llamabro.sdk.chat

import com.suhel.llamabro.sdk.model.LlamaError

/**
 * The result of a streaming completion, emitted progressively during inference.
 *
 * Each [Streaming] emission carries the full timeline of assistant events so far.
 * The final emission is either [Complete] (with timing metrics) or [Error].
 */
sealed interface CompletionResult {

    data class Streaming(
        val events: List<ChatEvent.AssistantEvent.Part>
    ) : CompletionResult

    data class Complete(
        val events: List<ChatEvent.AssistantEvent.Part>,
        val tokensPerSecond: Float,
    ) : CompletionResult

    data class Error(
        val error: LlamaError
    ) : CompletionResult
}
