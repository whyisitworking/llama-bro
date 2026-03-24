package com.suhel.llamabro.sdk.chat.pipeline

internal sealed interface LexerEvent {
    data class Text(val content: String) : LexerEvent
    data class TagOpened(val marker: FeatureMarker) : LexerEvent
    data class TagContent(val marker: FeatureMarker, val content: String) : LexerEvent
    data class TagClosed(val marker: FeatureMarker) : LexerEvent
}
