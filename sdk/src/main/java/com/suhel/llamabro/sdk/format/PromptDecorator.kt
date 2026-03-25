package com.suhel.llamabro.sdk.format

import com.suhel.llamabro.sdk.chat.ChatEvent
import kotlin.reflect.KClass

/**
 * Decorators extend prompt formatting with capability-specific logic.
 *
 * Each decorator optionally:
 * - Appends content to the system prompt via [decorateSystem].
 * - Declares which [ChatEvent.AssistantEvent.Part] type it handles via [partType],
 *   and formats it via [formatPart]. The [PromptFormatter] builds an O(1) lookup
 *   map from these declarations at construction time.
 */
interface PromptDecorator {
    /** Content to append to the system prompt, or null if nothing to add. */
    fun decorateSystem(): String? = null

    /** The [Part] subtype this decorator handles, or null if it handles no parts. */
    val partType: KClass<out ChatEvent.AssistantEvent.Part>?

    /** Format the given [part] into its prompt-serialized form. */
    fun formatPart(part: ChatEvent.AssistantEvent.Part): String = ""
}
