package com.suhel.llamabro.sdk.api

import com.suhel.llamabro.sdk.schema.Chunk
import com.suhel.llamabro.sdk.schema.Message
import kotlinx.coroutines.flow.Flow

/**
 * A high-level, coroutine-native conversation session backed by a [LlamaSession].
 *
 * This is the interface most consumers should use. It handles:
 * - Chat history accumulation
 * - Streaming token output via [Flow]
 * - Tokens-per-second measurement per response
 * - Cooperative cancellation (cancel the coroutine scope to stop mid-generation)
 *
 * Obtain an instance via [LlamaSession.createChatSession].
 */
interface ChatSession : AutoCloseable {

    /**
     * The full conversation history in chronological order.
     * Updated after each [chat] flow completes (on EOS or cancellation).
     */
    val history: List<Message>

    /**
     * Sends a [UserMessage] to the model and returns a cold [Flow] of [Chunk]s.
     *
     * **The flow is cold** — nothing happens until you collect it. Cancelling the
     * collector mid-stream stops generation cooperatively at the next token boundary.
     *
     * Each emitted [Chunk] is one valid text piece (may be partial word). Collect
     * and concatenate for a full response, or display incrementally for a typing effect.
     *
     * Errors (e.g., [LlamaError.ContextOverflow], [LlamaError.DecodeFailed]) are
     * propagated as flow exceptions and can be caught with [Flow.catch].
     *
     * **Threading:** The flow runs on [kotlinx.coroutines.Dispatchers.IO] automatically.
     * Collect on any dispatcher.
     *
     * Example:
     * ```kotlin
     * chatSession.chat(UserMessage("Explain quantum entanglement"))
     *     .map { it.text }
     *     .catch { e -> handleError(e as? LlamaError) }
     *     .collect { piece -> appendToUI(piece) }
     * ```
     */
    fun chat(message: UserMessage): Flow<Chunk>

    /**
     * Clears [history] and resets the session to its initial state (system prompt preserved).
     * Call this to start a new conversation without recreating the session.
     */
    fun reset()
}

/**
 * A message composed by the user, sent via [ChatSession.chat].
 *
 * @param content      The message text.
 * @param showThinking Whether to expose the model's internal reasoning (thinking) tokens as
 *                     [Chunk.Thinking] chunks alongside [Chunk.Content] chunks. Only meaningful
 *                     for reasoning models (e.g., DeepSeek R1, QwQ). Default: false.
 */
data class UserMessage(
    val content: String,
    val showThinking: Boolean = false,
)

/**
 * Configuration for creating a [ChatSession].
 *
 * @param systemPrompt Optional system-level instruction prepended before the conversation.
 *                     Formatted with the engine's [PromptFormat] automatically.
 */
data class ChatSessionConfig(
    val systemPrompt: String? = null,
)
