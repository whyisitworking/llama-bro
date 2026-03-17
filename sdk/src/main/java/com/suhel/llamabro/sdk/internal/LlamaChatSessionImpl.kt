package com.suhel.llamabro.sdk.internal

import com.suhel.llamabro.sdk.LlamaChatSession
import com.suhel.llamabro.sdk.LlamaSession
import com.suhel.llamabro.sdk.model.Completion
import com.suhel.llamabro.sdk.model.LlamaError
import com.suhel.llamabro.sdk.model.Message
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/**
 * High-level implementation of [LlamaChatSession].
 *
 * This class coordinates between the raw [LlamaSession], the [PromptFormatter],
 * and the [TokenStreamParser] to provide a conversational experience.
 * It manages the token generation loop and transforms raw tokens into
 * structured [Completion] snapshots.
 *
 * ### Turn lifecycle
 * The C++ layer decodes EOG tokens into the KV cache, so every assistant
 * turn is automatically closed at the native level. This class does not
 * need to track or inject turn-closing tokens.
 */
internal class LlamaChatSessionImpl(
    private val session: LlamaSession,
    private val systemPrompt: String
) : LlamaChatSession {

    private val promptFormatter = PromptFormatter(session.modelConfig.promptFormat)

    override fun completion(message: String): Flow<Completion> = flow {
        val parser = TokenStreamParser(session.modelConfig.promptFormat.assistantSuffix)
        var completionState = Completion()
        var tokenCount = 0

        // Inject user turn + assistant turn prefix
        session.prompt(promptFormatter.user(message) + promptFormatter.assistantStart())
        val startTime = System.nanoTime()

        while (currentCoroutineContext().isActive) {
            val generation = try {
                session.generate()
            } catch (_: LlamaError.Cancelled) {
                emit(completionState.finalize(tokenCount, startTime, true))
                return@flow
            } catch (e: LlamaError) {
                throw e
            }

            if (generation.isComplete) {
                completionState = completionState.applyActions(parser.flush())
                emit(completionState.finalize(tokenCount, startTime))
                break
            }

            generation.token?.let { generatedToken ->
                tokenCount++
                val actions = parser.process(generatedToken)

                if (actions.isNotEmpty()) {
                    completionState = completionState.applyActions(actions)
                    emit(completionState)
                }

                // Stop if the parser intercepted a configured stop sequence
                // (e.g. assistant suffix for custom formats where suffix ≠ EOG).
                if (actions.any { it is StreamAction.Stop }) {
                    emit(completionState.finalize(tokenCount, startTime))
                    break
                }
            }
        }
    }
        .onCompletion { cause ->
            if (cause != null) {
                session.abort()
            }
        }
        .flowOn(Dispatchers.IO)

    /** Appends parser actions to the current completion snapshot. */
    private fun Completion.applyActions(actions: List<StreamAction>): Completion {
        var newContent = this.contentText
        var newThinking = this.thinkingText

        for (action in actions) {
            when (action) {
                is StreamAction.Content -> {
                    newContent = (newContent ?: "") + action.text
                }

                is StreamAction.Thinking -> {
                    newThinking = (newThinking ?: "") + action.text
                }

                is StreamAction.Stop -> {
                }
            }
        }

        return this.copy(contentText = newContent, thinkingText = newThinking)
    }

    /** Finalizes completion state with performance metrics and trimming. */
    private fun Completion.finalize(
        tokenCount: Int,
        startTime: Long,
        isInterrupted: Boolean = false
    ): Completion {
        val endTime = System.nanoTime()
        val durationNs = (endTime - startTime).coerceAtLeast(1)
        val tps = (tokenCount.toDouble() / durationNs * 1e9).toFloat()

        return this.copy(
            thinkingText = if (this.thinkingText.isNullOrBlank()) null else this.thinkingText.trim(),
            contentText = if (this.contentText.isNullOrBlank()) null else this.contentText.trim(),
            tokensPerSecond = tps,
            isComplete = true,
            isInterrupted = isInterrupted,
        )
    }

    override suspend fun reset() =
        withContext(Dispatchers.IO) {
            session.clear()
        }

    override suspend fun loadHistory(messages: List<Message>) =
        withContext(Dispatchers.IO) {
            messages.forEach { msg ->
                session.prompt(promptFormatter.format(msg))
            }
        }

    /** Initial injection of the BOS and system prompt during session creation. */
    internal suspend fun initialize() =
        withContext(Dispatchers.IO) {
            session.setSystemPrompt(
                text = promptFormatter.system(systemPrompt),
                addSpecial = promptFormatter.shouldAddSpecial()
            )
        }
}
