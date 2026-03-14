package com.suhel.llamabro.sdk.internal

import com.suhel.llamabro.sdk.model.Message
import com.suhel.llamabro.sdk.model.PromptFormat

internal class PromptFormatter(private val formatter: PromptFormat) {
    fun user(text: String): String =
        "${formatter.userPrefix}$text${formatter.userSuffix}"

    fun system(text: String): String =
        "${formatter.systemPrefix}$text${formatter.systemSuffix}"

    fun assistant(text: String?): String =
        if (text != null) {
            "${formatter.assistantPrefix}$text${formatter.assistantSuffix}"
        } else {
            formatter.assistantPrefix
        }

    fun format(message: Message): String =
        when (message) {
            is Message.User -> user(message.content)
            is Message.Assistant -> assistant(message.content)
        }
}