package com.suhel.llamabro.sdk.model

data class InferenceConfig(
    val temperature: Float = 0.8f,
    val repeatPenalty: Float = 1.1f,
    val minP: Float? = 0.1f,
    val topP: Float? = null,
    val topK: Int? = null,
)
