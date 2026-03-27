package com.suhel.llamabro.sdk.chat.internal

import com.suhel.llamabro.sdk.chat.ChatCompletionEvent
import com.suhel.llamabro.sdk.chat.ChatCompletionOptions
import com.suhel.llamabro.sdk.chat.ChatMessage
import com.suhel.llamabro.sdk.chat.LlamaChatCompletion
import com.suhel.llamabro.sdk.chat.Usage
import com.suhel.llamabro.sdk.chat.pipeline.SemanticChunk
import com.suhel.llamabro.sdk.chat.pipeline.TagDelimiter
import com.suhel.llamabro.sdk.chat.pipeline.lexTags
import com.suhel.llamabro.sdk.chat.pipeline.semanticChunks
import com.suhel.llamabro.sdk.config.InferenceConfig
import com.suhel.llamabro.sdk.config.ToolCallCapability
import com.suhel.llamabro.sdk.engine.LlamaSession
import com.suhel.llamabro.sdk.engine.internal.NativeErrorMapper
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlin.coroutines.cancellation.CancellationException

internal class LlamaChatCompletionImpl(
    private val session: LlamaSession,
    private val defaultInferenceConfig: InferenceConfig = InferenceConfig(),
    private val thinkingInferenceConfig: InferenceConfig? = null,
    private val toolCallCapability: ToolCallCapability? = null,
) : LlamaChatCompletion {

    override suspend fun initialize() {
        session.initChatTemplates()
    }

    override fun create(
        messages: List<ChatMessage>,
        options: ChatCompletionOptions,
    ): Flow<ChatCompletionEvent> = flow {
        try {
            // 1. Apply sampler from options
            val effectiveConfig = options.toInferenceConfig()
            session.updateSampler(effectiveConfig)

            // 2. Stateless: format → tokenize → prefix match → ingest delta (all in C++)
            val info = session.beginCompletion(messages, options.enableThinking)

            // 3. Configure scanner from native template info
            val thinkingTags = if (info.supportsThinking &&
                !info.thinkingStartTag.isNullOrEmpty() &&
                !info.thinkingEndTag.isNullOrEmpty()
            ) {
                TagDelimiter(info.thinkingStartTag, info.thinkingEndTag)
            } else {
                null
            }

            val allDelimiters = listOfNotNull(thinkingTags, toolCallCapability?.tags)

            // Pre-seed scanner if generation prompt ends with thinking open tag
            val preSeed = if (thinkingTags != null &&
                info.generationPrompt?.trimEnd()?.endsWith(thinkingTags.open.trimEnd()) == true
            ) thinkingTags else null

            // 4. Generate → lex → semantic → emit OpenAI-compatible deltas
            var completionTokens = 0
            val startTimeMs = System.currentTimeMillis()

            session.generateFlow()
                .lexTags(allDelimiters, initialActiveDelimiter = preSeed)
                .semanticChunks(thinkingTags, options.enableThinking, toolCallCapability)
                .collect { chunk ->
                    when (chunk) {
                        is SemanticChunk.Text -> {
                            emit(ChatCompletionEvent.Delta(content = chunk.content))
                            completionTokens++
                        }

                        is SemanticChunk.Thinking -> {
                            emit(ChatCompletionEvent.Delta(reasoningContent = chunk.content))
                            completionTokens++
                        }

                        is SemanticChunk.ToolCall -> {
                            // Future: tool call handling
                        }
                    }
                }

            // 5. Final event with usage
            val elapsed = (System.currentTimeMillis() - startTimeMs) / 1000f
            val tps = if (elapsed > 0f && completionTokens > 0) completionTokens / elapsed else 0f
            emit(
                ChatCompletionEvent.Done(
                    finishReason = "stop",
                    usage = Usage(
                        promptTokens = info.tokensCached + info.tokensIngested,
                        completionTokens = completionTokens,
                        totalTokens = info.tokensCached + info.tokensIngested + completionTokens,
                        tokensPerSecond = tps,
                    ),
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emit(ChatCompletionEvent.Error(NativeErrorMapper.map(e)))
        }
    }

    /**
     * Build an [InferenceConfig] from [ChatCompletionOptions], using model defaults
     * for any unspecified parameters.
     */
    private fun ChatCompletionOptions.toInferenceConfig(): InferenceConfig {
        val base = if (enableThinking) {
            thinkingInferenceConfig ?: defaultInferenceConfig
        } else {
            defaultInferenceConfig
        }

        return base.copy(
            temperature = temperature ?: base.temperature,
            topP = topP ?: base.topP,
            topK = topK ?: base.topK,
            minP = minP ?: base.minP,
            repeatPenalty = repeatPenalty ?: base.repeatPenalty,
            frequencyPenalty = frequencyPenalty ?: base.frequencyPenalty,
            presencePenalty = presencePenalty ?: base.presencePenalty,
            seed = seed ?: base.seed,
        )
    }
}
