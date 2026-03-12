package com.suhel.llamabro.sdk.internal

import com.suhel.llamabro.sdk.api.ChatSession
import com.suhel.llamabro.sdk.api.ChatSessionConfig
import com.suhel.llamabro.sdk.api.LlamaError
import com.suhel.llamabro.sdk.api.LlamaSession
import com.suhel.llamabro.sdk.api.PromptFormat
import com.suhel.llamabro.sdk.api.PromptFormatter
import com.suhel.llamabro.sdk.api.UserMessage
import com.suhel.llamabro.sdk.schema.Chunk
import com.suhel.llamabro.sdk.schema.Message
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.isActive
import kotlin.coroutines.coroutineContext
import kotlin.system.measureTimeMillis

internal class ChatSessionImpl(
    private val rawSession: LlamaSession,
    promptFormat: PromptFormat,
    config: ChatSessionConfig,
    private val maxNewTokens: Int,
) : ChatSession {

    private val promptFormatter = PromptFormatter(promptFormat)
    private val _history = mutableListOf<Message>()
    private val systemPrompt = config.systemPrompt

    override val history: List<Message>
        get() = _history.toList()

    init {
        // Warm up the context with the system prompt if provided.
        // This runs on whatever thread creates the ChatSession.
        if (!systemPrompt.isNullOrBlank()) {
            rawSession.prompt(Message.User(promptFormatter.system(systemPrompt)))
        }
    }

    override fun chat(message: UserMessage): Flow<Chunk> {
        val startTimeMs = System.currentTimeMillis()
        var pieceCount = 0
        val contentBuilder = StringBuilder()
        val thinkingBuilder = StringBuilder()
        var isInThinkingBlock = false

        return flow {
            // Stage user message into the KV cache
            rawSession.prompt(Message.User(message.content))
            _history.add(Message.User(message.content))

            // Generate token pieces until EOS, max token guard, or cancellation
            var tokenCount = 0
            while (coroutineContext.isActive && tokenCount < maxNewTokens) {
                val piece = rawSession.generate() ?: break   // null = EOS
                tokenCount++

                val chunk = classifyAndEmit(
                    piece             = piece,
                    showThinking      = message.showThinking,
                    isInThinkingBlock = isInThinkingBlock,
                    contentBuilder    = contentBuilder,
                    thinkingBuilder   = thinkingBuilder,
                    onThinkingStateChange = { isInThinkingBlock = it },
                )

                if (chunk != null) {
                    emit(chunk)
                }
            }
        }
        .onCompletion { cause ->
            // Record the assistant turn in history upon completion (success or cancel)
            val elapsedMs = System.currentTimeMillis() - startTimeMs
            val tps = if (elapsedMs > 0 && pieceCount > 0) {
                pieceCount / (elapsedMs / 1000f)
            } else null

            val assistantMessage = Message.Assistant(
                content          = contentBuilder.toString(),
                thinking         = thinkingBuilder.takeIf { it.isNotEmpty() }?.toString(),
                tokensPerSecond  = if (cause == null) tps else null, // null on cancellation
            )
            _history.add(assistantMessage)
        }
        .flowOn(Dispatchers.IO)
    }

    override fun reset() {
        _history.clear()
        rawSession.clear()

        // Re-ingest the system prompt after clearing
        if (!systemPrompt.isNullOrBlank()) {
            rawSession.prompt(Message.User(promptFormatter.system(systemPrompt)))
        }
    }

    override fun close() {
        // ChatSession does not own the rawSession lifecycle — the caller does.
        // Closing rawSession is the responsibility of whoever created LlamaSession.
        _history.clear()
    }

    /**
     * Classifies an incoming text piece as either [Chunk.Thinking] or [Chunk.Content],
     * based on simple `<think>` / `</think>` tag detection used by reasoning models.
     *
     * Returns null if the piece is a partial tag boundary (consumed but not yet emittable).
     */
    private fun classifyAndEmit(
        piece: String,
        showThinking: Boolean,
        isInThinkingBlock: Boolean,
        contentBuilder: StringBuilder,
        thinkingBuilder: StringBuilder,
        onThinkingStateChange: (Boolean) -> Unit,
    ): Chunk? {
        return when {
            piece.contains("<think>") -> {
                onThinkingStateChange(true)
                null // tag itself is not emitted
            }
            piece.contains("</think>") -> {
                onThinkingStateChange(false)
                null
            }
            isInThinkingBlock -> {
                thinkingBuilder.append(piece)
                if (showThinking) Chunk.Thinking(piece) else null
            }
            else -> {
                contentBuilder.append(piece)
                Chunk.Content(piece)
            }
        }
    }
}
