package com.suhel.llamabro.sdk.chat.pipeline

import com.suhel.llamabro.sdk.config.ModelProfile
import com.suhel.llamabro.sdk.engine.TokenGenerationResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.transform

/**
 * 1. Syntax Layer: Parses raw strings into LexerEvents based on active tag delimiters.
 * Uses an internal AllocationOptimizedScanner for high-performance, low-memory DFA scanning.
 */
internal fun Flow<TokenGenerationResult>.lexTags(delimiters: List<TagDelimiter>): Flow<LexerEvent> {
    val scanner = AllocationOptimizedScanner(delimiters)
    return transform { result ->
        for (event in scanner.feed(result.token)) {
            emit(event)
        }
    }
}

/**
 * 2. Semantic Layer: Maps tag delimiters to semantic meaning using the model profile.
 */
internal fun Flow<LexerEvent>.semanticChunks(profile: ModelProfile): Flow<SemanticChunk> =
    transform { event ->
        when (event) {
            is LexerEvent.Text -> emit(SemanticChunk.Text(event.content))
            is LexerEvent.TagContent -> {
                when (event.delimiter) {
                    profile.thinking?.tags -> emit(SemanticChunk.Thinking(event.content))
                    profile.toolCall?.tags -> emit(SemanticChunk.ToolCallContent(event.content))
                }
            }

            is LexerEvent.TagClosed -> {
                if (event.delimiter == profile.toolCall?.tags) emit(SemanticChunk.ToolCallComplete)
            }

            is LexerEvent.TagOpened -> { /* no-op */ }
        }
    }
