package com.suhel.llamabro.sdk.internal

import com.suhel.llamabro.sdk.api.ChatSession
import com.suhel.llamabro.sdk.api.ChatSessionConfig
import com.suhel.llamabro.sdk.api.LlamaError
import com.suhel.llamabro.sdk.api.LlamaSession
import com.suhel.llamabro.sdk.api.OverflowStrategy
import com.suhel.llamabro.sdk.api.PromptFormat
import com.suhel.llamabro.sdk.api.PromptFormatter
import com.suhel.llamabro.sdk.api.SessionConfig
import com.suhel.llamabro.sdk.schema.Message
import com.suhel.llamabro.sdk.schema.format

internal class LlamaSessionImpl(
    enginePtr: Long,
    private val promptFormat: PromptFormat,
    sessionConfig: SessionConfig
) : LlamaSession {

    private val promptFormatter = PromptFormatter(promptFormat)
    private val maxNewTokens = sessionConfig.inferenceConfig.maxNewTokens

    private val ptr: Long = try {
        Jni.create(
            enginePtr = enginePtr,
            params = NativeCreateParams(
                contextSize              = sessionConfig.contextSize,
                systemPrompt             = "", // ChatSession handles system prompt injection
                overflowStrategyId       = when (sessionConfig.overflowStrategy) {
                    OverflowStrategy.Halt          -> 0
                    OverflowStrategy.ClearHistory  -> 1
                    is OverflowStrategy.RollingWindow -> 2
                },
                overflowDropTokens       = (sessionConfig.overflowStrategy as? OverflowStrategy.RollingWindow)
                    ?.dropTokens ?: 0,
                topKEnabled              = sessionConfig.inferenceConfig.topK != null,
                topK                     = sessionConfig.inferenceConfig.topK ?: 0,
                topPEnabled              = sessionConfig.inferenceConfig.topP != null,
                topP                     = sessionConfig.inferenceConfig.topP ?: 0f,
                minPEnabled              = sessionConfig.inferenceConfig.minP != null,
                minP                     = sessionConfig.inferenceConfig.minP ?: 0f,
                repPen                   = sessionConfig.inferenceConfig.repeatPenalty,
                temp                     = sessionConfig.inferenceConfig.temperature,
                seed                     = sessionConfig.inferenceConfig.seed,
                batchSize                = sessionConfig.decodeConfig.batchSize,
                microBatchSize           = sessionConfig.decodeConfig.microBatchSize,
                systemPromptReserve      = sessionConfig.decodeConfig.systemPromptReserve,
            )
        )
    } catch (e: RuntimeException) {
        throw mapNativeError(e)
    }

    override fun prompt(message: Message) {
        try {
            Jni.prompt(ptr, message.format(promptFormatter))
        } catch (e: RuntimeException) {
            throw mapNativeError(e)
        }
    }

    override fun generate(): String? {
        return try {
            Jni.generate(ptr)
        } catch (e: RuntimeException) {
            throw mapNativeError(e)
        }
    }

    override fun clear() {
        Jni.clear(ptr)
    }

    override fun createChatSession(config: ChatSessionConfig): ChatSession {
        return ChatSessionImpl(
            rawSession    = this,
            promptFormat  = promptFormat,
            config        = config,
            maxNewTokens  = maxNewTokens,
        )
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

    private object Jni {
        @JvmStatic external fun create(enginePtr: Long, params: NativeCreateParams): Long
        @JvmStatic external fun prompt(sessionPtr: Long, text: String)
        @JvmStatic external fun clear(sessionPtr: Long)
        @JvmStatic external fun generate(sessionPtr: Long): String?
        @JvmStatic external fun destroy(sessionPtr: Long)
    }
}
