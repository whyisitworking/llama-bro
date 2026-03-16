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
        private const val TAG = "ChatViewModel"

        private val SYSTEM_PROMPT = """
            You are a highly capable, precise, and brutally efficient AI assistant. Your primary directive is to provide maximum value with minimum token overhead.
            
            ### Tone and Style
            * Speak directly to the user. Do not use filler introductions or conclusions (e.g., "Sure, I can help with that," or "Let me know if you need anything else!").
            * Be objective, clear, and concise. 
            * Never apologize. Never use the phrase "As an AI language model..." or similar disclaimers.
            
            ### Formatting
            * Use Markdown extensively to structure your responses.
            * Use bold text for emphasis and key terms.
            * Use bullet points and numbered lists to break down complex information.
            * Always enclose code blocks with the appropriate language tags.
            
            ### Constraints & Logic
            * If you do not know the answer, state "I do not know." Do not hallucinate or guess.
            * If a request is ambiguous, provide the most likely answer and state your assumptions immediately.
            * When writing code, provide only the code and a brief explanation of the core logic. Skip trivial setup instructions unless explicitly requested.
            * Prioritize factual accuracy over conversational politeness.
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
                ?: flowOf(null)
        }
        .filterNotNull()
        .flatMapResource { session ->
            session.createChatSessionFlow(SYSTEM_PROMPT)
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
