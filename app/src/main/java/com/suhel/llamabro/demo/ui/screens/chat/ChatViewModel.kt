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
import com.suhel.llamabro.sdk.chat.ChatCompletionEvent
import com.suhel.llamabro.sdk.chat.ChatCompletionOptions
import com.suhel.llamabro.sdk.chat.ChatMessage
import com.suhel.llamabro.sdk.config.InferenceConfig
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.flow.shareIn
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

        private const val MESSAGE_ROLE_SYSTEM = "system"
        private const val MESSAGE_ROLE_USER = "user"
        private const val MESSAGE_ROLE_ASSISTANT = "assistant"

        private val systemMessage = ChatMessage(
            role = MESSAGE_ROLE_SYSTEM,
            content = SYSTEM_PROMPT
        )
    }

    /** Null until the first message is sent (new conversation flow). */
    private val conversationId = MutableStateFlow(args.conversationId)

    private val sendMessageTrigger =
        MutableSharedFlow<Pair<String, Boolean>?>(extraBufferCapacity = 1)

    private val currentModelFlow = modelRepository.currentInferenceContextFlow
        .map { it?.model }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /**
     * User-specified inference config override. Null means use the model profile's default
     * (or thinking override when thinking is enabled). Set via [setInferenceConfig].
     */
    private val _userInferenceConfig = MutableStateFlow<InferenceConfig?>(null)

    @OptIn(ExperimentalCoroutinesApi::class)
    private val chatCompletionFlow = modelRepository.currentInferenceContextFlow
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
            session.createChatCompletionFlow()
        }
        .filterSuccess()
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    @OptIn(ExperimentalCoroutinesApi::class)
    private val messages = conversationId
        .filterNotNull()
        .flatMapLatest(chatRepository::getMessages)
        .scan(listOf(systemMessage)) { history, newMessage ->
            history + when (newMessage.role) {
                MessageRole.User -> {
                    ChatMessage(
                        role = MESSAGE_ROLE_USER,
                        content = newMessage.content,
                    )
                }

                MessageRole.Assistant -> {
                    ChatMessage(
                        role = MESSAGE_ROLE_ASSISTANT,
                        content = newMessage.content,
                        reasoningContent = newMessage.thinking,
                    )
                }
            }
        }
        .shareIn(viewModelScope, SharingStarted.Eagerly)

    /**
     * Emits an empty list until a conversation is created, then switches to
     * the real paging source for that conversation.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val pagedMessages = conversationId
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
    val incomingMessageFlow = combine(
        sendMessageTrigger,
        currentModelFlow.filterNotNull()
    ) { message, model -> message to model }
        .flatMapLatest { (message, _) ->
            if (message == null) {
                return@flatMapLatest flowOf(null)
            }

            val (prompt, enableThinking) = message

            flow<UiChatMessage?> {
                emit(
                    UiChatMessage(
                        id = STREAMING_ID,
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

                // Build the full message list for this completion (OpenAI-style stateless API)
                val chatMessages = messages.first()

                // Accumulate streaming content and thinking
                var currentContent = StringBuilder()
                var currentThinking = StringBuilder()

                emitAll(
                    chatCompletionFlow
                        .filterNotNull()
                        .flatMapLatest { chatCompletion ->
                            // Build options from user config + thinking flag
                            val userConfig = _userInferenceConfig.value
                            val options = ChatCompletionOptions(
                                temperature = userConfig?.temperature,
                                topP = userConfig?.topP,
                                topK = userConfig?.topK,
                                minP = userConfig?.minP,
                                repeatPenalty = userConfig?.repeatPenalty,
                                frequencyPenalty = userConfig?.frequencyPenalty,
                                presencePenalty = userConfig?.presencePenalty,
                                seed = userConfig?.seed,
                                enableThinking = enableThinking,
                            )

                            chatCompletion.create(chatMessages, options)
                        }
                        .map { event ->
                            when (event) {
                                is ChatCompletionEvent.Delta -> {
                                    event.content?.let { currentContent.append(it) }
                                    event.reasoningContent?.let { currentThinking.append(it) }

                                    UiChatMessage(
                                        id = STREAMING_ID,
                                        role = MessageRole.Assistant,
                                        content = currentContent
                                            .takeIf { it.isNotEmpty() }
                                            ?.toString(),
                                        thinking = currentThinking
                                            .takeIf { it.isNotEmpty() }
                                            ?.toString(),
                                    )
                                }

                                is ChatCompletionEvent.Done -> {
                                    // Save final message with tps
                                    val text = currentContent.toString()
                                    val thinking = currentThinking
                                        .takeIf { it.isNotEmpty() }
                                        ?.toString()

                                    if (text.isNotEmpty() || thinking != null) {
                                        chatRepository.addMessage(
                                            conversationId = conversationId,
                                            role = MessageRole.Assistant,
                                            content = text,
                                            thinking = thinking,
                                            tokensPerSecond = event.usage.tokensPerSecond,
                                        )
                                    }
                                    null // Signal completion
                                }

                                is ChatCompletionEvent.Error -> {
                                    UiChatMessage(
                                        id = STREAMING_ID,
                                        role = MessageRole.Assistant,
                                        error = event.error.message
                                    )
                                }
                            }
                        }
                        .catch { e ->
                            emit(
                                UiChatMessage(
                                    id = STREAMING_ID,
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
        currentModelFlow.filterNotNull(),
        incomingMessageFlow
    ) { model, incomingMessage ->
        UiChatInputConfig(
            thinkingSupported = model.profile.supportsThinking,
            isGenerating = incomingMessage != null
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, UiChatInputConfig())

    fun sendMessage(text: String, enableThinking: Boolean = false) {
        sendMessageTrigger.tryEmit(text to enableThinking)
    }

    fun stopGeneration() {
        sendMessageTrigger.tryEmit(null)
    }

    fun setInferenceConfig(config: InferenceConfig?) {
        _userInferenceConfig.value = config
    }
}
