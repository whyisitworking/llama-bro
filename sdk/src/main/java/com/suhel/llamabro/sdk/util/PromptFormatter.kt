package com.suhel.llamabro.sdk.util

import com.suhel.llamabro.sdk.model.Message
import com.suhel.llamabro.sdk.model.PromptFormat

class PromptFormatter(private val formatter: PromptFormat) {
    fun user(text: String): String =
        "${formatter.userPrefix}$text${formatter.userSuffix}"

    fun system(text: String): String =
        "${formatter.systemPrefix}$text${formatter.systemSuffix}"

    fun assistant(text: String): String =
        "${formatter.assistantPrefix}$text${formatter.assistantSuffix}"

    fun format(message: Message): String {
        return when (message) {
            is Message.User -> user(message.content)
            is Message.Assistant -> assistant(message.content)
        }
    }
}
