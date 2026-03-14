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
import com.suhel.llamabro.demo.asMessageRole
import com.suhel.llamabro.demo.data.repository.ChatRepository
import com.suhel.llamabro.demo.data.repository.ModelRepository
import com.suhel.llamabro.demo.model.ChatMessage
import com.suhel.llamabro.demo.model.MessageRole
import com.suhel.llamabro.demo.navigation.Chat
import com.suhel.llamabro.demo.toDomain
import com.suhel.llamabro.demo.toRaw
import com.suhel.llamabro.sdk.model.LoadEvent
import com.suhel.llamabro.sdk.model.Message
import com.suhel.llamabro.sdk.model.SessionConfig
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
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
        private val SYSTEM_PROMPT = """
            (You are a highly capable, precise, and brutally efficient AI assistant. Your primary directive is to provide maximum value with minimum token overhead.
            
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
            * Prioritize factual accuracy over conversational politeness.)
        """.trimIndent()
    }

    private val _incomingMessage = MutableStateFlow<ChatMessage?>(null)
    val incomingMessage = _incomingMessage.asStateFlow()

    private var generationJob: Job? = null

    @OptIn(ExperimentalCoroutinesApi::class)
    private val chatSession = modelRepository.currentModelFlow
        .mapNotNull { (it as? LoadEvent.Ready)?.resource }
        .flatMapLatest { currentModel ->
            currentModel.engine.createSessionFlow(
                SessionConfig(inferenceConfig = currentModel.model.defaultInferenceConfig)
            ).mapNotNull { (it as? LoadEvent.Ready)?.resource }
                .distinctUntilChanged()
                .map { session ->
                    val chatSession = session.createChatSession(SYSTEM_PROMPT)
                    val history = chatRepository.getMessages(args.conversationId)
                    val sdkHistory = history.map { entity ->
                        when (entity.role.asMessageRole()) {
                            MessageRole.User -> Message.User(entity.content)
                            MessageRole.Assistant -> Message.Assistant(
                                entity.content, entity.thinking
                            )
                        }
                    }
                    if (sdkHistory.isNotEmpty()) {
                        chatSession.loadHistory(sdkHistory)
                    }

                    chatSession
                }
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

        val oldJob = generationJob
        generationJob = viewModelScope.launch {
            oldJob?.cancelAndJoin()
            try {
                session.completion(text).collect { chunk ->
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
        viewModelScope.launch {
            generationJob?.cancelAndJoin()
            generationJob = null
        }
    }
}
