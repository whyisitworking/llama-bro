package com.suhel.llamabro.sdk.format

import com.suhel.llamabro.sdk.chat.ChatEvent
import com.suhel.llamabro.sdk.config.ThinkingCapability
import kotlin.reflect.KClass

class ThinkingDecorator(
    private val thinking: ThinkingCapability
) : PromptDecorator {

    override val partType: KClass<out ChatEvent.AssistantEvent.Part> =
        ChatEvent.AssistantEvent.Part.ThinkingPart::class

    override fun formatPart(part: ChatEvent.AssistantEvent.Part): String {
        val thinkingPart = part as ChatEvent.AssistantEvent.Part.ThinkingPart
        return buildString {
            appendLine(thinking.tags.open)
            appendLine(thinkingPart.content)
            appendLine(thinking.tags.close)
        }
    }
}
