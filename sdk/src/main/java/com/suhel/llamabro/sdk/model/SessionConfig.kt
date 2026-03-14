package com.suhel.llamabro.sdk.model

/**
 * Configuration for creating an inference session.
 *
 * @param contextSize      Context window size in tokens. Default: 4096.
 * @param overflowStrategy How to handle context overflow. Default: [OverflowStrategy.RollingWindow].
 * @param inferenceConfig  Sampling parameters (temperature, penalties, etc.).
 * @param decodeConfig     Low-level llama.cpp tuning parameters.
 * @param seed             RNG seed for reproducible sampling. -1 for random. Default: -1.
 */
data class SessionConfig(
    val contextSize: Int = 4096,
    val overflowStrategy: OverflowStrategy = OverflowStrategy.RollingWindow(),
    val inferenceConfig: InferenceConfig = InferenceConfig(),
    val decodeConfig: DecodeConfig = DecodeConfig(),
    val seed: Int = -1,
)