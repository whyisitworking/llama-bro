package com.suhel.llamabro.sdk.format

import com.suhel.llamabro.sdk.config.ModelDefinition
import com.suhel.llamabro.sdk.models.ChatEvent
import com.suhel.llamabro.sdk.chat.pipeline.ThinkingMarker

internal class PromptFormatter(
    private val modelDefinition: ModelDefinition,
    private val decorators: List<PromptDecorator> = emptyList()
) {
    private val template = modelDefinition.promptFormat

    /**
     * Governs the exact handshake strings required to turn a semantic chat event into a raw prompt.
     */
    fun formatTurn(event: ChatEvent): String {
        return when (event) {
            is ChatEvent.SystemEvent -> formatSystem(event)
            is ChatEvent.UserEvent -> formatUserTurnStart(event)
            is ChatEvent.AssistantEvent -> formatAssistant(event)
            is ChatEvent.ToolResultEvent -> formatToolResult(event)
        }
    }

    private fun formatSystem(event: ChatEvent.SystemEvent): String {
        var content = event.content
        for (decorator in decorators) {
            content = decorator.decorateSystem(content)
        }
        return buildString {
            append(template.systemPrefix)
            append(content)
            append(template.endOfTurn)
        }
    }

    private fun formatUserTurnStart(event: ChatEvent.UserEvent): String {
        return buildString {
            append(template.userPrefix)
            append(event.content)
            append(template.endOfTurn)

            if (template.emitAssistantPrefixOnGeneration) {
                append(template.assistantPrefix)
            }

            if (event.think) {
                val thinkingMarker = modelDefinition.features.filterIsInstance<ThinkingMarker>().firstOrNull()
                if (thinkingMarker != null) {
                    append(thinkingMarker.open)
                    append("\n")
                }
            }
        }
    }

    private fun formatAssistant(event: ChatEvent.AssistantEvent): String {
        return buildString {
            if (!template.emitAssistantPrefixOnGeneration) {
                append(template.assistantPrefix)
            }

            for (part in event.parts) {
                var decorated: String? = null
                for (decorator in decorators) {
                    val d = decorator.decorateAssistantPart(part)
                    if (d != null) {
                        decorated = d
                        break
                    }
                }
                if (decorated != null) {
                    append(decorated)
                } else if (part is ChatEvent.AssistantEvent.Part.TextPart) {
                    append(part.content)
                }
            }
            append(template.endOfTurn)
        }
    }

    private fun formatToolResult(event: ChatEvent.ToolResultEvent): String {
        return buildString {
            append(template.userPrefix)
            append(event.result.toString())
            append(template.endOfTurn)
        }
    }
}
