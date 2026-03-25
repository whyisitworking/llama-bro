package com.suhel.llamabro.sdk.format

import com.suhel.llamabro.sdk.chat.ChatEvent
import com.suhel.llamabro.sdk.config.ToolCallCapability
import com.suhel.llamabro.sdk.toolcall.ToolDefinition
import kotlin.reflect.KClass

class ToolCallDecorator(
    private val toolCall: ToolCallCapability,
    private val tools: List<ToolDefinition> = emptyList()
) : PromptDecorator {

    override val partType: KClass<out ChatEvent.AssistantEvent.Part> =
        ChatEvent.AssistantEvent.Part.ToolCallPart::class

    override fun decorateSystem(): String? =
        tools.takeIf { it.isNotEmpty() }
            ?.let { tools -> toolCall.definitionFormatter(tools) }

    override fun formatPart(part: ChatEvent.AssistantEvent.Part): String {
        val toolCallPart = part as ChatEvent.AssistantEvent.Part.ToolCallPart
        return toolCall.callSerializer(toolCallPart.call)
    }
}
