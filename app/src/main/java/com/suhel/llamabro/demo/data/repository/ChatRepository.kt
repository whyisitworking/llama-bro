package com.suhel.llamabro.demo.data.repository

import androidx.paging.PagingSource
import androidx.room.withTransaction
import com.suhel.llamabro.demo.data.db.AppDatabase
import com.suhel.llamabro.demo.data.db.entity.ConversationEntity
import com.suhel.llamabro.demo.data.db.entity.MessageEntity
import kotlinx.coroutines.flow.Flow
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

    suspend fun createConversation(): ConversationEntity {
        val now = System.currentTimeMillis()
        val entity = ConversationEntity(
            id = UUID.randomUUID().toString(),
            title = "New conversation",
            createdAt = now,
            updatedAt = now,
        )
        convDao.upsert(entity)
        return entity
    }

    suspend fun addMessage(
        conversationId: String,
        role: String,
        content: String,
        thinking: String? = null,
        tokensPerSecond: Float? = null,
    ): MessageEntity {
        val now = System.currentTimeMillis()
        val entity = MessageEntity(
            id = UUID.randomUUID().toString(),
            conversationId = conversationId,
            role = role,
            content = content,
            thinking = thinking,
            tokensPerSecond = tokensPerSecond,
            createdAt = now,
        )
        db.withTransaction {
            msgDao.insert(entity)
            convDao.touch(conversationId, now)
        }
        return entity
    }

    suspend fun updateConversationTitle(id: String, title: String) {
        convDao.updateTitle(id, title, System.currentTimeMillis())
    }

    suspend fun deleteConversation(id: String) {
        convDao.delete(id)
    }
}
