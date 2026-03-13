package com.suhel.llamabro.sdk

import com.suhel.llamabro.sdk.model.Message

/**
 * Low-level inference session backed by a llama.cpp context.
 *
 * Feed formatted prompts with [prompt], then call [generate] in a loop to sample
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

    /** Ingests a formatted prompt message into the KV cache. Blocks softly but is cancellable. */
    suspend fun prompt(message: Message)

    /** Samples the next token. Returns `null` when generation is complete. Blocks softly but is cancellable. */
    suspend fun generate(): String?

    /** Clears conversation history, keeping only the system prompt. */
    fun clear()

    /** Asynchronously signals the native engine to preempt any active prompt ingestion or token generation. */
    fun abort()
}
