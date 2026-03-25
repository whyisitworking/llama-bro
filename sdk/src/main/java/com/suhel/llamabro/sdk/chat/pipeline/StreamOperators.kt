package com.suhel.llamabro.sdk.chat.pipeline

import com.suhel.llamabro.sdk.config.ModelProfile
import com.suhel.llamabro.sdk.engine.TokenGenerationResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.transform

/**
 * 1. Syntax Layer: Parses raw token strings into [LexerEvent]s via DFA scanning.
 */
internal fun Flow<TokenGenerationResult>.lexTags(delimiters: List<TagDelimiter>): Flow<LexerEvent> {
    val scanner = AllocationOptimizedScanner(delimiters)
    return transform { result ->
        scanner.feed(result.token) { event -> emit(event) }
    }
}

/**
 * 2. Semantic Layer: Maps [LexerEvent]s to [SemanticChunk]s using the model profile.
 *
 * Tool call content is buffered internally and emitted as a fully-parsed
 * [SemanticChunk.ToolCall] on tag close — consumers never see raw tool call fragments.
 */
internal fun Flow<LexerEvent>.semanticChunks(profile: ModelProfile): Flow<SemanticChunk> {
    val toolCall = profile.toolCall
    val toolCallBuffer = StringBuilder()
    return transform { event ->
        when (event) {
            is LexerEvent.Text -> emit(SemanticChunk.Text(event.content))
            is LexerEvent.TagContent -> when (event.delimiter) {
                profile.thinking?.tags -> {
                    if (event.content.isNotBlank()) {
                        emit(SemanticChunk.Thinking(event.content))
                    }
                }
                toolCall?.tags -> toolCallBuffer.append(event.content)
            }

            is LexerEvent.TagClosed -> {
                if (toolCall != null && event.delimiter == toolCall.tags) {
                    emit(SemanticChunk.ToolCall(toolCall.callParser(toolCallBuffer.toString())))
                    toolCallBuffer.clear()
                }
            }

            is LexerEvent.TagOpened -> { }
        }
    }
}
