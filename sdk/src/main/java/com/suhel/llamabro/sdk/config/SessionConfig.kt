package com.suhel.llamabro.sdk.config

import kotlin.random.Random

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
    val repeatPenalty: Float = 1.05f,
    val frequencyPenalty: Float = 0.0f,
    val presencePenalty: Float = 0.0f,
    val penaltyLastN: Int = 128,

    val dryMultiplier: Float = 0.0f,
    val dryBase: Float = 1.75f,
    val dryAllowedLength: Int = 2,
    val dryPenaltyLastN: Int = 128,

    val topNSigma: Float = 0.0f,
    val topK: Int = 40,
    val typP: Float = 1.0f,
    val topP: Float = 0.95f,
    val minP: Float = 0.0f,

    val temperature: Float = 0.8f,
    val seed: Int = Random.nextInt(),
)

sealed interface OverflowStrategy {
    data object Halt : OverflowStrategy
    data object ClearHistory : OverflowStrategy
    data class RollingWindow(val dropTokens: Int = 500) : OverflowStrategy
}
