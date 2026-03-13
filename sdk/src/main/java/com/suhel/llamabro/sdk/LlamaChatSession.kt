package com.suhel.llamabro.sdk

import com.suhel.llamabro.sdk.model.ChatGeneration
import com.suhel.llamabro.sdk.model.Message
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.scan

class LlamaChatSession(private val rawSession: LlamaSession) {
    fun chat(message: String) = flow {
        rawSession.prompt(Message.User(message))

        val startTime = System.nanoTime()
        var isInThinkingBlock = false
        var tokenCount = 0

        while (true) {
            val token = rawSession.generate()

            if (token == null) {
                val endTime = System.nanoTime()
                val tokensPerSecond = (tokenCount / (endTime - startTime) * 1e9).toFloat()
                emit(Chunk.Metric(tokensPerSecond))
                break
            } else {
                tokenCount++
            }

            classifyAndEmit(token, isInThinkingBlock) { isInThinkingBlock = it }
        }
    }.scan(ChatGeneration()) { acc, chunk ->
        when (chunk) {
            is Chunk.Content -> acc.copy(contentText = acc.contentText.orEmpty() + chunk.text)
            is Chunk.Thinking -> acc.copy(thinkingText = acc.thinkingText.orEmpty() + chunk.text)
            is Chunk.Metric -> acc.copy(tokensPerSecond = chunk.tokensPerSecond, isComplete = true)
        }
    }

    suspend fun reset() {
        rawSession.clear()
    }

    sealed interface Chunk {
        data class Content(val text: String) : Chunk
        data class Thinking(val text: String) : Chunk
        data class Metric(val tokensPerSecond: Float) : Chunk
    }

    private suspend fun FlowCollector<Chunk>.classifyAndEmit(
        token: String,
        isInThinkingBlock: Boolean,
        onThinkingStateChange: (Boolean) -> Unit,
    ) {
        when {
            token.contains("<think>") -> onThinkingStateChange(true)
            token.contains("</think>") -> onThinkingStateChange(false)
            isInThinkingBlock -> emit(Chunk.Thinking(token))
            else -> emit(Chunk.Content(token))
        }
    }
}
