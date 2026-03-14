package com.suhel.llamabro.demo.model

data class ChatMessage(
    val id: String,
    val role: MessageRole,
    val content: String,
    val thinking: String? = null,
    val tokensPerSecond: Float? = null,
    val error: String? = null,
    val timestamp: Long? = null
)
