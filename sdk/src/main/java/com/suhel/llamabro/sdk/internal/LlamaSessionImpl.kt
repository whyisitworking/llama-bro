package com.suhel.llamabro.sdk.internal

import com.suhel.llamabro.sdk.LlamaSession
import com.suhel.llamabro.sdk.model.Message
import com.suhel.llamabro.sdk.model.OverflowStrategy
import com.suhel.llamabro.sdk.model.PromptFormat
import com.suhel.llamabro.sdk.model.SessionConfig
import com.suhel.llamabro.sdk.util.PromptFormatter
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class LlamaSessionImpl(
    enginePtr: Long,
    promptFormat: PromptFormat,
    sessionConfig: SessionConfig
) : LlamaSession {

    private val promptFormatter = PromptFormatter(promptFormat)
    private val mutex = Mutex()

    private val ptr: Long = try {
        Jni.create(
            enginePtr = enginePtr,
            params = NativeCreateParams(
                contextSize = sessionConfig.contextSize,
                systemPrompt = sessionConfig.systemPrompt,
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
                temp = sessionConfig.inferenceConfig.temperature,
                seed = sessionConfig.seed,
                batchSize = sessionConfig.decodeConfig.batchSize,
                microBatchSize = sessionConfig.decodeConfig.microBatchSize,
                systemPromptReserve = sessionConfig.decodeConfig.systemPromptReserve,
            )
        )
    } catch (e: RuntimeException) {
        throw mapNativeError(e)
    }

    override suspend fun prompt(message: Message) {
        mutex.withLock {
            try {
                runInterruptible {
                    Jni.prompt(ptr, promptFormatter.format(message))
                }
            } catch (e: RuntimeException) {
                throw mapNativeError(e)
            }
        }
    }

    override suspend fun generate(): String? {
        return mutex.withLock {
            try {
                runInterruptible {
                    Jni.generate(ptr)
                }
            } catch (e: RuntimeException) {
                throw mapNativeError(e)
            }
        }
    }

    override fun clear() {
        Jni.clear(ptr)
    }

    override fun abort() {
        Jni.abort(ptr)
    }

    override fun close() {
        Jni.destroy(ptr)
    }

    // ── JNI params ───────────────────────────────────────────────────────────

    class NativeCreateParams(
        val contextSize: Int,
        val systemPrompt: String,
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
        val temp: Float,
        val seed: Int,
        // Decode tuning
        val batchSize: Int,
        val microBatchSize: Int,
        val systemPromptReserve: Int,
    )

    object Jni {
        @JvmStatic
        external fun create(enginePtr: Long, params: NativeCreateParams): Long

        @JvmStatic
        external fun prompt(sessionPtr: Long, text: String)

        @JvmStatic
        external fun clear(sessionPtr: Long)

        @JvmStatic
        external fun abort(sessionPtr: Long)

        @JvmStatic
        external fun generate(sessionPtr: Long): String?

        @JvmStatic
        external fun destroy(sessionPtr: Long)
    }
}
