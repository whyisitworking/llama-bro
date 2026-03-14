package com.suhel.llamabro.demo.ui.screens.chat

import com.suhel.llamabro.demo.model.MessageRole

data class UiChatMessage(
    val id: String,
    val role: MessageRole,
    val isProcessing: Boolean = false,
    val content: String? = null,
    val thinking: String? = null,
    val tokensPerSecond: Float? = null,
    val error: String? = null,
    val timestamp: Long? = null
)
