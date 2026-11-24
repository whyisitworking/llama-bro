package com.suhel.llamabro.demo.chat

import com.suhel.llamabro.demo.data.db.ChatDao
import com.suhel.llamabro.demo.data.db.ChatEntity
import com.suhel.llamabro.demo.data.db.ChatSummaryRow
import com.suhel.llamabro.demo.data.db.MessageEntity
import com.suhel.llamabro.demo.data.db.ModelUseRow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicInteger

/** Room's DAO in memory, for the engine's JVM tests. Counts message updates so tests can bound database writes. */
class FakeChatDao : ChatDao() {
    private val chats = MutableStateFlow<List<ChatEntity>>(emptyList())
    private val messages = MutableStateFlow<List<MessageEntity>>(emptyList())
    private val chatIds =
        java.util.concurrent.atomic
            .AtomicLong()
    private val messageIds =
        java.util.concurrent.atomic
            .AtomicLong()
    val messageUpdates = AtomicInteger()

    override suspend fun insertChat(chat: ChatEntity): Long {
        val id = chatIds.incrementAndGet()
        chats.update { it + chat.copy(id = id) }
        return id
    }

    override suspend fun updateChat(chat: ChatEntity) {
        chats.update { list -> list.map { if (it.id == chat.id) chat else it } }
    }

    override suspend fun chat(id: Long): ChatEntity? = chats.value.firstOrNull { it.id == id }

    override fun sampling(id: Long): Flow<String?> = chats.map { list -> list.firstOrNull { it.id == id }?.sampling }

    override fun lastUseByModel(): Flow<List<ModelUseRow>> =
        messages.map { all ->
            all
                .filter { it.kind == "assistant" && it.modelKey != null }
                .groupBy { it.modelKey!! }
                .map { (key, rows) -> ModelUseRow(key, rows.maxOf { it.sentAt }) }
        }

    override fun summaries(pattern: String): Flow<List<ChatSummaryRow>> =
        combine(chats, messages) { list, all ->
            val needle =
                pattern
                    .replace(
                        "\\%",
                        "\u0000",
                    ).trim('%')
                    .replace("\u0000", "%")
                    .replace("\\_", "_")
                    .replace("\\\\", "\\")
            list
                .filter { c ->
                    needle.isEmpty() || c.title.contains(needle, ignoreCase = true) ||
                        all.any { it.chatId == c.id && it.text.contains(needle, ignoreCase = true) }
                }.sortedWith(compareByDescending<ChatEntity> { it.updatedAt }.thenByDescending { it.id })
                .map { c ->
                    val last = all.filter { it.chatId == c.id && it.kind != "switch" }.maxByOrNull { it.position }
                    ChatSummaryRow(
                        c.id,
                        c.title,
                        c.updatedAt,
                        c.modelKey,
                        last?.text ?: "",
                        last?.kind == "user",
                        last?.state ?: "",
                    )
                }
        }

    override fun messages(chatId: Long): Flow<List<MessageEntity>> =
        messages.map { all -> all.filter { it.chatId == chatId }.sortedBy { it.position } }

    override suspend fun messagesOf(chatId: Long): List<MessageEntity> =
        messages.value.filter { it.chatId == chatId }.sortedBy { it.position }

    override suspend fun message(id: Long): MessageEntity? = messages.value.firstOrNull { it.id == id }

    override suspend fun insertMessage(message: MessageEntity): Long {
        // Room's foreign key: a message for a chat that is gone (deleted meanwhile) is refused, as SQLite refuses it
        check(chats.value.any { it.id == message.chatId }) { "FOREIGN KEY constraint failed: chat ${message.chatId}" }
        val id = messageIds.incrementAndGet()
        messages.update { it + message.copy(id = id) }
        return id
    }

    override suspend fun updateMessage(message: MessageEntity) {
        messageUpdates.incrementAndGet()
        messages.update { list -> list.map { if (it.id == message.id) message else it } }
    }

    override suspend fun nextPosition(chatId: Long): Int =
        (messages.value.filter { it.chatId == chatId }.maxOfOrNull { it.position } ?: -1) + 1

    override suspend fun userMessageCount(chatId: Long): Int =
        messages.value.count { it.chatId == chatId && it.kind == "user" }

    override suspend fun imagesOf(chatId: Long): List<String> =
        messages.value.filter { it.chatId == chatId && it.images.isNotEmpty() }.map { it.images }

    override suspend fun audioOf(chatId: Long): List<String> =
        messages.value.filter { it.chatId == chatId }.mapNotNull { it.audio }

    override suspend fun audioFrom(chatId: Long, position: Int): List<String> =
        messages.value.filter { it.chatId == chatId && it.position >= position }.mapNotNull { it.audio }

    override suspend fun imagesFrom(chatId: Long, position: Int): List<String> =
        messages.value
            .filter {
                it.chatId == chatId && it.position >= position && it.images.isNotEmpty()
            }.map { it.images }

    override suspend fun allAudio(): List<String> = messages.value.mapNotNull { it.audio }

    override suspend fun allImages(): List<String> = messages.value.map { it.images }.filter { it.isNotEmpty() }

    override suspend fun deleteFrom(chatId: Long, position: Int) {
        messages.update { list -> list.filterNot { it.chatId == chatId && it.position >= position } }
    }

    override suspend fun deleteChat(chatId: Long) {
        chats.update { list -> list.filterNot { it.id == chatId } }
        messages.update { list -> list.filterNot { it.chatId == chatId } }
    }

    override suspend fun stopStreaming(): Int {
        val n = messages.value.count { it.state == "streaming" }
        messages.update { list -> list.map { if (it.state == "streaming") it.copy(state = "stopped") else it } }
        return n
    }
}
