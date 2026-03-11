package com.suhel.llamabro.sdk.api

import com.suhel.llamabro.sdk.schema.Message

interface LlamaSession : AutoCloseable {
    fun prompt(message: Message)

    fun clear()

    fun generate(): String?
}

data class SessionConfig(
    val contextSize: Int,
    val systemPrompt: String?,
    val overflowStrategy: OverflowStrategy,
    val inferenceConfig: InferenceConfig
)

data class InferenceConfig(
    val temperature: Float?,
    val topK: Int?,
    val repeatPenalty: Float?,
    val topP: Float?,
    val minP: Float?,
    val seed: Int
)
