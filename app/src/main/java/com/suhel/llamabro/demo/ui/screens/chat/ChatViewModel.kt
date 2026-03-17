package com.suhel.llamabro.demo.ui.screens.chat

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.cachedIn
import androidx.paging.map
import com.suhel.llamabro.demo.asMessageRole
import com.suhel.llamabro.demo.data.repository.ChatRepository
import com.suhel.llamabro.demo.data.repository.ModelRepository
import com.suhel.llamabro.demo.model.MessageRole
import com.suhel.llamabro.demo.navigation.Chat
import com.suhel.llamabro.sdk.model.Message
import com.suhel.llamabro.sdk.model.SessionConfig
import com.suhel.llamabro.sdk.model.filterSuccess
import com.suhel.llamabro.sdk.model.flatMapResource
import com.suhel.llamabro.sdk.model.getOrNull
import com.suhel.llamabro.sdk.model.mapSuccess
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class ChatViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    modelRepository: ModelRepository,
    private val chatRepository: ChatRepository
) : ViewModel() {
    private val args = savedStateHandle.toRoute<Chat>()

    companion object {
        private val SYSTEM_PROMPT = """
        You are Llama Bro, a highly efficient AI assistant running locally on an Android device.
        Follow these rules strictly:
        1. Be direct, concise, and highly accurate.
        2. Do not use filler words, apologies, or preamble.
        3. Format your answers using markdown for readability.
        4. Answer the user's question immediately.
        """.trimIndent()
    }

    private val sendMessageTrigger = MutableSharedFlow<String?>(extraBufferCapacity = 1)

    @OptIn(ExperimentalCoroutinesApi::class)
    private val chatSessionFlow = modelRepository.currentInferenceContextFlow
        .flatMapLatest { currentInferenceContext ->
            currentInferenceContext?.engine
                ?.getOrNull()
                ?.createSessionFlow(
                    SessionConfig(
                        inferenceConfig = currentInferenceContext.model.defaultInferenceConfig
                    )
                )
                ?.mapSuccess { session ->
                    currentInferenceContext.model to session
                }
                ?: flowOf(null)
        }
        .filterNotNull()
        .flatMapResource { (model, session) ->
            session.createChatSessionFlow(model.defaultSystemPrompt ?: SYSTEM_PROMPT)
        }
        .filterSuccess()
        .onEach { chatSession ->
            val history = chatRepository.getMessages(args.conversationId)
                .map { chatMessage ->
                    when (chatMessage.role) {
                        MessageRole.User -> Message.User(chatMessage.content)
                        MessageRole.Assistant -> Message.Assistant(chatMessage.content)
                    }
                }

            chatSession.loadHistory(history)
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
            pagingData.map { entity ->
                UiChatMessage(
                    id = entity.id,
                    role = entity.role.asMessageRole(),
                    content = entity.content,
                    thinking = entity.thinking,
                    tokensPerSecond = entity.tokensPerSecond,
                    timestamp = entity.createdAt
                )
            }
        }
        .cachedIn(viewModelScope)

    @OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
    val incomingMessage = sendMessageTrigger
        .distinctUntilChanged()
        .flatMapLatest { message ->
            if (message != null) {
                flow {
                    emit(
                        UiChatMessage(
                            id = "streaming",
                            role = MessageRole.Assistant,
                            isProcessing = true,
                        )
                    )

                    chatRepository.addMessage(
                        conversationId = args.conversationId,
                        role = MessageRole.User,
                        content = message
                    )

                    val session = chatSessionFlow.filterNotNull().first()

                    emitAll(
                        session.completion(message)
                            .map { chunk ->
                                if (chunk.isComplete && chunk.contentText != null) {
                                    chatRepository.addMessage(
                                        conversationId = args.conversationId,
                                        role = MessageRole.Assistant,
                                        content = chunk.contentText!!,
                                        thinking = chunk.thinkingText,
                                        tokensPerSecond = chunk.tokensPerSecond
                                    )

                                    null
                                } else {
                                    UiChatMessage(
                                        id = "streaming",
                                        role = MessageRole.Assistant,
                                        content = chunk.contentText,
                                        thinking = chunk.thinkingText
                                    )
                                }
                            }
                    )
                }
            } else {
                flowOf(null as UiChatMessage?)
            }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun sendMessage(text: String) {
        sendMessageTrigger.tryEmit(text)
    }

    fun stopGeneration() {
        sendMessageTrigger.tryEmit(null)
    }
}
