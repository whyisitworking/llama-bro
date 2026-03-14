package com.suhel.llamabro.sdk.internal

import com.suhel.llamabro.sdk.LlamaChatSession
import com.suhel.llamabro.sdk.LlamaSession
import com.suhel.llamabro.sdk.model.Completion
import com.suhel.llamabro.sdk.model.Message
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

internal class LlamaChatSessionImpl(
    private val session: LlamaSession,
    private val systemPrompt: String
) : LlamaChatSession {
    private val promptFormatter = PromptFormatter(session.modelConfig.promptFormat)

    override fun completion(message: String): Flow<Completion> = flow {
        val parser = TokenStreamParser(session.modelConfig.promptFormat.assistantSuffix)
        var completionState = Completion()
        var tokenCount = 0

        session.prompt(promptFormatter.user(message) + promptFormatter.assistant(null))
        val startTime = System.nanoTime()

        while (currentCoroutineContext().isActive) {
            val token = session.generate()

            // 1. Handle native End of Generation (EOG)
            if (token == null) {
                completionState = completionState.applyActions(parser.flush())
                emit(finalizeCompletion(completionState, tokenCount, startTime))
                break
            }

            tokenCount++
            val actions = parser.process(token)

            // 2. Update state and emit if we have new verified text
            if (actions.isNotEmpty()) {
                completionState = completionState.applyActions(actions)
                emit(completionState)
            }

            // 3. Handle intercepted assistantSuffix Stop word
            if (actions.any { it is StreamAction.Stop }) {
                emit(finalizeCompletion(completionState, tokenCount, startTime))
                break
            }
        }
    }
        .onCompletion { session.abort() } // Ensures C++ halt if coroutine is cancelled
        .flowOn(Dispatchers.IO)

    private fun Completion.applyActions(actions: List<StreamAction>): Completion {
        var newContent = this.contentText
        var newThinking = this.thinkingText

        for (action in actions) {
            when (action) {
                is StreamAction.Content -> newContent = (newContent ?: "") + action.text
                is StreamAction.Thinking -> newThinking = (newThinking ?: "") + action.text
                is StreamAction.Stop -> { /* Handled in flow loop */
                }
            }
        }
        return this.copy(contentText = newContent, thinkingText = newThinking)
    }

    private fun finalizeCompletion(state: Completion, tokens: Int, startTime: Long): Completion {
        val endTime = System.nanoTime()
        val tps = (tokens.toDouble() / (endTime - startTime) * 1e9).toFloat()
        return state.copy(
            thinkingText = state.thinkingText?.trim(),
            contentText = state.contentText?.trim(),
            tokensPerSecond = tps,
            isComplete = true,
        )
    }

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
}
