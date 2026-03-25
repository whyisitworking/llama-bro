package com.suhel.llamabro.sdk.format

import com.suhel.llamabro.sdk.chat.ChatEvent
import com.suhel.llamabro.sdk.config.ModelProfile
import com.suhel.llamabro.sdk.config.ThinkingStrategy
import kotlin.reflect.KClass

/**
 * Serializes [ChatEvent]s into the raw prompt strings expected by the model.
 *
 * Two formatting modes reflect distinct intents:
 * - [formatHistory] — formats a completed turn for replay (role prefix + content + endOfTurn).
 * - [formatGeneration] — formats a user turn to kick off generation (includes assistant prefix
 *   and any thinking-strategy prefill).
 *
 * Decorator dispatch uses a pre-built O(1) map keyed by [ChatEvent.AssistantEvent.Part] type.
 */
internal class PromptFormatter(
    private val profile: ModelProfile,
    private val decorators: List<PromptDecorator> = emptyList()
) {
    private val template = profile.promptFormat

    /** Pre-computed system decorations — stable for the lifetime of this formatter. */
    private val cachedSystemDecorations: String = buildString {
        for (decorator in decorators) {
            decorator.decorateSystem()?.let {
                append('\n')
                append(it)
            }
        }
    }

    /** O(1) lookup: Part type → ordered list of decorators that handle it. */
    private val partFormatters: Map<KClass<out ChatEvent.AssistantEvent.Part>, List<PromptDecorator>> =
        decorators
            .filter { it.partType != null }
            .groupBy { it.partType!! }

    // ── System ───────────────────────────────────────────────────────────────

    fun formatSystem(event: ChatEvent.SystemEvent): String = buildString {
        append(template.systemPrefix)
        append(event.content)
        append(cachedSystemDecorations)
        append(template.endOfTurn)
    }

    // ── History (complete turns for replay) ──────────────────────────────────

    /**
     * Formats any historical [ChatEvent] as a complete turn.
     * Never appends another role's prefix — each turn is self-contained.
     */
    fun formatHistory(event: ChatEvent): String = when (event) {
        is ChatEvent.SystemEvent -> formatSystem(event)
        is ChatEvent.UserEvent -> formatHistoryUser(event)
        is ChatEvent.AssistantEvent -> formatHistoryAssistant(event)
        is ChatEvent.ToolResultEvent -> formatHistoryToolResult(event)
    }

    private fun formatHistoryUser(event: ChatEvent.UserEvent): String = buildString {
        append(template.userPrefix)
        append(event.content)
        append(template.endOfTurn)
    }

    private fun formatHistoryAssistant(event: ChatEvent.AssistantEvent): String = buildString {
        append(template.assistantPrefix)
        formatParts(this, event.parts)
        append(template.endOfTurn)
    }

    private fun formatHistoryToolResult(event: ChatEvent.ToolResultEvent): String = buildString {
        append(template.userPrefix)
        val serializer = profile.toolCall?.resultSerializer
        append(serializer?.invoke(event.result) ?: event.result.toString())
        append(template.endOfTurn)
    }

    // ── Generation (user turn + assistant prime) ─────────────────────────────

    /**
     * Formats a [ChatEvent.UserEvent] for immediate generation.
     * Appends the assistant prefix and any thinking-strategy manipulations.
     */
    fun formatGeneration(event: ChatEvent.UserEvent): String = buildString {
        append(template.userPrefix)
        append(event.content)

        // SoftSwitch: append directive to user content
        val thinking = profile.thinking
        if (thinking != null) {
            when (val strategy = thinking.strategy) {
                is ThinkingStrategy.SoftSwitch -> {
                    append('\n')
                    append(if (event.think) strategy.enableDirective else strategy.disableDirective)
                }

                is ThinkingStrategy.Prefill,
                is ThinkingStrategy.None -> { /* handled after assistant prefix */ }
            }
        }

        append(template.endOfTurn)
        append(template.assistantPrefix)

        // Prefill: insert after assistant prefix
        if (thinking != null) {
            when (val strategy = thinking.strategy) {
                is ThinkingStrategy.Prefill -> {
                    append(if (event.think) strategy.forcePrefix else strategy.suppressPrefix)
                }

                is ThinkingStrategy.SoftSwitch,
                is ThinkingStrategy.None -> { /* already handled or nothing to do */ }
            }
        }
    }

    // ── Part formatting (O(1) dispatch) ──────────────────────────────────────

    private fun formatParts(sb: StringBuilder, parts: List<ChatEvent.AssistantEvent.Part>) {
        for (part in parts) {
            val formatters = partFormatters[part::class]
            if (formatters != null) {
                for (f in formatters) sb.append(f.formatPart(part))
            } else if (part is ChatEvent.AssistantEvent.Part.TextPart) {
                sb.append(part.content)
            }
        }
    }
}
