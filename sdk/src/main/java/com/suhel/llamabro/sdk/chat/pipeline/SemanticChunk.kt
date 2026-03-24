package com.suhel.llamabro.sdk.chat.pipeline

internal sealed interface SemanticChunk {
    data class Text(val content: String) : SemanticChunk
    data class Thinking(val content: String) : SemanticChunk
    data class ToolCallContent(val content: String) : SemanticChunk
    data object ToolCallComplete : SemanticChunk
}
