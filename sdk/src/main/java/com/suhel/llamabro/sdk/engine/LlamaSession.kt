package com.suhel.llamabro.sdk.engine

import com.suhel.llamabro.sdk.chat.ChatMessage
import com.suhel.llamabro.sdk.chat.LlamaChatCompletion
import com.suhel.llamabro.sdk.chat.internal.LlamaChatCompletionImpl
import com.suhel.llamabro.sdk.config.InferenceConfig
import com.suhel.llamabro.sdk.config.LoadableModel
import com.suhel.llamabro.sdk.engine.internal.NativeChatTemplateInfo
import com.suhel.llamabro.sdk.engine.internal.NativeCompletionInfo
import com.suhel.llamabro.sdk.model.ResourceState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

interface LlamaSession : AutoCloseable {
    val loadableModel: LoadableModel

    /** Initialize chat templates from model GGUF metadata. Returns template capabilities. */
    suspend fun initChatTemplates(): NativeChatTemplateInfo

    /**
     * Stateless chat completion entry point (OpenAI-style).
     *
     * Formats all messages via Jinja, tokenizes, performs token-level prefix matching
     * against the KV cache, truncates on divergence, and ingests only new tokens.
     *
     * @param messages Full conversation history (system, user, assistant messages).
     * @param enableThinking Whether to enable thinking/reasoning for this request.
     * @return Completion metadata including generation prompt, thinking tags, and cache stats.
     */
    suspend fun beginCompletion(
        messages: List<ChatMessage>,
        enableThinking: Boolean,
    ): NativeCompletionInfo

    suspend fun generate(): TokenGenerationResult

    fun generateFlow(): Flow<TokenGenerationResult>

    suspend fun clear()

    fun abort()

    suspend fun updateSampler(config: InferenceConfig)

    /**
     * Create a [LlamaChatCompletion] instance backed by this session.
     *
     * The returned object is stateless from the caller's perspective — each [LlamaChatCompletion.create]
     * call accepts the full message history. Token-level prefix caching is handled internally.
     *
     * Call [LlamaChatCompletion.initialize] before the first [LlamaChatCompletion.create] call.
     */
    fun createChatCompletion(): LlamaChatCompletion {
        val profile = loadableModel.profile
        return LlamaChatCompletionImpl(
            session = this,
            defaultInferenceConfig = profile.defaultInferenceConfig,
            thinkingInferenceConfig = profile.thinkingInferenceConfig,
            toolCallCapability = profile.toolCall,
        )
    }

    /**
     * Create a [LlamaChatCompletion] instance with initialization as a reactive flow.
     *
     * Emits [ResourceState.Loading], then [ResourceState.Success] with the ready-to-use instance,
     * or [ResourceState.Failure] on error.
     */
    fun createChatCompletionFlow(): Flow<ResourceState<LlamaChatCompletion>> = flow {
        emit(ResourceState.Loading())
        try {
            val completion = createChatCompletion()
            withContext(Dispatchers.IO) { completion.initialize() }
            emit(ResourceState.Success(completion))
        } catch (e: Exception) {
            emit(ResourceState.Failure(
                com.suhel.llamabro.sdk.engine.internal.NativeErrorMapper.map(e)
            ))
        }
    }.flowOn(Dispatchers.IO)
}
