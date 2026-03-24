package com.suhel.llamabro.sdk.format

import com.suhel.llamabro.sdk.models.ChatEvent
import com.suhel.llamabro.sdk.chat.pipeline.ThinkingMarker

class ThinkingDecorator(
    private val thinking: ThinkingMarker
) : PromptDecorator {

    override fun decorateAssistantPart(part: ChatEvent.AssistantEvent.Part): String? {
        if (part !is ChatEvent.AssistantEvent.Part.ThinkingPart) return null
        return buildString {
            append(thinking.open)
            append("\n")
            append(part.content)
            append("\n")
            append(thinking.close)
            append("\n")
        }
    }
}
