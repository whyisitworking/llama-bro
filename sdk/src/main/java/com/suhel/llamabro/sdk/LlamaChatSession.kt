package com.suhel.llamabro.sdk

import com.suhel.llamabro.sdk.model.Completion
import com.suhel.llamabro.sdk.model.Message
import kotlinx.coroutines.flow.Flow

/**
 * High-level conversational API built on top of [com.suhel.llamabro.sdk.LlamaSession].
 *
 * Owns the [com.suhel.llamabro.sdk.model.PromptFormat] and handles all message formatting, assistant turn
 * boundaries, thinking-block parsing, and text accumulation.
 *
 * **Thread safety:** Instances are **not** thread-safe. Collect the [completion] flow
 * from a single coroutine at a time. Call [reset] only when no generation is active.
 */
interface LlamaChatSession {
    /**
     * Sends [message] to the model and returns a [com.suhel.llamabro.sdk.model.Completion] flow.
     *
     * The user message is formatted, the assistant turn prefix is appended,
     * and both are ingested as a single prompt before generation begins.
     *
     * Each emission is a progressively accumulated snapshot: thinking text,
     * content text, and finally tokens-per-second with [com.suhel.llamabro.sdk.model.Completion.isComplete] = true.
     */
    fun completion(message: String): Flow<Completion>

    /**
     * Clears the conversation history, keeping only the system prompt.
     * This blocks briefly while the native KV cache is reset.
     */
    suspend fun reset()

    /**
     * Ingest a list of historical messages into the conversation.
     * Each message is formatted according to the prompt template and fed
     * into the native KV cache. No token generation is performed.
     */
    suspend fun loadHistory(messages: List<Message>)
}
