package com.suhel.llamabro.demo.ui.screens.conversations

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.cachedIn
import androidx.paging.map
import com.suhel.llamabro.demo.data.repository.ChatRepository
import com.suhel.llamabro.demo.toDomain
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class ConversationsViewModel @Inject constructor(
    private val chatRepository: ChatRepository,
) : ViewModel() {
    val conversations = Pager(
        config = PagingConfig(
            pageSize = 20
        ),
        pagingSourceFactory = { chatRepository.conversationsPagingSource() }
    )
        .flow
        .map { pagingData ->
            pagingData.map { it.toDomain() }
        }
        .cachedIn(viewModelScope)

    fun newConversation(onCreated: (String) -> Unit) {
        viewModelScope.launch {
            val conv = withContext(Dispatchers.IO) {
                chatRepository.createConversation()
            }

            withContext(Dispatchers.Main) {
                onCreated(conv.id)
            }
        }
    }

    fun deleteConversation(conversationId: String) {
        viewModelScope.launch {
            chatRepository.deleteConversation(conversationId)
        }
    }
}
