package com.suhel.llamabro.sdk.internal

internal sealed interface StreamAction {
    data class Content(val text: String) : StreamAction
    data class Thinking(val text: String) : StreamAction
    data object Stop : StreamAction // Emitted when the assistantSuffix is intercepted
}
