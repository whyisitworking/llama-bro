package com.suhel.llamabro.demo.data.repository

import androidx.paging.PagingSource
import androidx.room.withTransaction
import com.suhel.llamabro.demo.data.db.AppDatabase
import com.suhel.llamabro.demo.data.db.entity.ConversationEntity
import com.suhel.llamabro.demo.data.db.entity.MessageEntity
import com.suhel.llamabro.demo.model.ChatMessage
import com.suhel.llamabro.demo.model.MessageRole
import com.suhel.llamabro.demo.toDomain
import com.suhel.llamabro.demo.toRaw
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ChatRepository @Inject constructor(private val db: AppDatabase) {
    private val convDao = db.conversationDao()
    private val msgDao = db.messageDao()

    fun conversationsPagingSource(): PagingSource<Int, ConversationEntity> =
        convDao.conversationsPagingSource()

    fun messagesPagingSource(conversationId: String): PagingSource<Int, MessageEntity> =
        msgDao.messagesPagingSource(conversationId)

    fun getMessages(conversationId: String): Flow<ChatMessage> =
        msgDao.getMessages(conversationId).map(MessageEntity::toDomain)

    suspend fun createConversation(title: String): ConversationEntity =
        withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            val entity = ConversationEntity(
                id = UUID.randomUUID().toString(),
                title = title,
                createdAt = now,
                updatedAt = now,
            )
            convDao.upsert(entity)
            entity
        }

    suspend fun addMessage(
        conversationId: String,
        role: MessageRole,
        content: String,
        thinking: String? = null,
        tokensPerSecond: Float? = null,
    ): MessageEntity = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val entity = MessageEntity(
            id = UUID.randomUUID().toString(),
            conversationId = conversationId,
            role = role.toRaw(),
            content = content,
            thinking = thinking,
            tokensPerSecond = tokensPerSecond,
            createdAt = now,
        )
        db.withTransaction {
            msgDao.insert(entity)
            convDao.touch(conversationId, now)
        }
        entity
    }

    suspend fun updateConversationTitle(id: String, title: String) =
        withContext(Dispatchers.IO) {
            convDao.updateTitle(id, title, System.currentTimeMillis())
        }

    suspend fun deleteConversation(id: String) =
        withContext(Dispatchers.IO) {
            convDao.delete(id)
        }
}
