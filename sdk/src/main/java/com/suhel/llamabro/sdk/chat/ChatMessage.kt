package com.suhel.llamabro.sdk.chat

/**
 * A single message in a chat conversation, mirroring the OpenAI Chat Completion API.
 *
 * @param role The role of the message author: "system", "user", "assistant", or "tool".
 * @param content The text content of the message.
 * @param reasoningContent Optional reasoning/thinking content for assistant messages.
 *   When provided, the Jinja template wraps this in thinking tags automatically.
 */
data class ChatMessage(
    val role: String,
    val content: String,
    val reasoningContent: String? = null,
)
