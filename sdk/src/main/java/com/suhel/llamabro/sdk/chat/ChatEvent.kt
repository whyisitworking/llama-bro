package com.suhel.llamabro.sdk.chat

import com.suhel.llamabro.sdk.toolcall.ToolCall
import com.suhel.llamabro.sdk.toolcall.ToolDefinition
import com.suhel.llamabro.sdk.toolcall.ToolResult
import kotlinx.serialization.Serializable

@Serializable
sealed interface ChatEvent {

    @Serializable
    data class SystemEvent(
        val content: String,
        val tools: List<ToolDefinition> = emptyList(),
    ) : ChatEvent

    @Serializable
    data class UserEvent(
        val content: String,
        val think: Boolean,
    ) : ChatEvent

    @Serializable
    data class AssistantEvent(
        val parts: List<Part>,
    ) : ChatEvent {
        val text: String
            get() = parts.filterIsInstance<Part.TextPart>().joinToString("") { it.content }

        val thinkingText: String
            get() = parts.filterIsInstance<Part.ThinkingPart>().joinToString("") { it.content }

        val toolCalls: List<ToolCall>
            get() = parts.filterIsInstance<Part.ToolCallPart>().map { it.call }

        @Serializable
        sealed interface Part {
            @Serializable
            data class TextPart(val content: String) : Part
            @Serializable
            data class ThinkingPart(val content: String) : Part
            @Serializable
            data class ToolCallPart(val call: ToolCall) : Part
        }
    }

    @Serializable
    data class ToolResultEvent(
        val result: ToolResult,
    ) : ChatEvent
}