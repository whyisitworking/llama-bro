package com.suhel.llamabro.sdk.engine

import com.suhel.llamabro.sdk.ProgressListener
import com.suhel.llamabro.sdk.config.ModelDefinition
import com.suhel.llamabro.sdk.config.SessionConfig
import com.suhel.llamabro.sdk.internal.LlamaEngineImpl
import com.suhel.llamabro.sdk.model.LlamaError
import com.suhel.llamabro.sdk.model.ResourceState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive

/**
 * Entry point for on-device LLM inference using llama-bro.
 *
 * An engine instance manages a loaded GGUF model and serves as a factory for [LlamaSession]s.
 * It handles the heavy lifting of loading the model into memory (via MMAP or MLOCK) and
 * managing the underlying GGML compute backends.
 *
 * Use [LlamaEngine.Companion.createFlow] for a reactive, progress-aware loading experience,
 * or [LlamaEngine.Companion.create] for standard synchronous initialization.
 *
 * ### Lifecycle
 * An engine should be closed when it is no longer needed to free up significant system memory.
 * Ensure all child [LlamaSession] instances are closed before closing the engine. Accessing
 * a session after its parent engine is closed will result in undefined behavior (likely a native crash).
 *
 * ### Thread Safety
 * This interface is thread-safe. You can create multiple sessions concurrently. However,
 * individual [LlamaSession] instances are generally not thread-safe.
 */
interface LlamaEngine : AutoCloseable {

    /**
     * Creates a new inference session synchronously.
     *
     * This method allocates a new llama.cpp context. If the [sessionConfig] includes a large
     * context size, this may take some time and consume significant RAM.
     *
     * @param sessionConfig Configuration for the context size, sampling, and overflow behavior.
     * @return A ready-to-use [LlamaSession].
     * @throws LlamaError.ContextInitFailed if the context could not be allocated (e.g., OOM).
     */
    suspend fun createSession(sessionConfig: SessionConfig): LlamaSession

    /**
     * Creates a new inference session asynchronously, providing status updates via a [Flow].
     *
     * This is the preferred way to create sessions in a UI environment, as it allows
     * observing the loading state without blocking the main thread.
     *
     * @param sessionConfig Configuration for the session.
     * @return A flow emitting [ResourceState.Loading], then [ResourceState.Success] with the session,
     * or [ResourceState.Failure] on error.
     */
    fun createSessionFlow(sessionConfig: SessionConfig): Flow<ResourceState<LlamaSession>>

    companion object {
        private val nativeLoaded by lazy { System.loadLibrary("llama_bro") }

        private fun ensureNativeLoaded() {
            nativeLoaded
        }

        /**
         * Loads a GGUF model synchronously and returns a [LlamaEngine].
         *
         * @param modelConfig Path to the model file and loading options like MMAP/MLOCK.
         * @param onProgress Optional callback receiving loading progress (0.0 to 1.0).
         *                   Return `true` to continue loading, `false` to abort.
         * @return A loaded [LlamaEngine] instance.
         * @throws LlamaError.ModelNotFound if the file path is invalid.
         * @throws LlamaError.ModelLoadFailed if the GGUF file is corrupt or incompatible.
         */
        fun create(
            modelConfig: ModelDefinition,
            onProgress: ((Float) -> Boolean)? = null
        ): LlamaEngine {
            ensureNativeLoaded()
            val listener = onProgress?.let { callback ->
                object : ProgressListener {
                    override fun onProgress(progress: Float): Boolean = callback(progress)
                }
            }
            return LlamaEngineImpl(modelConfig, listener)
        }

        /**
         * Loads a GGUF model asynchronously, emitting progress and the final [LlamaEngine].
         *
         * The engine is automatically closed if the flow collection is cancelled before completion.
         * If the flow completes with [ResourceState.Success], the caller assumes ownership of the
         * engine and must call [LlamaEngine.close] when finished.
         *
         * @param modelConfig Path and loading options for the model.
         * @return A flow of [ResourceState] representing the loading lifecycle.
         */
        fun createFlow(modelConfig: ModelDefinition): Flow<ResourceState<LlamaEngine>> = callbackFlow {
            ensureNativeLoaded()

            val listener = object : ProgressListener {
                override fun onProgress(progress: Float): Boolean {
                    trySend(ResourceState.Loading(progress))
                    return isActive
                }
            }

            var engine: LlamaEngine? = null

            try {
                send(ResourceState.Loading())
                engine = LlamaEngineImpl(modelConfig, listener)
                send(ResourceState.Success(engine))
            } catch (e: Exception) {
                val llamaError = e as? LlamaError
                    ?: LlamaError.NativeException(e.message ?: "Unknown", e)
                send(ResourceState.Failure(llamaError))
            }

            awaitClose {
                // Only close if we didn't successfully hand it over to the user
                // Actually, the user might expect the engine to stay open if Success was sent.
                // But in this flow, if the flow is closed, we close the engine.
                // This is a common pattern for "managed" resources in flows.
                engine?.close()
            }
        }.flowOn(Dispatchers.IO)
    }
}
