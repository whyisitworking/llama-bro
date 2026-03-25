package com.suhel.llamabro.sdk.engine.internal

import com.suhel.llamabro.sdk.chat.LlamaChatSession
import com.suhel.llamabro.sdk.chat.internal.LlamaChatSessionImpl
import com.suhel.llamabro.sdk.config.LoadableModel
import com.suhel.llamabro.sdk.config.OverflowStrategy
import com.suhel.llamabro.sdk.config.SessionConfig
import com.suhel.llamabro.sdk.engine.LlamaSession
import com.suhel.llamabro.sdk.engine.TokenGenerationResult
import com.suhel.llamabro.sdk.engine.TokenGenerationResultCode
import com.suhel.llamabro.sdk.model.ResourceState
import com.suhel.llamabro.sdk.toolcall.ToolCall
import com.suhel.llamabro.sdk.toolcall.ToolResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.channelFlow
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
    override val loadableModel: LoadableModel
) : LlamaSession {
    private val mutex = Mutex()
    private val result = NativeTokenGenerationResult()

    /** Pointer to the native llama_bro_session structure. */
    private val ptr: Long = try {
        Jni.create(
            enginePtr = enginePtr,
            params = NativeCreateParams(
                contextSize = sessionConfig.contextSize,
                threads = loadableModel.loadConfig.threads,
                overflowStrategyId = when (sessionConfig.overflowStrategy) {
                    OverflowStrategy.Halt -> 0
                    OverflowStrategy.ClearHistory -> 1
                    is OverflowStrategy.RollingWindow -> 2
                },
                overflowDropTokens = (sessionConfig.overflowStrategy as? OverflowStrategy.RollingWindow)
                    ?.dropTokens ?: 0,

                repeatPenalty = sessionConfig.inferenceConfig.repeatPenalty,
                frequencyPenalty = sessionConfig.inferenceConfig.frequencyPenalty,
                presencePenalty = sessionConfig.inferenceConfig.presencePenalty,
                penaltyLastN = sessionConfig.inferenceConfig.penaltyLastN,

                dryMultiplier = sessionConfig.inferenceConfig.dryMultiplier,
                dryBase = sessionConfig.inferenceConfig.dryBase,
                dryAllowedLength = sessionConfig.inferenceConfig.dryAllowedLength,
                dryPenaltyLastN = sessionConfig.inferenceConfig.dryPenaltyLastN,

                topNSigma = sessionConfig.inferenceConfig.topNSigma,
                topK = sessionConfig.inferenceConfig.topK,
                typP = sessionConfig.inferenceConfig.typP,
                topP = sessionConfig.inferenceConfig.topP,
                minP = sessionConfig.inferenceConfig.minP,

                temperature = sessionConfig.inferenceConfig.temperature,
                seed = sessionConfig.inferenceConfig.seed,

                batchSize = sessionConfig.decodeConfig.batchSize,
                microBatchSize = sessionConfig.decodeConfig.microBatchSize,
            )
        )
    } catch (e: Exception) {
        throw NativeErrorMapper.map(e)
    }

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
                while (!isDone && currentCoroutineContext().isActive) {
                    val genResult = runInterruptible {
                        Jni.generate(ptr, result)
                        TokenGenerationResult(
                            token = result.token,
                            resultCode = TokenGenerationResultCode.parse(result.resultCode),
                            isComplete = result.isComplete,
                        )
                    }
                    send(genResult)
                    if (genResult.resultCode != TokenGenerationResultCode.OK
                        && genResult.resultCode != TokenGenerationResultCode.CANCELLED
                    ) {
                        throw NativeErrorMapper.fromResultCode(genResult.resultCode)
                    }
                    isDone =
                        genResult.isComplete || genResult.resultCode != TokenGenerationResultCode.OK
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

    override suspend fun createChatSession(
        systemPrompt: String,
        toolCaller: (suspend (List<ToolCall>) -> List<ToolResult>)?,
    ): LlamaChatSession =
        withContext(Dispatchers.IO) {
            LlamaChatSessionImpl(
                session = this@LlamaSessionImpl,
                systemPrompt = systemPrompt,
                toolCaller = toolCaller,
            ).also { it.initialize() }
        }

    override fun createChatSessionFlow(
        systemPrompt: String,
        toolCaller: (suspend (List<ToolCall>) -> List<ToolResult>)?,
    ): Flow<ResourceState<LlamaChatSession>> =
        callbackFlow {
            try {
                send(ResourceState.Loading())
                val session = createChatSession(systemPrompt, toolCaller)
                send(ResourceState.Success(session))
            } catch (e: Exception) {
                send(ResourceState.Failure(NativeErrorMapper.map(e)))
            }

            awaitClose()
        }.flowOn(Dispatchers.IO)

    // ── JNI params ───────────────────────────────────────────────────────────

    private class NativeCreateParams(
        val contextSize: Int,
        val threads: Int,
        val overflowStrategyId: Int,
        val overflowDropTokens: Int,

        val repeatPenalty: Float,
        val frequencyPenalty: Float,
        val presencePenalty: Float,
        val penaltyLastN: Int,

        val dryMultiplier: Float,
        val dryBase: Float,
        val dryAllowedLength: Int,
        val dryPenaltyLastN: Int,

        val topNSigma: Float,
        val topK: Int,
        val typP: Float,
        val topP: Float,
        val minP: Float,

        val temperature: Float,
        val seed: Int,

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
