package com.suhel.llamabro.sdk.engine

import com.suhel.llamabro.sdk.chat.LlamaChatSession
import com.suhel.llamabro.sdk.config.LoadableModel
import com.suhel.llamabro.sdk.model.ResourceState
import com.suhel.llamabro.sdk.toolcall.ToolCall
import com.suhel.llamabro.sdk.toolcall.ToolResult
import kotlinx.coroutines.flow.Flow

interface LlamaSession : AutoCloseable {
    val loadableModel: LoadableModel

    suspend fun setPrefixedPrompt(text: String)

    suspend fun addPrompt(prompt: String)

    suspend fun generate(): TokenGenerationResult

    fun generateFlow(): Flow<TokenGenerationResult>

    suspend fun clear()

    fun abort()

    suspend fun createChatSession(
        systemPrompt: String,
        toolCaller: (suspend (List<ToolCall>) -> List<ToolResult>)? = null,
    ): LlamaChatSession

    fun createChatSessionFlow(
        systemPrompt: String,
        toolCaller: (suspend (List<ToolCall>) -> List<ToolResult>)? = null,
    ): Flow<ResourceState<LlamaChatSession>>
}
