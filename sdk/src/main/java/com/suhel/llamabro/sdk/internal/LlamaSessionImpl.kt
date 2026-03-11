package com.suhel.llamabro.sdk.internal

import com.suhel.llamabro.sdk.api.LlamaSession
import com.suhel.llamabro.sdk.api.OverflowStrategy
import com.suhel.llamabro.sdk.api.PromptFormat
import com.suhel.llamabro.sdk.api.PromptFormatter
import com.suhel.llamabro.sdk.api.SessionConfig
import com.suhel.llamabro.sdk.schema.Message
import com.suhel.llamabro.sdk.schema.format

internal class LlamaSessionImpl(
    enginePtr: Long,
    promptFormat: PromptFormat,
    sessionConfig: SessionConfig
) : LlamaSession {
    private val promptFormatter = PromptFormatter(promptFormat)

    private val ptr = Jni.create(
        enginePtr = enginePtr,
        params = NativeCreateParams(
            contextSize = sessionConfig.contextSize,
            systemPrompt = sessionConfig.systemPrompt?.let(promptFormatter::system).orEmpty(),
            overflowStrategyId = when (sessionConfig.overflowStrategy) {
                OverflowStrategy.Halt -> 0
                OverflowStrategy.ClearHistory -> 1
                is OverflowStrategy.RollingWindow -> 2
            },
            overflowDropTokens = (sessionConfig.overflowStrategy as? OverflowStrategy.RollingWindow)?.dropTokens
                ?: 0,
            topKEnabled = sessionConfig.inferenceConfig.topK != null,
            topK = sessionConfig.inferenceConfig.topK ?: 0,
            topPEnabled = sessionConfig.inferenceConfig.topP != null,
            topP = sessionConfig.inferenceConfig.topP ?: 0f,
            minPEnabled = sessionConfig.inferenceConfig.minP != null,
            minP = sessionConfig.inferenceConfig.minP ?: 0f,
            repPenEnabled = sessionConfig.inferenceConfig.repeatPenalty != null,
            repPen = sessionConfig.inferenceConfig.repeatPenalty ?: 0f,
            tempEnabled = sessionConfig.inferenceConfig.temperature != null,
            temp = sessionConfig.inferenceConfig.temperature ?: 0f,
            seed = sessionConfig.inferenceConfig.seed,
        )
    )

    override fun prompt(message: Message) {
        Jni.prompt(ptr, message.format(promptFormatter))
    }

    override fun clear() {
        Jni.clear(ptr)
    }

    override fun generate(): String? {
        return Jni.generate(ptr)
    }

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
        val repPenEnabled: Boolean,
        val repPen: Float,
        val tempEnabled: Boolean,
        val temp: Float,
        val seed: Int
    )

    override fun close() {
        Jni.destroy(ptr)
    }

    private object Jni {
        @JvmStatic
        external fun create(enginePtr: Long, params: NativeCreateParams): Long

        @JvmStatic
        external fun prompt(sessionPtr: Long, text: String)

        @JvmStatic
        external fun clear(sessionPtr: Long)

        @JvmStatic
        external fun generate(sessionPtr: Long): String?

        @JvmStatic
        external fun destroy(sessionPtr: Long)
    }
}
