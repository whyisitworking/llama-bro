package com.suhel.llamabro.sdk.engine.internal

import com.suhel.llamabro.sdk.chat.ChatMessage
import com.suhel.llamabro.sdk.config.InferenceConfig
import com.suhel.llamabro.sdk.config.LoadableModel
import com.suhel.llamabro.sdk.config.OverflowStrategy
import com.suhel.llamabro.sdk.config.SessionConfig
import com.suhel.llamabro.sdk.engine.LlamaSession
import com.suhel.llamabro.sdk.engine.TokenGenerationResult
import com.suhel.llamabro.sdk.engine.TokenGenerationResultCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
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
                inferenceParams = sessionConfig.inferenceConfig.toNativeParams(),
                batchSize = sessionConfig.decodeConfig.batchSize,
                microBatchSize = sessionConfig.decodeConfig.microBatchSize,
            )
        )
    } catch (e: Exception) {
        throw NativeErrorMapper.map(e)
    }

    override suspend fun initChatTemplates(): NativeChatTemplateInfo =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                runInterruptible {
                    Jni.initChatTemplates(ptr)
                }
            }
        }

    override suspend fun beginCompletion(
        messages: List<ChatMessage>,
        enableThinking: Boolean,
    ): NativeCompletionInfo =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                runInterruptible {
                    // Build parallel arrays for JNI
                    val roles = Array(messages.size) { messages[it].role }
                    val contents = Array(messages.size) { messages[it].content }
                    val reasoningContents = Array(messages.size) { messages[it].reasoningContent ?: "" }

                    // Only pass reasoning array if any message has reasoning content
                    val hasReasoning = reasoningContents.any { it.isNotEmpty() }

                    Jni.beginCompletion(
                        sessionPtr = ptr,
                        roles = roles,
                        contents = contents,
                        reasoningContents = if (hasReasoning) reasoningContents else null,
                        enableThinking = enableThinking,
                    )
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

    override suspend fun updateSampler(config: InferenceConfig) =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                runInterruptible {
                    Jni.updateSampler(ptr, config.toNativeParams())
                }
            }
        }

    override fun close() {
        Jni.destroy(ptr)
    }

    // ── JNI params ───────────────────────────────────────────────────────────

    private class NativeCreateParams(
        val contextSize: Int,
        val threads: Int,
        val overflowStrategyId: Int,
        val overflowDropTokens: Int,
        val inferenceParams: NativeInferenceParams,
        val batchSize: Int,
        val microBatchSize: Int,
    )

    /**
     * Inference-only params passed to the native sampler — either as a sub-object of
     * [NativeCreateParams] at session creation, or standalone for [Jni.updateSampler].
     */
    private class NativeInferenceParams(
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
    )

    private fun InferenceConfig.toNativeParams() = NativeInferenceParams(
        repeatPenalty = repeatPenalty,
        frequencyPenalty = frequencyPenalty,
        presencePenalty = presencePenalty,
        penaltyLastN = penaltyLastN,
        dryMultiplier = dryMultiplier,
        dryBase = dryBase,
        dryAllowedLength = dryAllowedLength,
        dryPenaltyLastN = dryPenaltyLastN,
        topNSigma = topNSigma,
        topK = topK,
        typP = typP,
        topP = topP,
        minP = minP,
        temperature = temperature,
        seed = seed,
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
        external fun initChatTemplates(sessionPtr: Long): NativeChatTemplateInfo

        @JvmStatic
        external fun beginCompletion(
            sessionPtr: Long,
            roles: Array<String>,
            contents: Array<String>,
            reasoningContents: Array<String>?,
            enableThinking: Boolean,
        ): NativeCompletionInfo

        @JvmStatic
        external fun clear(sessionPtr: Long)

        @JvmStatic
        external fun abort(sessionPtr: Long)

        @JvmStatic
        external fun generate(sessionPtr: Long, result: NativeTokenGenerationResult)

        @JvmStatic
        external fun updateSampler(sessionPtr: Long, params: NativeInferenceParams)

        @JvmStatic
        external fun destroy(sessionPtr: Long)
    }
}
