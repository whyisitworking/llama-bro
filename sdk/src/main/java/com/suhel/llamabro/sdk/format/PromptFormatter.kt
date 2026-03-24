package com.suhel.llamabro.sdk.format

import com.suhel.llamabro.sdk.config.ModelProfile
import com.suhel.llamabro.sdk.config.ThinkingStrategy
import com.suhel.llamabro.sdk.chat.ChatEvent

internal class PromptFormatter(
    private val profile: ModelProfile,
    private val decorators: List<PromptDecorator> = emptyList()
) {
    private val template = profile.promptFormat

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
        val thinking = profile.thinking
        var content = event.content
        var assistantSuffix = ""

        if (thinking != null) {
            val strategy = thinking.strategy
            when {
                event.think -> when (strategy) {
                    is ThinkingStrategy.SoftSwitch -> {
                        content += "\n${strategy.enableDirective}"
                    }
                    is ThinkingStrategy.Prefill -> {
                        assistantSuffix = strategy.forcePrefix
                    }
                    is ThinkingStrategy.None -> { /* no manipulation */ }
                }
                else -> when (strategy) {
                    is ThinkingStrategy.SoftSwitch -> {
                        content += "\n${strategy.disableDirective}"
                    }
                    is ThinkingStrategy.Prefill -> {
                        assistantSuffix = strategy.suppressPrefix
                    }
                    is ThinkingStrategy.None -> { /* no manipulation */ }
                }
            }
        }

        return buildString {
            append(template.userPrefix)
            append(content)
            append(template.endOfTurn)

            if (template.emitAssistantPrefixOnGeneration) {
                append(template.assistantPrefix)
            }

            if (assistantSuffix.isNotEmpty()) {
                append(assistantSuffix)
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
