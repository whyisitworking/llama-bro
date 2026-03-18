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
    private val fmt = session.modelConfig.promptFormat
    private val parser = TokenStreamParser(
        thinkingStart = fmt.thinkStart,
        thinkingEnd = fmt.thinkEnd,
        stopStrings = fmt.stopStrings,
    )
    private val prompter = Prompter(fmt)

    override val supportsThinking: Boolean
        get() = session.modelConfig.supportsThinking

    override fun completion(
        prompt: String,
        enableThinking: Boolean,
        maxThinkingTokens: Int?,
    ): Flow<Completion> = flow {
        var completionState = Completion()
        var tokenCount = 0
        var thinkingTokenCount = 0
        val contentBuilder = StringBuilder()
        val thinkingBuilder = StringBuilder()
        val thinkingEnabled = enableThinking && session.modelConfig.supportsThinking

        parser.reset(thinkingEnabled)

        val formattedPrompt = buildString {
            append(prompter.user(prompt))
            append(prompter.assistantStart())

            if(thinkingEnabled) {
                append(prompter.thinkingStart())
            }
        }

        session.ingestPrompt(
            prompt = formattedPrompt,
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
            } catch (_: LlamaError.ContextOverflow) {
                // Context exhausted and strategy cannot recover — surface as an interrupted completion.
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
                // Fatal errors (DecodeFailed, NativeException, etc.) are emitted as data,
                // not thrown — the flow always terminates cleanly.
                emit(
                    completionState.finalize(
                        tokenCount = tokenCount,
                        startTime = startTime,
                        isInterrupted = false,
                        contentBuilder = contentBuilder,
                        thinkingBuilder = thinkingBuilder,
                        error = e
                    )
                )
                return@flow
            }

            generation.token?.let { token ->
                tokenCount++
                if (parser.isThinking) thinkingTokenCount++

                val contentLenBefore = contentBuilder.length
                val thinkingLenBefore = thinkingBuilder.length
                val stateBefore = parser.isThinking

                // The parser directly modifies the builders. 0 allocations.
                parser.process(token, contentBuilder, thinkingBuilder)

                // Only emit a new state if the parser actually appended text or flipped state.
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

                // Stop string detected — treat as a clean end of generation.
                if (parser.isStopped) {
                    emit(
                        completionState.finalize(
                            tokenCount = tokenCount,
                            startTime = startTime,
                            isInterrupted = false,
                            contentBuilder = contentBuilder,
                            thinkingBuilder = thinkingBuilder
                        )
                    )
                    return@flow
                }
            }

            // Thinking budget exhausted — force-close the thinking block so the model
            // begins its response. Done outside the token `let` to allow suspension.
            if (
                parser.isThinking &&
                maxThinkingTokens != null &&
                thinkingTokenCount >= maxThinkingTokens
            ) {
                val closeTag = prompter.thinkingEnd()
                session.ingestPrompt(closeTag, addSpecial = false)
                parser.process(closeTag, contentBuilder, thinkingBuilder)
                completionState = completionState.copy(
                    thinkingText = thinkingBuilder.ifBlank { null }?.toString()?.trim()
                )
                emit(completionState)
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
        thinkingBuilder: StringBuilder,
        error: LlamaError? = null,
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
            error = error,
        )
    }

    override suspend fun reset() =
        withContext(Dispatchers.IO) {
            session.clear()
        }

    /**
     * Ingests [messages] into the session, oldest-first. If the context fills up mid-load,
     * the oldest message is dropped and the remaining slice is retried from the system-prompt
     * boundary — ensuring the most recent history always fits.
     */
    override suspend fun loadHistory(messages: List<Message>) =
        withContext(Dispatchers.IO) {
            var start = 0
            retry@ while (start < messages.size) {
                session.clear()
                for (i in start until messages.size) {
                    try {
                        session.ingestPrompt(prompter.format(messages[i]))
                    } catch (_: LlamaError.ContextOverflow) {
                        start++
                        continue@retry
                    }
                }
                return@withContext
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
