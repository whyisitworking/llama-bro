package com.suhel.llamabro.sdk.model

data class ChatGeneration(
    val thinkingText: String? = null,
    val contentText: String? = null,
    val tokensPerSecond: Float? = null,
    val error: LlamaError? = null,
    val isComplete: Boolean = false,
)
