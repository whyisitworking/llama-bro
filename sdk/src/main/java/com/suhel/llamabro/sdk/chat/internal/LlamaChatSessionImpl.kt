package com.suhel.llamabro.sdk.chat.internal

import com.suhel.llamabro.sdk.chat.ChatEvent
import com.suhel.llamabro.sdk.chat.CompletionSnapshot
import com.suhel.llamabro.sdk.chat.LlamaChatSession
import com.suhel.llamabro.sdk.chat.pipeline.SemanticChunk
import com.suhel.llamabro.sdk.chat.pipeline.lexTags
import com.suhel.llamabro.sdk.chat.pipeline.semanticChunks
import com.suhel.llamabro.sdk.engine.LlamaSession
import com.suhel.llamabro.sdk.format.PromptDecorator
import com.suhel.llamabro.sdk.format.PromptFormatter
import com.suhel.llamabro.sdk.format.ThinkingDecorator
import com.suhel.llamabro.sdk.format.ToolCallDecorator
import com.suhel.llamabro.sdk.toolcall.ToolCall
import com.suhel.llamabro.sdk.toolcall.ToolDefinition
import com.suhel.llamabro.sdk.toolcall.ToolResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

internal class LlamaChatSessionImpl(
    private val session: LlamaSession,
    private val systemPrompt: String,
    private val toolCaller: (suspend (List<ToolCall>) -> List<ToolResult>)? = null
) : LlamaChatSession {

    private val profile = session.loadableModel.profile
    private var formatter: PromptFormatter = PromptFormatter(profile)

    override suspend fun initialize(tools: List<ToolDefinition>) {
        val decorators = mutableListOf<PromptDecorator>()

        profile.thinking?.let { decorators.add(ThinkingDecorator(it)) }
        profile.toolCall?.let { decorators.add(ToolCallDecorator(it, tools)) }

        formatter = PromptFormatter(profile, decorators)

        val system = ChatEvent.SystemEvent(
            content = systemPrompt,
            tools = tools,
        )

        session.setPrefixedPrompt(formatter.formatTurn(system))
    }

    override suspend fun feedHistory(history: List<ChatEvent>) {
        history.forEach {
            session.addPrompt(formatter.formatTurn(it))
        }
    }

    override fun completion(message: ChatEvent.UserEvent): Flow<CompletionSnapshot> = flow {
        // 1. Prepare prompts via holistic formatter logic
        session.addPrompt(formatter.formatTurn(message))

        // 2. Setup context
        val toolCallCapability = profile.toolCall

        var completedParts = emptyList<ChatEvent.AssistantEvent.Part>()
        var currentText = ""
        var currentThinking = ""
        var currentToolCallBuffer = ""
        var turnComplete = false

        // Timing state for tok/s metric
        var tokenCount = 0
        val startTimeMs = System.currentTimeMillis()

        // 3. Declarative Streaming Loop
        while (!turnComplete) {
            var executionTriggered = false

            session.generateFlow()
                .lexTags(profile.tagDelimiters)
                .semanticChunks(profile)
                .collect { chunk ->
                    // Apply immutable state transitions
                    when (chunk) {
                        is SemanticChunk.Text -> {
                            currentText += chunk.content
                            tokenCount++
                        }

                        is SemanticChunk.Thinking -> {
                            currentThinking += chunk.content
                            tokenCount++
                        }

                        is SemanticChunk.ToolCallContent -> currentToolCallBuffer += chunk.content
                        is SemanticChunk.ToolCallComplete -> {
                            val capability = toolCallCapability
                                ?: throw IllegalStateException("Tool call triggered but model lacks tool definitions.")
                            val call = capability.callParser(currentToolCallBuffer)
                            completedParts =
                                completedParts + ChatEvent.AssistantEvent.Part.ToolCallPart(call)
                            currentToolCallBuffer = ""

                            val results = toolCaller?.invoke(listOf(call)) ?: emptyList()
                            for (result in results) {
                                val toolResult = ChatEvent.ToolResultEvent(result)
                                session.addPrompt(formatter.formatTurn(toolResult))
                            }
                            executionTriggered = true
                        }
                    }

                    // Emit reactive intermediate UI state
                    emit(
                        CompletionSnapshot(
                            message = ChatEvent.AssistantEvent(
                                buildSnapshotParts(completedParts, currentText, currentThinking)
                            ),
                            isComplete = false,
                            isError = false,
                            error = null
                        )
                    )
                }

            // If the native generator completed naturally without invoking a tool, we're done.
            if (!executionTriggered) {
                turnComplete = true
            }
        }

        // 4. Final Emit
        val elapsedSeconds = (System.currentTimeMillis() - startTimeMs) / 1000f
        val tokensPerSecond =
            if (elapsedSeconds > 0f && tokenCount > 0) tokenCount / elapsedSeconds else 0f

        completedParts = buildSnapshotParts(completedParts, currentText, currentThinking)
        emit(
            CompletionSnapshot(
                message = ChatEvent.AssistantEvent(completedParts),
                isComplete = true,
                isError = false,
                error = null,
                tokensPerSecond = tokensPerSecond
            )
        )
    }

    private fun buildSnapshotParts(
        completedParts: List<ChatEvent.AssistantEvent.Part>,
        currentText: String,
        currentThinking: String
    ): List<ChatEvent.AssistantEvent.Part> {
        val result = completedParts.toMutableList()
        if (currentThinking.isNotEmpty()) {
            result.add(ChatEvent.AssistantEvent.Part.ThinkingPart(currentThinking))
        }
        if (currentText.isNotEmpty()) {
            result.add(ChatEvent.AssistantEvent.Part.TextPart(currentText))
        }
        return result
    }
}
