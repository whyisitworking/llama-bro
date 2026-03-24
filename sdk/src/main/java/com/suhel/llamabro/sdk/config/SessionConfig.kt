package com.suhel.llamabro.sdk.config

data class SessionConfig(
    val contextSize: Int = 2048,
    val overflowStrategy: OverflowStrategy = OverflowStrategy.RollingWindow(),
    val inferenceConfig: InferenceConfig = InferenceConfig(),
    val decodeConfig: DecodeConfig = DecodeConfig(),
    val seed: Int = -1,
)

data class DecodeConfig(
    val batchSize: Int = 2048,
    val microBatchSize: Int = 512,
) {
    init {
        require(batchSize >= microBatchSize) {
            "batchSize ($batchSize) must be >= microBatchSize ($microBatchSize)"
        }
    }
}

data class InferenceConfig(
    val temperature: Float = 0.8f,
    val repeatPenalty: Float = 1.0f,
    val presencePenalty: Float = 0.0f,
    val minP: Float? = 0.1f,
    val topP: Float? = null,
    val topK: Int? = null,
)

sealed interface OverflowStrategy {
    data object Halt : OverflowStrategy
    data object ClearHistory : OverflowStrategy
    data class RollingWindow(val dropTokens: Int = 500) : OverflowStrategy
}
