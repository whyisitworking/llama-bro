package com.suhel.llamabro.sdk

import com.suhel.llamabro.sdk.model.ResourceState
import com.suhel.llamabro.sdk.model.ModelConfig
import kotlinx.coroutines.flow.Flow

/**
 * Low-level inference session backed by a llama.cpp context.
 *
 * Feed raw formatted text with [prompt], then call [generate] in a loop to sample
 * tokens one at a time. Use [clear] to wipe the conversation while keeping the
 * system prompt.
 *
 * For a higher-level conversational API, see [LlamaChatSession].
 *
 * **Thread safety:** Instances are **not** thread-safe. All calls must come from
 * a single coroutine (or be externally synchronised). Close sessions before
 * closing the parent [LlamaEngine].
 */
interface LlamaSession : AutoCloseable {
    val modelConfig: ModelConfig

    suspend fun setSystemPrompt(text: String)

    /** Ingests raw text into the KV cache. Blocks softly but is cancellable. */
    suspend fun prompt(text: String)

    /** Samples the next token. Returns `null` when generation is complete. Blocks softly but is cancellable. */
    suspend fun generate(): String?

    /** Clears conversation history, keeping only the system prompt. */
    suspend fun clear()

    /** Asynchronously signals the native engine to preempt any active prompt ingestion or token generation. */
    fun abort()

    suspend fun createChatSession(systemPrompt: String): LlamaChatSession

    fun createChatSessionFlow(systemPrompt: String): Flow<ResourceState<LlamaChatSession>>
}
