package com.suhel.llamabro.demo.ui.screens.chat

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.cachedIn
import androidx.paging.map
import com.suhel.llamabro.demo.data.repository.ChatRepository
import com.suhel.llamabro.demo.data.repository.ModelRepository
import com.suhel.llamabro.demo.model.ChatMessage
import com.suhel.llamabro.demo.model.MessageRole
import com.suhel.llamabro.demo.navigation.Chat
import com.suhel.llamabro.demo.toDomain
import com.suhel.llamabro.demo.toRaw
import com.suhel.llamabro.sdk.LlamaChatSession
import com.suhel.llamabro.sdk.model.LoadEvent
import com.suhel.llamabro.sdk.model.SessionConfig
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ChatViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val modelRepository: ModelRepository,
    private val chatRepository: ChatRepository
) : ViewModel() {
    private val args = savedStateHandle.toRoute<Chat>()

    companion object {
        private const val SYSTEM_PROMPT =
            "You are a helpful, concise AI assistant running entirely on-device. " +
                    "Keep answers clear and to the point."
    }

    private val _incomingMessage = MutableStateFlow<ChatMessage?>(null)
    val incomingMessage = _incomingMessage.asStateFlow()

    private var generationJob: Job? = null

    @OptIn(ExperimentalCoroutinesApi::class)
    private val chatSessionLoad = modelRepository.currentModelFlow
        .mapNotNull { (it as? LoadEvent.Ready)?.resource }
        .flatMapLatest { currentModel ->
            currentModel.engine.createSessionFlow(
                SessionConfig(
                    systemPrompt = SYSTEM_PROMPT,
                    inferenceConfig = currentModel.model.defaultInferenceConfig
                )
            )
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val chatSession = chatSessionLoad
        .mapNotNull { (it as? LoadEvent.Ready)?.resource }
        .distinctUntilChanged()
        .map { engineSession ->
            val chatSession = LlamaChatSession(engineSession)
            
            // Load and inject history before emitting the session
            val history = chatRepository.getMessages(args.conversationId)
            val sdkHistory = history.mapNotNull { entity ->
                when (entity.role) {
                    "user" -> com.suhel.llamabro.sdk.model.Message.User(entity.content)
                    "assistant" -> com.suhel.llamabro.sdk.model.Message.Assistant(entity.content, entity.thinking)
                    else -> null // Ignore unknown roles
                }
            }
            if (sdkHistory.isNotEmpty()) {
                chatSession.loadHistory(sdkHistory)
            }
            
            chatSession
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val messages = Pager(
        config = PagingConfig(
            pageSize = 20,
            enablePlaceholders = false
        ),
        pagingSourceFactory = { chatRepository.messagesPagingSource(args.conversationId) }
    )
        .flow
        .map { pagingData ->
            pagingData.map { entity -> entity.toDomain() }
        }
        .cachedIn(viewModelScope)

    fun sendMessage(text: String) {
        val session = chatSession.value ?: return

        // Persist the user message immediately
        viewModelScope.launch {
            chatRepository.addMessage(
                conversationId = args.conversationId,
                role = MessageRole.User.toRaw(),
                content = text
            )
        }

        generationJob?.cancel()
        generationJob = viewModelScope.launch {
            try {
                session.chat(text).collect { chunk ->
                    Log.e("Chunk", chunk.toString())
                    if (chunk.isComplete) {
                        // Persist the complete response
                        chatRepository.addMessage(
                            conversationId = args.conversationId,
                            role = MessageRole.Assistant.toRaw(),
                            content = chunk.contentText.orEmpty(),
                            thinking = chunk.thinkingText,
                            tokensPerSecond = chunk.tokensPerSecond
                        )
                        _incomingMessage.value = null
                    } else {
                        // Push intermediate stream chunks to the UI
                        _incomingMessage.value = ChatMessage(
                            id = "streaming",
                            role = MessageRole.Assistant,
                            content = chunk.contentText,
                            thinking = chunk.thinkingText
                        )
                    }
                }
            } finally {
                // Failsafe in case of cancellation
                _incomingMessage.value = null
            }
        }
    }

    fun stopGeneration() {
        generationJob?.cancel()
        generationJob = null
    }
}
