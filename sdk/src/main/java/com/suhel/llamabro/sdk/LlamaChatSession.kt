package com.suhel.llamabro.sdk

import com.suhel.llamabro.sdk.model.ChatGeneration
import com.suhel.llamabro.sdk.model.Message
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * High-level conversational API built on top of [LlamaSession].
 *
 * Wraps raw token generation into a [ChatGeneration] flow that separates
 * thinking blocks (`<think>…</think>`) from content, accumulates text, and
 * reports tokens-per-second on completion.
 *
 * **Thread safety:** Instances are **not** thread-safe. Collect the [chat] flow
 * from a single coroutine at a time. Call [reset] only when no generation is active.
 */
class LlamaChatSession(private val rawSession: LlamaSession) {

    /**
     * Sends [message] to the model and returns a [ChatGeneration] flow.
     *
     * Each emission is a progressively accumulated snapshot: thinking text,
     * content text, and finally tokens-per-second with [ChatGeneration.isComplete] = true.
     */
    fun chat(message: String) = callbackFlow {
        tagBuffer.clear()

        // This runs the suspension on IO and allows proper cancellation
        launch(Dispatchers.IO) {
            try {
                rawSession.prompt(Message.User(message))

                val startTime = System.nanoTime()
                var isInThinkingBlock = false
                var tokenCount = 0

                while (isActive) {
                    val token = rawSession.generate()

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
            rawSession.abort()
        }
    }.scan(ChatGeneration()) { acc, chunk ->
        when (chunk) {
            is Chunk.Content -> acc.copy(contentText = acc.contentText.orEmpty() + chunk.text)
            is Chunk.Thinking -> acc.copy(thinkingText = acc.thinkingText.orEmpty() + chunk.text)
            is Chunk.Metric -> acc.copy(tokensPerSecond = chunk.tokensPerSecond, isComplete = true)
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Clears the conversation history, keeping only the system prompt.
     * This blocks briefly while the native KV cache is reset.
     */
    fun reset() {
        rawSession.clear()
    }

    /**
     * Synchronously ingest a list of historical messages into the conversation.
     * This feeds the messages into the underlying native KV cache to restore context.
     * No token generation is performed during loading.
     */
    suspend fun loadHistory(messages: List<Message>) {
        messages.forEach { msg ->
            rawSession.prompt(msg)
        }
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
