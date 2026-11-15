package com.suhel.llamabro.demo.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/** One row of the Chat list: the chat plus its last spoken line. */
data class ChatSummaryRow(
    val id: Long,
    val title: String,
    val updatedAt: Long,
    val modelKey: String,
    val lastText: String,
    val lastFromUser: Boolean,
    /** The last line's state (`done`, `stopped`, …), empty for a chat with no message. */
    val lastState: String,
)

/** When a model last answered in any chat. */
data class ModelUseRow(val modelKey: String, val lastUsed: Long)

@Dao
abstract class ChatDao {
    @Insert abstract suspend fun insertChat(chat: ChatEntity): Long

    @Update abstract suspend fun updateChat(chat: ChatEntity)

    @Query("SELECT * FROM chats WHERE id = :id")
    abstract suspend fun chat(id: Long): ChatEntity?

    @Query("SELECT sampling FROM chats WHERE id = :id")
    abstract fun sampling(id: Long): Flow<String?>

    /**
     * Newest first. [pattern] is a LIKE pattern (escaped with `\`) or empty for every chat; it matches titles and
     * message text.
     */
    @Query(
        """
        SELECT c.id AS id, c.title AS title, c.updatedAt AS updatedAt, c.modelKey AS modelKey,
          COALESCE((SELECT CASE WHEN m.text = '' AND m.images != '' THEN 'Image' ELSE m.text END
                    FROM messages m WHERE m.chatId = c.id AND m.kind != 'switch'
                    ORDER BY m.position DESC LIMIT 1), '') AS lastText,
          COALESCE((SELECT m.kind = 'user' FROM messages m WHERE m.chatId = c.id AND m.kind != 'switch'
                    ORDER BY m.position DESC LIMIT 1), 0) AS lastFromUser,
          COALESCE((SELECT m.state FROM messages m WHERE m.chatId = c.id AND m.kind != 'switch'
                    ORDER BY m.position DESC LIMIT 1), '') AS lastState
        FROM chats c
        WHERE :pattern = '' OR c.title LIKE :pattern ESCAPE '\' OR EXISTS (
            SELECT 1 FROM messages m WHERE m.chatId = c.id AND m.text LIKE :pattern ESCAPE '\')
        ORDER BY c.updatedAt DESC, c.id DESC
        """,
    )
    abstract fun summaries(pattern: String): Flow<List<ChatSummaryRow>>

    @Query("SELECT * FROM messages WHERE chatId = :chatId ORDER BY position")
    abstract fun messages(chatId: Long): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE chatId = :chatId ORDER BY position")
    abstract suspend fun messagesOf(chatId: Long): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE id = :id")
    abstract suspend fun message(id: Long): MessageEntity?

    @Insert abstract suspend fun insertMessage(message: MessageEntity): Long

    @Update abstract suspend fun updateMessage(message: MessageEntity)

    @Query("SELECT COALESCE(MAX(position), -1) + 1 FROM messages WHERE chatId = :chatId")
    abstract suspend fun nextPosition(chatId: Long): Int

    @Query("SELECT COUNT(*) FROM messages WHERE chatId = :chatId AND kind = 'user'")
    abstract suspend fun userMessageCount(chatId: Long): Int

    @Query("SELECT images FROM messages WHERE chatId = :chatId AND images != ''")
    abstract suspend fun imagesOf(chatId: Long): List<String>

    @Query("DELETE FROM messages WHERE chatId = :chatId AND position >= :position")
    abstract suspend fun deleteFrom(chatId: Long, position: Int)

    @Query("DELETE FROM chats WHERE id = :chatId")
    abstract suspend fun deleteChat(chatId: Long)

    /** Each model's latest reply, for the library's "last used" and its sort by recent use. */
    @Query(
        "SELECT modelKey, MAX(sentAt) AS lastUsed FROM messages " +
            "WHERE kind = 'assistant' AND modelKey IS NOT NULL GROUP BY modelKey",
    )
    abstract fun lastUseByModel(): Flow<List<ModelUseRow>>

    @Query("UPDATE messages SET state = 'stopped' WHERE state = 'streaming'")
    abstract suspend fun stopStreaming(): Int

    /**
     * Appends [message] at the next position and returns its id, in one transaction with the chat's bookkeeping:
     * `updatedAt` moves to [now]; a model-carrying row sets the chat's `modelKey`; the first user message replaces the
     * placeholder title with [title].
     */
    @Transaction
    open suspend fun append(message: MessageEntity, now: Long, title: String?): Long {
        val id = insertMessage(message.copy(position = nextPosition(message.chatId)))
        val chat = chat(message.chatId) ?: return id
        updateChat(
            chat.copy(
                updatedAt = now,
                title =
                    if (message.kind == "user" && title != null &&
                        chat.title == NEW_CHAT_TITLE
                    ) {
                        title
                    } else {
                        chat.title
                    },
                modelKey = message.modelKey ?: chat.modelKey,
            ),
        )
        return id
    }

    /**
     * Reads row [id], applies [change] and writes it back in one transaction; [touchAt] also moves the chat's
     * `updatedAt`.
     */
    @Transaction
    open suspend fun modify(id: Long, touchAt: Long?, change: (MessageEntity) -> MessageEntity) {
        val old = message(id) ?: return
        updateMessage(change(old))
        if (touchAt != null) chat(old.chatId)?.let { updateChat(it.copy(updatedAt = touchAt)) }
    }
}

/** The placeholder title of a chat that has no user message yet. */
const val NEW_CHAT_TITLE = "New chat"
