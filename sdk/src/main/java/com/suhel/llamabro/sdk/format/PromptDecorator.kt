package com.suhel.llamabro.sdk.format

import com.suhel.llamabro.sdk.chat.ChatEvent

/**
 * A capability decorator injects template prefixes for prompts based on features.
 * Used by PromptFormatter to decouple formatting logic.
 */
interface PromptDecorator {
    fun decorateSystem(content: String): String = content
    
    // Allows decorators to format their specific Part types
    fun decorateAssistantPart(part: ChatEvent.AssistantEvent.Part): String? = null
}
