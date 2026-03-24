package com.suhel.llamabro.sdk.format

import com.suhel.llamabro.sdk.chat.ChatEvent
import com.suhel.llamabro.sdk.config.ToolCallCapability
import com.suhel.llamabro.sdk.toolcall.ToolDefinition

class ToolCallDecorator(
    private val toolCall: ToolCallCapability,
    private val tools: List<ToolDefinition> = emptyList()
) : PromptDecorator {

    override fun decorateSystem(content: String): String {
        val toolsText = if (tools.isNotEmpty()) {
            toolCall.definitionFormatter(tools)
        } else {
            ""
        }

        if (toolsText.isEmpty()) return content

        return "$content\n$toolsText"
    }

    override fun decorateAssistantPart(part: ChatEvent.AssistantEvent.Part): String? {
        if (part !is ChatEvent.AssistantEvent.Part.ToolCallPart) return null
        return toolCall.callSerializer(part.call)
    }
}
