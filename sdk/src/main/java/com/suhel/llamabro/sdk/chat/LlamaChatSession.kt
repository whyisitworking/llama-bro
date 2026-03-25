package com.suhel.llamabro.sdk.chat

import com.suhel.llamabro.sdk.config.InferenceConfig
import com.suhel.llamabro.sdk.toolcall.ToolDefinition
import kotlinx.coroutines.flow.Flow

/**
 * A perfectly encapsulated session representing an active stateful conversation with a model.
 */
interface LlamaChatSession {
    suspend fun initialize(tools: List<ToolDefinition> = emptyList())
    suspend fun feedHistory(history: List<ChatEvent>)
    fun completion(
        message: ChatEvent.UserEvent,
        inferenceConfig: InferenceConfig? = null,
    ): Flow<CompletionResult>
}
