package com.suhel.llamabro.sdk.schema

import com.suhel.llamabro.sdk.api.PromptFormatter

sealed interface Message {
    val content: String

    data class User(override val content: String) : Message
    data class Assistant(override val content: String) : Message
}

fun Message.format(promptFormatter: PromptFormatter): String {
    return when (this) {
        is Message.User -> promptFormatter.user(content)
        is Message.Assistant -> promptFormatter.assistant(content)
    }
}
