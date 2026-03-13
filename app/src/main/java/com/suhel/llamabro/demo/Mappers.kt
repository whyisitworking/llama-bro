package com.suhel.llamabro.demo

import com.suhel.llamabro.demo.data.db.entity.ConversationEntity
import com.suhel.llamabro.demo.data.db.entity.MessageEntity
import com.suhel.llamabro.demo.model.ChatMessage
import com.suhel.llamabro.demo.model.Conversation
import com.suhel.llamabro.demo.model.MessageRole

fun ConversationEntity.toDomain(): Conversation =
    Conversation(
        id = this.id,
        title = this.title
    )

fun MessageEntity.toDomain(): ChatMessage =
    ChatMessage(
        id = this.id,
        role = this.role.asMessageRole(),
        content = this.content,
        thinking = this.thinking,
        tokensPerSecond = this.tokensPerSecond,
        timestamp = this.createdAt
    )

fun MessageRole.toRaw(): String = when (this) {
    MessageRole.User -> "user"
    MessageRole.Assistant -> "assistant"
}

fun String.asMessageRole(): MessageRole = when (this) {
    "user" -> MessageRole.User
    "assistant" -> MessageRole.Assistant
    else -> throw IllegalArgumentException("Invalid message role: $this")
}
