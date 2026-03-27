package com.suhel.llamabro.sdk.chat

import kotlinx.coroutines.flow.Flow

/**
 * Stateless chat completion interface, mirroring OpenAI's `/v1/chat/completions` endpoint.
 *
 * Each call to [create] accepts the full conversation history and returns a streaming flow
 * of [ChatCompletionEvent]s. The implementation handles token-level prefix caching internally,
 * so repeated calls with overlapping message histories are efficient.
 *
 * ### Usage
 * ```kotlin
 * val completion: LlamaChatCompletion = session.createChatCompletion()
 * completion.initialize()
 *
 * val events = completion.create(
 *     messages = listOf(
 *         ChatMessage(role = "system", content = "You are helpful."),
 *         ChatMessage(role = "user", content = "Hello!"),
 *     ),
 *     options = ChatCompletionOptions(enableThinking = true),
 * )
 *
 * events.collect { event ->
 *     when (event) {
 *         is ChatCompletionEvent.Delta -> {
 *             event.content?.let { print(it) }
 *             event.reasoningContent?.let { /* handle thinking */ }
 *         }
 *         is ChatCompletionEvent.Done -> { /* generation complete */ }
 *         is ChatCompletionEvent.Error -> { /* handle error */ }
 *     }
 * }
 * ```
 */
interface LlamaChatCompletion {

    /**
     * Initialize the chat template engine from the model's GGUF metadata.
     * Must be called once before the first [create] call.
     */
    suspend fun initialize()

    /**
     * Create a streaming chat completion from the given message history.
     *
     * @param messages The full conversation history, including system prompt.
     * @param options Sampling parameters and feature flags for this request.
     * @return A flow of [ChatCompletionEvent]s — deltas followed by a terminal Done or Error.
     */
    fun create(
        messages: List<ChatMessage>,
        options: ChatCompletionOptions = ChatCompletionOptions(),
    ): Flow<ChatCompletionEvent>
}
