package com.suhel.llamabro.demo.navigation

import kotlinx.serialization.Serializable

@Serializable
data object ModelSelection

@Serializable
data object Conversations

@Serializable
data class Chat(val conversationId: String? = null)