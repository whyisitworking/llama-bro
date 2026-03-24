package com.suhel.llamabro.sdk.chat.pipeline

import com.suhel.llamabro.sdk.engine.TokenGenerationResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * 1. Syntax Layer: Parses raw strings into LexerEvents based on active feature markers.
 * Uses an internal AllocationOptimizedScanner for high-performance, low-memory DFA scanning.
 */
internal fun Flow<TokenGenerationResult>.lexTags(markers: List<FeatureMarker>): Flow<LexerEvent> = flow {
    val scanner = AllocationOptimizedScanner(markers)
    collect { result ->
        val events = scanner.feed(result.token)
        for (event in events) {
            emit(event)
        }
        
        // When stream naturally completes, feed null to flush scanner if needed.
        if (result.isComplete) {
            val finalEvents = scanner.feed(null)
            for (event in finalEvents) {
                emit(event)
            }
        }
    }
}

/**
 * 2. Semantic Layer: Buffers syntax events into whole textual or semantic parts.
 */
internal fun Flow<LexerEvent>.semanticChunks(): Flow<SemanticChunk> = flow {
    collect { event ->
        when (event) {
            is LexerEvent.Text -> emit(SemanticChunk.Text(event.content))
            is LexerEvent.TagContent -> {
                when (event.marker) {
                    is ThinkingMarker -> emit(SemanticChunk.Thinking(event.content))
                    is ToolCallMarker -> emit(SemanticChunk.ToolCallContent(event.content))
                }
            }
            is LexerEvent.TagClosed -> {
                if (event.marker is ToolCallMarker) emit(SemanticChunk.ToolCallComplete)
            }
            is LexerEvent.TagOpened -> {}
        }
    }
}
