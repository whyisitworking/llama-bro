package com.suhel.llamabro.sdk.chat.pipeline

import com.suhel.llamabro.sdk.toolcall.ToolCall

internal sealed interface SemanticChunk {
    data class Text(val content: String) : SemanticChunk
    data class Thinking(val content: String) : SemanticChunk
    data class ToolCall(val call: com.suhel.llamabro.sdk.toolcall.ToolCall) : SemanticChunk
}
