package com.suhel.llamabro.demo.ui.screens.chat

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.map
import com.suhel.llamabro.demo.asMessageRole
import com.suhel.llamabro.demo.data.repository.ChatRepository
import com.suhel.llamabro.demo.data.repository.ModelRepository
import com.suhel.llamabro.demo.model.MessageRole
import com.suhel.llamabro.demo.navigation.Chat
import com.suhel.llamabro.sdk.chat.ChatEvent
import com.suhel.llamabro.sdk.config.SessionConfig
import com.suhel.llamabro.sdk.model.filterSuccess
import com.suhel.llamabro.sdk.model.flatMapResource
import com.suhel.llamabro.sdk.model.getOrNull
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.updateAndGet
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
        Your name is Llama Bro.
        You are extremely intelligent, funny and sassy.
        """.trimIndent()

        private const val MAX_TITLE_LENGTH = 50

        private const val STREAMING_ID = "streaming"
    }

    /** Null until the first message is sent (new conversation flow). */
    private val conversationId = MutableStateFlow(args.conversationId)

    private val sendMessageTrigger =
        MutableSharedFlow<Pair<String, Boolean>?>(extraBufferCapacity = 1)

    private val currentModelFlow = modelRepository.currentInferenceContextFlow
        .map { it?.model }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    @OptIn(ExperimentalCoroutinesApi::class)
    private val chatSessionFlow = modelRepository.currentInferenceContextFlow
        .flatMapLatest { currentInferenceContext ->
            currentInferenceContext?.engine
                ?.getOrNull()
                ?.createSessionFlow(
                    SessionConfig(
                        contextSize = 8192,
                        inferenceConfig = currentInferenceContext.model.profile.defaultInferenceConfig
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
            // For existing conversations, restore the message history.
            val id = conversationId.value ?: return@onEach
            val history = chatRepository.getMessages(id)
                .map { chatMessage ->
                    when (chatMessage.role) {
                        MessageRole.User -> ChatEvent.UserEvent(
                            content = chatMessage.content,
                            think = false
                        )

                        MessageRole.Assistant -> ChatEvent.AssistantEvent(
                            parts = listOf(
                                ChatEvent.AssistantEvent.Part.TextPart(chatMessage.content)
                            )
                        )
                    }
                }

            chatSession.feedHistory(history)
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /**
     * Emits an empty list until a conversation is created, then switches to
     * the real paging source for that conversation.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val messages = conversationId
        .flatMapLatest { conversationId ->
            if (conversationId == null) {
                flowOf(PagingData.empty())
            } else {
                Pager(
                    config = PagingConfig(
                        pageSize = 20,
                        enablePlaceholders = false
                    ),
                    pagingSourceFactory = { chatRepository.messagesPagingSource(conversationId) }
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
            }
        }
        .cachedIn(viewModelScope)

    @OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
    val incomingMessage = combine(
        sendMessageTrigger,
        currentModelFlow.filterNotNull()
    ) { message, model -> message to model }
        .flatMapLatest { (message, model) ->
            if (message == null) {
                return@flatMapLatest flowOf(null)
            }

            val (prompt, enableThinking) = message

            flow<UiChatMessage?> {
                emit(
                    UiChatMessage(
                        id = "streaming",
                        role = MessageRole.Assistant,
                        isProcessing = true,
                    )
                )

                // Lazily create the conversation on the first message, deriving
                // its title from the first non-blank line of the user's prompt.
                val conversationId = conversationId.updateAndGet { id ->
                    if (id == null) {
                        val title = prompt.lines()
                            .firstOrNull { it.isNotBlank() }
                            ?.trim()
                            ?.take(MAX_TITLE_LENGTH)
                            ?: "New conversation"
                        val conv = chatRepository.createConversation(title)
                        conv.id
                    } else {
                        id
                    }
                } ?: return@flow

                chatRepository.addMessage(
                    conversationId = conversationId,
                    role = MessageRole.User,
                    content = prompt
                )

                emitAll(
                    chatSessionFlow
                        .filterNotNull()
                        .flatMapLatest { chatSession ->
                            chatSession.completion(
                                ChatEvent.UserEvent(
                                    content = prompt,
                                    think = enableThinking
                                )
                            )
                        }
                        .onEach { chunk ->
                            if (chunk.isComplete && !chunk.isError
                                && (chunk.message.text.isNotEmpty() || chunk.message.thinkingText.isNotEmpty())
                            ) {
                                chatRepository.addMessage(
                                    conversationId = conversationId,
                                    role = MessageRole.Assistant,
                                    content = chunk.message.text,
                                    thinking = chunk.message.thinkingText.takeIf { it.isNotEmpty() },
                                    tokensPerSecond = chunk.tokensPerSecond
                                )
                            }
                        }
                        .map { chunk ->
                            when {
                                chunk.isError -> UiChatMessage(
                                    id = "streaming",
                                    role = MessageRole.Assistant,
                                    error = chunk.error
                                )

                                chunk.isComplete -> null

                                else -> UiChatMessage(
                                    id = "streaming",
                                    role = MessageRole.Assistant,
                                    content = chunk.message.text.takeIf { it.isNotEmpty() },
                                    thinking = chunk.message.thinkingText.takeIf { it.isNotEmpty() }
                                )
                            }
                        }
                        .catch { e ->
                            // Safety net for unexpected non-LlamaError exceptions.
                            emit(
                                UiChatMessage(
                                    id = "streaming",
                                    role = MessageRole.Assistant,
                                    error = e.message
                                )
                            )
                        }
                )
            }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val inputConfig = combine(
        currentModelFlow.map { it?.profile?.supportsThinking == true },
        incomingMessage.map { it != null }
    ) { supportsThinking, isGenerating ->
        UiChatInputConfig(
            thinkingSupported = supportsThinking,
            isGenerating = isGenerating
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, UiChatInputConfig())

    fun sendMessage(text: String, enableThinking: Boolean = false) {
        sendMessageTrigger.tryEmit(text to enableThinking)
    }

    fun stopGeneration() {
        sendMessageTrigger.tryEmit(null)
    }
}
