package com.suhel.llamabro.sdk.internal

import com.suhel.llamabro.sdk.LlamaChatSession
import com.suhel.llamabro.sdk.LlamaSession
import com.suhel.llamabro.sdk.model.Completion
import com.suhel.llamabro.sdk.model.Message
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class LlamaChatSessionImpl(
    private val session: LlamaSession,
    private val systemPrompt: String
) : LlamaChatSession {
    private val promptFormatter = PromptFormatter(session.modelConfig.promptFormat)

    override fun completion(message: String): Flow<Completion> = callbackFlow {
        tagBuffer.clear()

        // This runs the suspension on IO and allows proper cancellation
        launch(Dispatchers.IO) {
            try {
                session.prompt(
                    promptFormatter.user(message) + promptFormatter.assistant(null)
                )

                val startTime = System.nanoTime()
                var isInThinkingBlock = false
                var tokenCount = 0

                while (isActive) {
                    val token = session.generate()

                    if (token == null) {
                        val endTime = System.nanoTime()
                        val tokensPerSecond =
                            (tokenCount.toDouble() / (endTime - startTime) * 1e9).toFloat()
                        flushBuffer(isInThinkingBlock)
                        send(Chunk.Metric(tokensPerSecond))
                        break
                    } else {
                        tokenCount++
                    }

                    isInThinkingBlock = classifyAndEmit(token, isInThinkingBlock)
                }
            } finally {
                close()
            }
        }

        awaitClose {
            session.abort()
        }
    }.scan(Completion()) { acc, chunk ->
        when (chunk) {
            is Chunk.Content -> acc.copy(
                contentText = if (chunk.text.isEmpty()) acc.contentText
                else acc.contentText.orEmpty() + chunk.text
            )

            is Chunk.Thinking -> acc.copy(
                thinkingText = if (chunk.text.isEmpty()) acc.thinkingText
                else acc.thinkingText.orEmpty() + chunk.text
            )

            is Chunk.Metric -> {
                val suffix = session.modelConfig.promptFormat.assistantSuffix
                acc.copy(
                    contentText = acc.contentText
                        ?.removeSuffix(suffix)?.trim()?.ifEmpty { null },
                    thinkingText = acc.thinkingText?.trim()?.ifEmpty { null },
                    tokensPerSecond = chunk.tokensPerSecond,
                    isComplete = true,
                )
            }
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun reset() = withContext(Dispatchers.IO) {
        session.clear()
    }

    override suspend fun loadHistory(messages: List<Message>) = withContext(Dispatchers.IO) {
        messages.forEach { msg ->
            session.prompt(promptFormatter.format(msg))
        }
    }

    internal suspend fun initialize() = withContext(Dispatchers.IO) {
        session.setSystemPrompt(promptFormatter.system(systemPrompt))
    }

    // ── Streaming thinking-tag parser ───────────────────────────────────────

    private val tagBuffer = StringBuilder()

    private sealed interface Chunk {
        data class Content(val text: String) : Chunk
        data class Thinking(val text: String) : Chunk
        data class Metric(val tokensPerSecond: Float) : Chunk
    }

    private suspend fun SendChannel<Chunk>.flushBuffer(isInThinkingBlock: Boolean) {
        // No pending text — nothing to do.
        if (tagBuffer.isEmpty()) return

        // The buffer held a partial candidate that never completed a tag.
        // Emit it in the current block context.
        val text = tagBuffer.toString()
        tagBuffer.clear()
        if (isInThinkingBlock) {
            send(Chunk.Thinking(text))
        } else {
            send(Chunk.Content(text))
        }
    }

    /**
     * Buffered streaming tag parser that handles `<think>` / `</think>` tags
     * even when they are split across multiple token emissions.
     *
     * Returns the (possibly updated) thinking-block state.
     */
    private suspend fun SendChannel<Chunk>.classifyAndEmit(
        token: String,
        isInThinkingBlock: Boolean,
    ): Boolean {
        tagBuffer.append(token)
        val buf = tagBuffer.toString()

        // Check for complete opening tag
        val openIdx = buf.indexOf(OPEN_TAG)
        if (openIdx != -1) {
            // Emit any content before the tag
            if (openIdx > 0) {
                send(Chunk.Content(buf.substring(0, openIdx)))
            }
            // Keep anything after the tag in the buffer for further processing
            val after = buf.substring(openIdx + OPEN_TAG.length)
            tagBuffer.clear()
            if (after.isNotEmpty()) {
                tagBuffer.append(after)
                return drainBuffer(this, isInThinkingBlock = true)
            }
            return true
        }

        // Check for complete closing tag
        val closeIdx = buf.indexOf(CLOSE_TAG)
        if (closeIdx != -1) {
            // Emit any thinking text before the tag
            if (closeIdx > 0) {
                send(Chunk.Thinking(buf.substring(0, closeIdx)))
            }
            // Keep anything after the tag in the buffer for further processing
            val after = buf.substring(closeIdx + CLOSE_TAG.length)
            tagBuffer.clear()
            if (after.isNotEmpty()) {
                tagBuffer.append(after)
                return drainBuffer(this, isInThinkingBlock = false)
            }
            return false
        }

        // Could the buffer end with a partial tag prefix?
        if (couldContainPartialTag(buf)) {
            // Hold in buffer — wait for more tokens to confirm or deny a tag.
            return isInThinkingBlock
        }

        // No tag possibility — flush everything.
        tagBuffer.clear()
        if (buf.isEmpty()) return isInThinkingBlock
        if (isInThinkingBlock) {
            send(Chunk.Thinking(buf))
        } else {
            send(Chunk.Content(buf))
        }
        return isInThinkingBlock
    }

    /**
     * Recursively drains the buffer when it may contain additional tags
     * (e.g., a token delivered both `</think>` and `<think>` at once).
     */
    private suspend fun drainBuffer(
        channel: SendChannel<Chunk>,
        isInThinkingBlock: Boolean,
    ): Boolean {
        if (tagBuffer.isEmpty()) return isInThinkingBlock
        // Re-enter the parser with an empty token so the buffer is re-examined.
        return channel.classifyAndEmit("", isInThinkingBlock)
    }

    companion object {
        private const val OPEN_TAG = "<think>"
        private const val CLOSE_TAG = "</think>"

        /**
         * Returns true if the tail of [text] could be the start of `<think>` or `</think>`.
         */
        internal fun couldContainPartialTag(text: String): Boolean {
            if (text.isEmpty()) return false
            // Check if any suffix of text is a prefix of either tag.
            val maxCheck = maxOf(OPEN_TAG.length, CLOSE_TAG.length) - 1
            val start = maxOf(0, text.length - maxCheck)
            for (i in start until text.length) {
                val suffix = text.substring(i)
                if (OPEN_TAG.startsWith(suffix) || CLOSE_TAG.startsWith(suffix)) {
                    return true
                }
            }
            return false
        }
    }
}