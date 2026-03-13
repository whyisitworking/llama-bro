package com.suhel.llamabro.sdk.model

data class SessionConfig(
    val systemPrompt: String,
    val contextSize: Int = 4096,
    val overflowStrategy: OverflowStrategy = OverflowStrategy.RollingWindow(),
    val inferenceConfig: InferenceConfig = InferenceConfig(),
    val decodeConfig: DecodeConfig = DecodeConfig(),
    val seed: Int = -1,
)