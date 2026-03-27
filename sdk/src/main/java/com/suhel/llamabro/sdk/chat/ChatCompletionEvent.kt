package com.suhel.llamabro.sdk.chat

import com.suhel.llamabro.sdk.model.LlamaError

/**
 * Streaming events from a chat completion, mirroring OpenAI SSE chunks.
 *
 * Each [Delta] contains incremental content — either regular text, reasoning content, or both.
 * The stream terminates with either a [Done] event (success) or an [Error] event.
 */
sealed interface ChatCompletionEvent {

    /**
     * An incremental content delta.
     *
     * @param content Regular text content delta, or null if this chunk is reasoning-only.
     * @param reasoningContent Thinking/reasoning content delta, or null if this chunk is text-only.
     */
    data class Delta(
        val content: String? = null,
        val reasoningContent: String? = null,
    ) : ChatCompletionEvent

    /**
     * Final event indicating completion, with usage statistics.
     *
     * @param finishReason Why generation stopped: "stop", "length", or "tool_calls".
     * @param usage Token usage statistics.
     */
    data class Done(
        val finishReason: String,
        val usage: Usage,
    ) : ChatCompletionEvent

    /**
     * An error occurred during generation.
     *
     * @param error The error that occurred.
     */
    data class Error(
        val error: LlamaError,
    ) : ChatCompletionEvent
}
