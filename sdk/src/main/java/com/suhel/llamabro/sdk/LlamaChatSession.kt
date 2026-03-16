package com.suhel.llamabro.sdk

import com.suhel.llamabro.sdk.model.Completion
import com.suhel.llamabro.sdk.model.Message
import kotlinx.coroutines.flow.Flow

/**
 * High-level conversational API built on top of [LlamaSession].
 *
 * This interface abstracts away the low-level prompt engineering and token
 * management. It handles:
 * - Message formatting using the [com.suhel.llamabro.sdk.model.PromptFormat] from the engine.
 * - Assistant turn boundary management.
 * - Real-time parsing of "thinking" blocks (e.g., `<think>...</think>`).
 * - Accumulating token streams into structured [Completion] snapshots.
 *
 * ### Usage
 * 1. Initialize with a system prompt.
 * 2. Call [completion] with a user message to start generation.
 * 3. Collect the resulting flow to receive updates.
 *
 * ### Thread Safety
 * **Instances are not thread-safe.** Generation must be collected from a single
 * coroutine at a time. Do not call [reset] or [loadHistory] while a generation
 * flow is active.
 */
interface LlamaChatSession {
    /**
     * Sends a message to the model and returns a reactive [Completion] flow.
     *
     * The flow emits progressively accumulated snapshots. Each emission includes
     * the latest thinking text, content text, and eventually performance metrics
     * like tokens-per-second once generation finishes.
     *
     * If the collector's coroutine is cancelled, the underlying native generation
     * is automatically aborted.
     *
     * @param message The user's input text.
     * @return A flow of [Completion] updates.
     */
    fun completion(message: String): Flow<Completion>

    /**
     * Clears the current conversation history while retaining the system prompt.
     *
     * This is useful for starting a new topic within the same session.
     */
    suspend fun reset()

    /**
     * Loads a sequence of historical messages into the session.
     *
     * This is used to "restore" a conversation state from a database or cache.
     * It performs prompt ingestion (pre-fill) for each message but does not
     * trigger generation.
     *
     * @param messages A list of [Message.User] and [Message.Assistant] messages.
     */
    suspend fun loadHistory(messages: List<Message>)
}
