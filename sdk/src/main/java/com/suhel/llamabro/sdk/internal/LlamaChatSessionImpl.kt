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

internal class LlamaChatSessionImpl(
    private val session: LlamaSession,
    private val systemPrompt: String
) : LlamaChatSession {
    private val parser = TokenStreamParser()
    private val prompter = Prompter(session.modelConfig.promptFormat)

    override val supportsThinking: Boolean
        get() = session.modelConfig.supportsThinking

    override fun completion(prompt: String, enableThinking: Boolean): Flow<Completion> = flow {
        var completionState = Completion()
        var tokenCount = 0
        val contentBuilder = StringBuilder()
        val thinkingBuilder = StringBuilder()
        val thinkingEnabled = enableThinking && session.modelConfig.supportsThinking

        parser.reset(thinkingEnabled)
        session.ingestPrompt(
            prompt = prompter.user(prompt) + prompter.assistantStart(thinkingEnabled),
            addSpecial = prompter.shouldAddSpecial()
        )

        val startTime = System.nanoTime()

        while (currentCoroutineContext().isActive) {
            val generation = try {
                session.generate()
            } catch (_: LlamaError.Cancelled) {
                emit(
                    completionState.finalize(
                        tokenCount = tokenCount,
                        startTime = startTime,
                        isInterrupted = true,
                        contentBuilder = contentBuilder,
                        thinkingBuilder = thinkingBuilder
                    )
                )
                return@flow
            } catch (e: LlamaError) {
                throw e
            }

            generation.token?.let { token ->
                tokenCount++

                val contentLenBefore = contentBuilder.length
                val thinkingLenBefore = thinkingBuilder.length
                val stateBefore = parser.isThinking

                // The parser directly modifies the builders. 0 allocations.
                parser.process(token, contentBuilder, thinkingBuilder)

                // Only emit a new state if the parser actually appended text or flipped state
                if (
                    contentBuilder.length > contentLenBefore ||
                    thinkingBuilder.length > thinkingLenBefore ||
                    parser.isThinking != stateBefore
                ) {
                    completionState = completionState.copy(
                        contentText = if (contentBuilder.isEmpty()) null else contentBuilder.toString(),
                        thinkingText = if (thinkingBuilder.isEmpty()) null else thinkingBuilder.toString()
                    )
                    emit(completionState)
                }
            }

            if (generation.isComplete) {
                parser.flush(contentBuilder, thinkingBuilder)
                emit(
                    completionState.finalize(
                        tokenCount = tokenCount,
                        startTime = startTime,
                        isInterrupted = false,
                        contentBuilder = contentBuilder,
                        thinkingBuilder = thinkingBuilder
                    )
                )
                break
            }
        }
    }
        .onCompletion { cause ->
            if (cause != null) {
                session.abort()
            }
        }
        .flowOn(Dispatchers.IO)

    /** Finalizes completion state with performance metrics and trimming. */
    private fun Completion.finalize(
        tokenCount: Int,
        startTime: Long,
        isInterrupted: Boolean,
        contentBuilder: StringBuilder,
        thinkingBuilder: StringBuilder
    ): Completion {
        val endTime = System.nanoTime()
        val durationNs = (endTime - startTime).coerceAtLeast(1)
        val tps = (tokenCount.toDouble() / durationNs * 1e9).toFloat()

        return this.copy(
            thinkingText = thinkingBuilder.ifBlank { null }?.toString()?.trim(),
            contentText = contentBuilder.ifBlank { null }?.toString()?.trim(),
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
                session.ingestPrompt(prompter.format(msg))
            }
        }

    /** Initial injection of the BOS and system prompt during session creation. */
    internal suspend fun initialize() =
        withContext(Dispatchers.IO) {
            session.setSystemPrompt(
                text = prompter.system(systemPrompt),
                addSpecial = prompter.shouldAddSpecial()
            )
        }
}
