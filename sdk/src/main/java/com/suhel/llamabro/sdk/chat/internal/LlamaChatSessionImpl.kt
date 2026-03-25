package com.suhel.llamabro.sdk.chat.internal

import android.util.Log
import com.suhel.llamabro.sdk.chat.ChatEvent
import com.suhel.llamabro.sdk.chat.CompletionResult
import com.suhel.llamabro.sdk.chat.LlamaChatSession
import com.suhel.llamabro.sdk.chat.pipeline.SemanticChunk
import com.suhel.llamabro.sdk.chat.pipeline.lexTags
import com.suhel.llamabro.sdk.chat.pipeline.semanticChunks
import com.suhel.llamabro.sdk.engine.LlamaSession
import com.suhel.llamabro.sdk.engine.internal.NativeErrorMapper
import com.suhel.llamabro.sdk.format.PromptFormatter
import com.suhel.llamabro.sdk.format.ThinkingDecorator
import com.suhel.llamabro.sdk.format.ToolCallDecorator
import com.suhel.llamabro.sdk.toolcall.ToolCall
import com.suhel.llamabro.sdk.toolcall.ToolDefinition
import com.suhel.llamabro.sdk.toolcall.ToolResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlin.coroutines.cancellation.CancellationException

internal class LlamaChatSessionImpl(
    private val session: LlamaSession,
    private val systemPrompt: String,
    private val toolCaller: (suspend (List<ToolCall>) -> List<ToolResult>)? = null,
) : LlamaChatSession {

    private val profile = session.loadableModel.profile
    private var formatter: PromptFormatter = PromptFormatter(profile)

    init {
        // Construction-time validation: if the model can produce tool calls,
        // a tool caller MUST be provided. Fail fast, not mid-generation.
        if (profile.toolCall != null) {
            requireNotNull(toolCaller) {
                "Model profile declares tool call capability but no toolCaller was provided. " +
                        "Pass a toolCaller to createChatSession() or use a profile without tool support."
            }
        }
    }

    override suspend fun initialize(tools: List<ToolDefinition>) {
        val decorators = listOfNotNull(
            profile.thinking?.let { ThinkingDecorator(it) },
            profile.toolCall?.let { ToolCallDecorator(it, tools) },
        )

        formatter = PromptFormatter(profile, decorators)

        val system = ChatEvent.SystemEvent(
            content = systemPrompt,
            tools = tools,
        )

        session.setPrefixedPrompt(formatter.formatSystem(system))
    }

    override suspend fun feedHistory(history: List<ChatEvent>) {
        history.forEach {
            session.addPrompt(formatter.formatHistory(it))
        }
    }

    override fun completion(message: ChatEvent.UserEvent): Flow<CompletionResult> = flow {
        try {
            // formatGeneration includes assistant prefix + thinking strategy prefill
            session.addPrompt(formatter.formatGeneration(message))

            val timeline = mutableListOf<ChatEvent.AssistantEvent.Part>()
            var turnComplete = false

            var tokenCount = 0
            val startTimeMs = System.currentTimeMillis()

            while (!turnComplete) {
                val pendingToolCalls = mutableListOf<ToolCall>()

                session.generateFlow()
                    .lexTags(profile.tagDelimiters)
                    .semanticChunks(profile)
                    .collect { chunk ->
                        when (chunk) {
                            is SemanticChunk.Text -> {
                                timeline.appendOrMerge(
                                    content = chunk.content,
                                    isThinking = false,
                                )
                                tokenCount++
                            }

                            is SemanticChunk.Thinking -> {
                                timeline.appendOrMerge(
                                    content = chunk.content,
                                    isThinking = true
                                )
                                tokenCount++
                            }

                            is SemanticChunk.ToolCall -> {
                                timeline += ChatEvent.AssistantEvent.Part.ToolCallPart(chunk.call)
                                pendingToolCalls += chunk.call
                            }
                        }

                        emit(CompletionResult.Streaming(timeline.toList()))
                    }

                // Process tool calls AFTER generateFlow completes (mutex is released).
                // This avoids the deadlock that would occur if addPrompt() were called
                // inside the collect lambda while generateFlow holds the session mutex.
                if (pendingToolCalls.isEmpty()) {
                    turnComplete = true
                } else {
                    // Safe: validated at init — toolCaller is non-null when tool capability exists
                    val results = toolCaller!!(pendingToolCalls)

                    // Append tool results to the timeline
                    for (result in results) {
                        session.addPrompt(
                            formatter.formatHistory(ChatEvent.ToolResultEvent(result))
                        )
                    }

                    // Prime the assistant turn for the next generation loop
                    session.addPrompt(profile.promptFormat.assistantPrefix)
                }
            }

            val elapsed = (System.currentTimeMillis() - startTimeMs) / 1000f
            val tps = if (elapsed > 0f && tokenCount > 0) tokenCount / elapsed else 0f

            Log.e("LlamaChatSessionImpl", timeline.toString())
            emit(CompletionResult.Complete(timeline.toList(), tps))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emit(CompletionResult.Error(NativeErrorMapper.map(e)))
        }
    }

    /**
     * Appends content to the timeline, merging with the last element if it's the same type.
     * This avoids creating a new Part object for every token — only the tail entry is updated.
     */
    private fun MutableList<ChatEvent.AssistantEvent.Part>.appendOrMerge(
        content: String,
        isThinking: Boolean,
    ) {
        val last = this.lastOrNull()

        if (isThinking) {
            if (last is ChatEvent.AssistantEvent.Part.ThinkingPart) {
                this[this.lastIndex] = last.copy(content = last.content + content)
            } else {
                this += ChatEvent.AssistantEvent.Part.ThinkingPart(content)
            }
        } else {
            if (last is ChatEvent.AssistantEvent.Part.TextPart) {
                this[this.lastIndex] = last.copy(content = last.content + content)
            } else {
                this += ChatEvent.AssistantEvent.Part.TextPart(content)
            }
        }
    }
}
