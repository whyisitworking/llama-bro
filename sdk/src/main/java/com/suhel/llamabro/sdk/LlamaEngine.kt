package com.suhel.llamabro.sdk

import com.suhel.llamabro.sdk.internal.LlamaEngineImpl
import com.suhel.llamabro.sdk.internal.ProgressListener
import com.suhel.llamabro.sdk.model.LlamaError
import com.suhel.llamabro.sdk.model.LoadEvent
import com.suhel.llamabro.sdk.model.ModelConfig
import com.suhel.llamabro.sdk.model.SessionConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive

/**
 * Entry point for on-device LLM inference.
 *
 * An engine holds a loaded model and creates [LlamaSession] instances.
 * Use [LlamaEngine.Companion.createFlow] for coroutine-friendly async loading with progress,
 * or [LlamaEngine.Companion.create] for synchronous loading.
 *
 * **Lifecycle:** Close all sessions before closing the engine. Accessing a session
 * after its engine is closed is undefined behaviour.
 *
 * **Thread safety:** Creating sessions is thread-safe, but each [LlamaSession]
 * must be used from one coroutine at a time.
 */
interface LlamaEngine : AutoCloseable {

    /** Creates a session synchronously. Blocks while the system prompt is ingested. */
    fun createSession(sessionConfig: SessionConfig): LlamaSession

    /** Creates a session asynchronously, emitting [LoadEvent] progress updates. */
    fun createSessionFlow(sessionConfig: SessionConfig): Flow<LoadEvent<LlamaSession>>

    companion object {
        private val nativeLoaded by lazy { System.loadLibrary("llama_bro") }

        private fun ensureNativeLoaded() {
            nativeLoaded
        }

        /**
         * Loads a model synchronously and returns an engine.
         *
         * @param modelConfig Model file path and loading options.
         * @param onProgress  Optional progress callback (0.0..1.0). Return `true` to continue, `false` to abort.
         * @throws LlamaError on model load failure.
         */
        fun create(
            modelConfig: ModelConfig,
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
         * Loads a model asynchronously, emitting [LoadEvent] progress updates.
         *
         * The engine is automatically closed when the returned flow's collector is cancelled.
         */
        fun createFlow(modelConfig: ModelConfig): Flow<LoadEvent<LlamaEngine>> = callbackFlow {
            ensureNativeLoaded()

            val listener = object : ProgressListener {
                override fun onProgress(progress: Float): Boolean {
                    trySend(LoadEvent.Loading(progress))
                    return isActive
                }
            }

            var engine: LlamaEngine? = null

            try {
                send(LoadEvent.Loading())
                engine = LlamaEngineImpl(modelConfig, listener)
                send(LoadEvent.Ready(engine))
            } catch (e: Exception) {
                val llamaError = e as? LlamaError
                    ?: LlamaError.NativeException(e.message ?: "Unknown", e)
                send(LoadEvent.Error(llamaError))
            }

            awaitClose {
                engine?.close()
            }
        }.flowOn(Dispatchers.IO)
    }
}
