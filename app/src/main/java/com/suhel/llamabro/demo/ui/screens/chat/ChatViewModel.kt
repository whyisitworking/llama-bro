package com.suhel.llamabro.demo.ui.screens.chat

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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flattenConcat
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
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

    private val sendMessageTrigger = MutableSharedFlow<String>()

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
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000, 0), null)

    private val chatSession = chatSessionLoad
        .mapNotNull { (it as? LoadEvent.Ready)?.resource }
        .distinctUntilChanged()
        .map { LlamaChatSession(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000, 0), null)

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

    @OptIn(ExperimentalCoroutinesApi::class)
    val incomingMessage = combine(
        chatSession.filterNotNull(),
        sendMessageTrigger
            .distinctUntilChanged()
            .onEach {
                withContext(Dispatchers.IO) {
                    chatRepository.addMessage(
                        conversationId = args.conversationId,
                        role = MessageRole.User.toRaw(),
                        content = it
                    )
                }
            }
    ) { session, message -> session.chat(message) }
        .flattenConcat()
        .onEach { generation ->
            if (generation.isComplete) {
                withContext(Dispatchers.IO) {
                    chatRepository.addMessage(
                        conversationId = args.conversationId,
                        role = MessageRole.Assistant.toRaw(),
                        content = generation.contentText.orEmpty(),
                        thinking = generation.thinkingText,
                        tokensPerSecond = generation.tokensPerSecond
                    )
                }
            }
        }
        .map { generation ->
            if (generation.isComplete) null else ChatMessage(
                id = "streaming",
                role = MessageRole.Assistant,
                thinking = generation.thinkingText
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_0000), null)

    fun sendMessage(text: String) {
        sendMessageTrigger.tryEmit(text)
    }
}
