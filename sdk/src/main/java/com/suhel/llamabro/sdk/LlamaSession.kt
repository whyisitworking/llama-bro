package com.suhel.llamabro.sdk

import com.suhel.llamabro.sdk.model.ResourceState
import com.suhel.llamabro.sdk.model.ModelConfig
import kotlinx.coroutines.flow.Flow

/**
 * Low-level inference session backed by a llama.cpp context.
 *
 * A session provides direct access to the KV cache and token generation loop.
 * It is suitable for applications requiring fine-grained control over prompt
 * ingestion and token sampling.
 *
 * For a higher-level, conversational API that handles chat templates and
 * reasoning blocks, use [createChatSession].
 *
 * ### Thread Safety
 * **Instances are not thread-safe.** A session must be accessed from a single
 * coroutine at a time. All suspending methods are safe to call from any
 * dispatcher (they internally switch to [kotlinx.coroutines.Dispatchers.IO]).
 *
 * ### Lifecycle
 * Sessions are bound to the parent [LlamaEngine]. Always call [close] when finished
 * to release the native context memory.
 */
interface LlamaSession : AutoCloseable {
    /** The configuration used to load the parent engine. */
    val modelConfig: ModelConfig

    /**
     * Sets the system prompt for the session.
     *
     * This prompt is usually pinned at the start of the context and is preserved
     * even during history clearing or rolling window overflows.
     *
     * @param text       Raw text to add to the context.
     * @param addSpecial If true, prepends the model's default BOS token.
     */
    suspend fun setSystemPrompt(text: String, addSpecial: Boolean = true)

    /**
     * Ingests raw text into the KV cache.
     *
     * This method blocks until the text is fully processed (pre-filled).
     * It is cancellable; if the coroutine is cancelled, the native pre-fill
     * loop will be interrupted.
     *
     * @param text       Raw text to add to the context.
     * @param addSpecial If true, prepends the model's default BOS token.
     * @throws LlamaError.ContextOverflow if the context is full and cannot be recovered.
     */
    suspend fun prompt(text: String, addSpecial: Boolean = false)

    /**
     * Samples the next token from the model based on the current context.
     *
     * Call this in a loop to generate a complete response.
     *
     * @return The generated token as a String, or `null` if the model emits an
     *         End-of-Generation (EOG) token.
     * @throws LlamaError.DecodeFailed if the native sampling loop fails.
     */
    suspend fun generate(): String?

    /**
     * Clears the conversation history from the KV cache.
     *
     * The system prompt (if set via [setSystemPrompt]) is preserved.
     */
    suspend fun clear()

    /**
     * Asynchronously signals the native engine to stop any active computation.
     *
     * Use this to immediately halt a long-running [prompt] or [generate] call
     * from another thread or UI action.
     */
    fun abort()

    /**
     * Creates a high-level chat session on top of this low-level session.
     *
     * @param systemPrompt The instruction defining the assistant's persona.
     * @return A ready-to-use [LlamaChatSession].
     */
    suspend fun createChatSession(systemPrompt: String): LlamaChatSession

    /**
     * Creates a high-level chat session asynchronously.
     */
    fun createChatSessionFlow(systemPrompt: String): Flow<ResourceState<LlamaChatSession>>
}
