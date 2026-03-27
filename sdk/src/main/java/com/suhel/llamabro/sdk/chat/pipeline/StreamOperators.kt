package com.suhel.llamabro.sdk.chat.pipeline

import com.suhel.llamabro.sdk.config.ToolCallCapability
import com.suhel.llamabro.sdk.engine.TokenGenerationResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.transform

/**
 * 1. Syntax Layer: Parses raw token strings into [LexerEvent]s via DFA scanning.
 */
internal fun Flow<TokenGenerationResult>.lexTags(
    delimiters: List<TagDelimiter>,
    initialActiveDelimiter: TagDelimiter? = null,
): Flow<LexerEvent> {
    val scanner = AllocationOptimizedScanner(delimiters, initialActiveDelimiter)
    return transform { result ->
        scanner.feed(result.token) { event -> emit(event) }
    }
}

/**
 * 2. Semantic Layer: Maps [LexerEvent]s to [SemanticChunk]s.
 *
 * @param thinkingTags The delimiter for thinking blocks (from native template info), or null.
 * @param thinkEnabled Whether the user requested thinking. When `false`, any thinking content
 *   the model emits is re-classified as [SemanticChunk.Text] — the `<think>` / `</think>` tags
 *   are still stripped, but the inner content surfaces as regular text. This handles models
 *   (e.g. Qwen 3) that always wrap output in thinking tags regardless of the template flag.
 * @param toolCall The tool call capability for parsing tool calls, or null.
 */
internal fun Flow<LexerEvent>.semanticChunks(
    thinkingTags: TagDelimiter?,
    thinkEnabled: Boolean = true,
    toolCall: ToolCallCapability? = null,
): Flow<SemanticChunk> {
    val toolCallBuffer = StringBuilder()
    return transform { event ->
        when (event) {
            is LexerEvent.Text -> emit(SemanticChunk.Text(event.content))
            is LexerEvent.TagContent -> when (event.delimiter) {
                thinkingTags -> {
                    if (event.content.isNotBlank()) {
                        if (thinkEnabled) {
                            emit(SemanticChunk.Thinking(event.content))
                        } else {
                            // Model emitted thinking content despite thinking being disabled.
                            // Surface it as regular text so the user still sees the response.
                            emit(SemanticChunk.Text(event.content))
                        }
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
