package com.suhel.llamabro.sdk.format

import com.suhel.llamabro.sdk.models.ChatEvent
import com.suhel.llamabro.sdk.toolcall.ToolDefinition
import com.suhel.llamabro.sdk.toolcall.ToolCallDefinition

class ToolCallDecorator(
    private val toolCallDefinition: ToolCallDefinition,
    private val tools: List<ToolDefinition> = emptyList()
) : PromptDecorator {

    override fun decorateSystem(content: String): String {
        val toolsText = if (tools.isNotEmpty()) {
            toolCallDefinition.definitionFormatter(tools)
        } else {
            ""
        }

        if (toolsText.isEmpty()) return content

        return "$content\n$toolsText"
    }

    override fun decorateAssistantPart(part: ChatEvent.AssistantEvent.Part): String? {
        if (part !is ChatEvent.AssistantEvent.Part.ToolCallPart) return null
        return toolCallDefinition.callSerializer(part.call)
    }
}
