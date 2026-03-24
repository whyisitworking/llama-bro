package com.suhel.llamabro.sdk.internal

import com.suhel.llamabro.sdk.chat.LlamaChatSession
import com.suhel.llamabro.sdk.chat.internal.LlamaChatSessionImpl
import com.suhel.llamabro.sdk.engine.LlamaSession
import com.suhel.llamabro.sdk.config.ModelDefinition
import com.suhel.llamabro.sdk.config.OverflowStrategy
import com.suhel.llamabro.sdk.config.SessionConfig
import com.suhel.llamabro.sdk.engine.TokenGenerationResult
import com.suhel.llamabro.sdk.engine.TokenGenerationResultCode
import com.suhel.llamabro.sdk.model.LlamaError
import com.suhel.llamabro.sdk.model.ResourceState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
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
    override val modelDefinition: ModelDefinition
) : LlamaSession {
    private val mutex = Mutex()
    private val result = NativeTokenGenerationResult()

    /** Pointer to the native llama_bro_session structure. */
    private val ptr: Long =
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

    override suspend fun setPrefixedPrompt(text: String) =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                runInterruptible {
                    Jni.setSystemPrompt(ptr, text)
                }
            }
        }

    override suspend fun addPrompt(prompt: String) =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                runInterruptible {
                    Jni.addUserPrompt(ptr, prompt)
                }
            }
        }

    override suspend fun generate(): TokenGenerationResult =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                runInterruptible {
                    Jni.generate(ptr, result)

                    TokenGenerationResult(
                        token = result.token,
                        resultCode = TokenGenerationResultCode.parse(result.resultCode),
                        isComplete = result.isComplete,
                    )
                }
            }
        }

    override fun generateFlow(): Flow<TokenGenerationResult> = channelFlow {
        withContext(Dispatchers.IO) {
            mutex.withLock {
                var isDone = false
                while (!isDone && kotlinx.coroutines.currentCoroutineContext().isActive) {
                    val genResult = runInterruptible {
                        Jni.generate(ptr, result)
                        TokenGenerationResult(
                            token = result.token,
                            resultCode = TokenGenerationResultCode.parse(result.resultCode),
                            isComplete = result.isComplete,
                        )
                    }
                    send(genResult)
                    isDone = genResult.isComplete || genResult.resultCode != TokenGenerationResultCode.OK
                }
            }
        }
    }

    override suspend fun clear() =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                runInterruptible {
                    Jni.clear(ptr)
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
        var token: String? = null,
        var resultCode: Int = 0,
        var isComplete: Boolean = false,
    )

    private object Jni {
        @JvmStatic
        external fun create(enginePtr: Long, params: NativeCreateParams): Long

        @JvmStatic
        external fun setSystemPrompt(sessionPtr: Long, prompt: String)

        @JvmStatic
        external fun addUserPrompt(sessionPtr: Long, prompt: String)

        @JvmStatic
        external fun clear(sessionPtr: Long)

        @JvmStatic
        external fun abort(sessionPtr: Long)

        @JvmStatic
        external fun generate(sessionPtr: Long, result: NativeTokenGenerationResult)

        @JvmStatic
        external fun destroy(sessionPtr: Long)
    }
}
