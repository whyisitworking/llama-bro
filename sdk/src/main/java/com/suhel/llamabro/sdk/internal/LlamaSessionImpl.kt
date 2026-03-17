package com.suhel.llamabro.sdk.internal

import com.suhel.llamabro.sdk.LlamaChatSession
import com.suhel.llamabro.sdk.LlamaSession
import com.suhel.llamabro.sdk.model.LlamaError
import com.suhel.llamabro.sdk.model.ModelConfig
import com.suhel.llamabro.sdk.model.OverflowStrategy
import com.suhel.llamabro.sdk.model.ResourceState
import com.suhel.llamabro.sdk.model.SessionConfig
import com.suhel.llamabro.sdk.model.TokenGenerationResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Internal implementation of [LlamaSession] managing a single llama.cpp context.
 *
 * This class uses a [Mutex] to ensure that only one operation (prompting or generating)
 * is active at a time, as the underlying native context is not thread-safe.
 */
internal class LlamaSessionImpl(
    enginePtr: Long,
    sessionConfig: SessionConfig,
    override val modelConfig: ModelConfig,
) : LlamaSession {
    private val mutex = Mutex()

    /** Pointer to the native llama_bro_session structure. */
    private val ptr: Long = try {
        Jni.create(
            enginePtr = enginePtr,
            params = NativeCreateParams(
                contextSize = sessionConfig.contextSize,
                overflowStrategyId = when (sessionConfig.overflowStrategy) {
                    OverflowStrategy.Halt -> 0
                    OverflowStrategy.ClearHistory -> 1
                    is OverflowStrategy.RollingWindow -> 2
                },
                overflowDropTokens = (sessionConfig.overflowStrategy as? OverflowStrategy.RollingWindow)
                    ?.dropTokens ?: 0,
                topKEnabled = sessionConfig.inferenceConfig.topK != null,
                topK = sessionConfig.inferenceConfig.topK ?: 0,
                topPEnabled = sessionConfig.inferenceConfig.topP != null,
                topP = sessionConfig.inferenceConfig.topP ?: 0f,
                minPEnabled = sessionConfig.inferenceConfig.minP != null,
                minP = sessionConfig.inferenceConfig.minP ?: 0f,
                repPen = sessionConfig.inferenceConfig.repeatPenalty,
                presencePen = sessionConfig.inferenceConfig.presencePenalty,
                temp = sessionConfig.inferenceConfig.temperature,
                seed = sessionConfig.seed,
                batchSize = sessionConfig.decodeConfig.batchSize,
                microBatchSize = sessionConfig.decodeConfig.microBatchSize,
            )
        )
    } catch (e: RuntimeException) {
        throw mapNativeError(e)
    }

    override suspend fun setSystemPrompt(text: String, addSpecial: Boolean) =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                try {
                    runInterruptible {
                        Jni.setSystemPrompt(ptr, text, addSpecial)
                    }
                } catch (e: RuntimeException) {
                    throw mapNativeError(e)
                }
            }
        }

    override suspend fun prompt(text: String, addSpecial: Boolean) =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                try {
                    runInterruptible {
                        Jni.injectPrompt(ptr, text, addSpecial)
                    }
                } catch (e: RuntimeException) {
                    throw mapNativeError(e)
                }
            }
        }

    override suspend fun generate(): TokenGenerationResult =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                try {
                    runInterruptible {
                        Jni.generate(ptr).let {
                            TokenGenerationResult(
                                token = it.token,
                                isComplete = it.isComplete,
                            )
                        }
                    }
                } catch (e: RuntimeException) {
                    throw mapNativeError(e)
                }
            }
        }

    override suspend fun clear() =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                try {
                    runInterruptible {
                        Jni.clear(ptr)
                    }
                } catch (e: RuntimeException) {
                    throw mapNativeError(e)
                }
            }
        }

    override fun abort() {
        Jni.abort(ptr)
    }

    override fun close() {
        Jni.destroy(ptr)
    }

    override suspend fun createChatSession(systemPrompt: String): LlamaChatSession =
        withContext(Dispatchers.IO) {
            LlamaChatSessionImpl(this@LlamaSessionImpl, systemPrompt).also { it.initialize() }
        }

    override fun createChatSessionFlow(systemPrompt: String): Flow<ResourceState<LlamaChatSession>> =
        callbackFlow {
            try {
                send(ResourceState.Loading())
                val session = createChatSession(systemPrompt)
                send(ResourceState.Success(session))
            } catch (e: Exception) {
                val llamaError = e as? LlamaError
                    ?: LlamaError.NativeException(e.message ?: "Unknown", e)
                send(ResourceState.Failure(llamaError))
            }

            awaitClose()
        }.flowOn(Dispatchers.IO)

    // ── JNI params ───────────────────────────────────────────────────────────

    private class NativeCreateParams(
        val contextSize: Int,
        val overflowStrategyId: Int,
        val overflowDropTokens: Int,
        val topKEnabled: Boolean,
        val topK: Int,
        val topPEnabled: Boolean,
        val topP: Float,
        val minPEnabled: Boolean,
        val minP: Float,
        // Always-on (no enable field)
        val repPen: Float,
        val presencePen: Float,
        val temp: Float,
        val seed: Int,
        // Decode tuning
        val batchSize: Int,
        val microBatchSize: Int,
    )

    private class NativeTokenGenerationResult(
        val token: String?,
        val isComplete: Boolean,
    )

    private object Jni {
        @JvmStatic
        external fun create(enginePtr: Long, params: NativeCreateParams): Long

        @JvmStatic
        external fun setSystemPrompt(sessionPtr: Long, text: String, addSpecial: Boolean)

        @JvmStatic
        external fun injectPrompt(sessionPtr: Long, text: String, addSpecial: Boolean)

        @JvmStatic
        external fun clear(sessionPtr: Long)

        @JvmStatic
        external fun abort(sessionPtr: Long)

        @JvmStatic
        external fun generate(sessionPtr: Long): NativeTokenGenerationResult

        @JvmStatic
        external fun destroy(sessionPtr: Long)
    }
}
