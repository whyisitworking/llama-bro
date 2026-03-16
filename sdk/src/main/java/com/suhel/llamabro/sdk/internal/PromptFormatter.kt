package com.suhel.llamabro.sdk.internal

import com.suhel.llamabro.sdk.model.Message
import com.suhel.llamabro.sdk.model.PromptFormat

/**
 * Internal utility to wrap raw message text into model-specific chat templates.
 *
 * This class ensures that System, User, and Assistant messages are prefixed 
 * and suffixed correctly according to the [PromptFormat] provided by the model.
 */
internal class PromptFormatter(private val formatter: PromptFormat) {
    /** Beginning of stream token. */
    fun bos(): String = formatter.bos ?: ""

    /** End of stream token. */
    fun eos(): String = formatter.eos ?: ""

    /** Formats a single user message. */
    fun user(text: String): String =
        "${formatter.userPrefix}$text${formatter.userSuffix}"

    /** Formats a single system instruction. */
    fun system(text: String): String =
        "${formatter.systemPrefix}$text${formatter.systemSuffix}"

    /** 
     * Formats an assistant message. 
     * If [text] is null, only the prefix is returned (used to start generation).
     */
    fun assistant(text: String?): String =
        if (text != null) {
            "${formatter.assistantPrefix}$text${formatter.assistantSuffix}${eos()}"
        } else {
            formatter.assistantPrefix
        }

    /** Dispatches formatting based on the message role. */
    fun format(message: Message): String =
        when (message) {
            is Message.User -> user(message.content)
            is Message.Assistant -> assistant(message.content)
        }
}
