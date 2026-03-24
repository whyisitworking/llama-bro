package com.suhel.llamabro.sdk.format

import com.suhel.llamabro.sdk.chat.ChatEvent
import com.suhel.llamabro.sdk.config.ThinkingCapability

class ThinkingDecorator(
    private val thinking: ThinkingCapability
) : PromptDecorator {

    override fun decorateAssistantPart(part: ChatEvent.AssistantEvent.Part): String? {
        if (part !is ChatEvent.AssistantEvent.Part.ThinkingPart) return null
        return buildString {
            append(thinking.tags.open)
            append("\n")
            append(part.content)
            append("\n")
            append(thinking.tags.close)
            append("\n")
        }
    }
}
