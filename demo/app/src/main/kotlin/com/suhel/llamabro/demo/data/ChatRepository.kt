package com.suhel.llamabro.demo.data

import com.suhel.llamabro.demo.data.db.ChatDao
import com.suhel.llamabro.demo.data.db.ChatEntity
import com.suhel.llamabro.demo.data.db.MessageEntity
import com.suhel.llamabro.demo.data.db.NEW_CHAT_TITLE
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.io.File

enum class MessageKind { User, Assistant, Switch }

enum class MessageState { Done, Held, Streaming, Stopped, Failed }

data class ChatSummary(
    val id: Long,
    val title: String,
    val updatedAt: Long,
    val modelKey: String,
    val lastText: String,
    val lastFromUser: Boolean,
    /** True when the last reply was stopped by the user, so a reply with no text can say so. */
    val lastStopped: Boolean = false,
    /** True for a chat that has no message yet (nothing to preview). */
    val isEmpty: Boolean = false,
)

/** [MessageEntity] with typed [kind] and [state] and [images] resolved to files. */
data class StoredMessage(
    val id: Long,
    val chatId: Long,
    val position: Int,
    val kind: MessageKind,
    val text: String,
    val reasoning: String?,
    val reasoningMillis: Long?,
    val images: List<File>,
    val modelKey: String?,
    val sentAt: Long,
    val wordsPerSecond: Double?,
    val state: MessageState,
    val failure: String?,
)

/** A row to append; [images] are file names in the [ImageStore]; [sentAt] defaults to the repository's clock. */
data class NewMessage(
    val kind: MessageKind,
    val text: String,
    val images: List<String> = emptyList(),
    val modelKey: String? = null,
    val state: MessageState = MessageState.Done,
    val reasoning: String? = null,
    val sentAt: Long? = null,
)

/**
 * A partial update: a null field is left as it was. Setting [state] to anything but `Failed` clears [failure] unless
 * a new one is given.
 */
data class MessageChange(
    val text: String? = null,
    val reasoning: String? = null,
    val reasoningMillis: Long? = null,
    val wordsPerSecond: Double? = null,
    val state: MessageState? = null,
    val failure: String? = null,
)

/** Room is the source of truth for chats (pd-design §4.5). Every call runs on Room's own threads. */
class ChatRepository(private val dao: ChatDao, private val imageStore: ImageStore, private val clock: () -> Long) {
    /** Newest first; a non-blank [query] keeps chats whose title or any message text contains it. */
    fun chats(query: String = ""): Flow<List<ChatSummary>> {
        val q = query.trim()
        val pattern =
            if (q.isEmpty()) {
                ""
            } else {
                "%" + q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") +
                    "%"
            }
        return dao.summaries(pattern).map { rows ->
            rows.map {
                ChatSummary(
                    it.id,
                    it.title,
                    it.updatedAt,
                    it.modelKey,
                    it.lastText,
                    it.lastFromUser,
                    lastStopped = it.lastState == "stopped",
                    isEmpty = it.lastState.isEmpty(),
                )
            }
        }
    }

    fun messages(chatId: Long): Flow<List<StoredMessage>> =
        dao.messages(chatId).map { rows -> rows.map { it.toStored() } }

    suspend fun createChat(modelKey: String): Long {
        val now = clock()
        return dao.insertChat(ChatEntity(title = NEW_CHAT_TITLE, createdAt = now, updatedAt = now, modelKey = modelKey))
    }

    /** Appends at the end; the first user message becomes the chat's title (see [titleFrom]). */
    suspend fun append(chatId: Long, message: NewMessage): Long {
        val now = clock()
        val entity =
            MessageEntity(
                chatId = chatId,
                position = 0,
                kind = message.kind.key,
                text = message.text,
                reasoning = message.reasoning,
                images = message.images.joinToString("\n"),
                modelKey = message.modelKey,
                sentAt = message.sentAt ?: now,
                state = message.state.key,
            )
        val title = if (message.kind == MessageKind.User) titleFrom(message.text, message.images.isNotEmpty()) else null
        return dao.append(entity, now, title)
    }

    /** Only a change of [MessageChange.state] counts as activity (streaming text does not reorder the Chat list). */
    suspend fun update(id: Long, change: MessageChange) {
        dao.modify(id, if (change.state != null) clock() else null) { old ->
            old.copy(
                text = change.text ?: old.text,
                reasoning = change.reasoning ?: old.reasoning,
                reasoningMillis = change.reasoningMillis ?: old.reasoningMillis,
                wordsPerSecond = change.wordsPerSecond ?: old.wordsPerSecond,
                state = change.state?.key ?: old.state,
                failure =
                    change.failure
                        ?: if (change.state != null && change.state != MessageState.Failed) null else old.failure,
            )
        }
    }

    /** Removes the message at [position] and everything after it (edit, regenerate). */
    suspend fun truncateFrom(chatId: Long, position: Int) = dao.deleteFrom(chatId, position)

    /** The model of the chat's last turn, or null when the chat is gone. */
    suspend fun modelKey(chatId: Long): String? = dao.chat(chatId)?.modelKey

    /** Records the model of the chat's last turn (switching models). */
    suspend fun setModel(chatId: Long, modelKey: String) {
        val chat = dao.chat(chatId) ?: return
        dao.updateChat(chat.copy(modelKey = modelKey))
    }

    /** Titles are never empty: a blank [title] is rejected. */
    suspend fun rename(chatId: Long, title: String) {
        require(title.isNotBlank()) { "a chat title cannot be blank" }
        val chat = dao.chat(chatId) ?: return
        dao.updateChat(chat.copy(title = title.trim()))
    }

    /** Deletes the chat, its messages (cascade) and the images they hold. */
    suspend fun delete(chatId: Long) {
        val names = dao.imagesOf(chatId).flatMap(::imageNames)
        dao.deleteChat(chatId)
        imageStore.delete(names)
    }

    /** After a process death: a reply that was `Streaming` becomes `Stopped` with the text it had; `Held` stays. */
    suspend fun recoverInterrupted() {
        dao.stopStreaming()
    }

    private fun MessageEntity.toStored() =
        StoredMessage(
            id = id,
            chatId = chatId,
            position = position,
            kind = MessageKind.entries.first { it.key == kind },
            text = text,
            reasoning = reasoning,
            reasoningMillis = reasoningMillis,
            images = imageNames(this.images).map(imageStore::file),
            modelKey = modelKey,
            sentAt = sentAt,
            wordsPerSecond = wordsPerSecond,
            state = MessageState.entries.first { it.key == state },
            failure = failure,
        )

    private companion object {
        fun imageNames(joined: String): List<String> = joined.split('\n').filter { it.isNotEmpty() }
    }
}

private val MessageKind.key get() = name.lowercase()
private val MessageState.key get() = name.lowercase()

/**
 * The title of a chat: the first line of its first message, cut at 40 characters on a word boundary with an
 * ellipsis; a message of only images is "Image". Never generated by the model.
 */
private const val TITLE_MAX_CHARS = 40

internal fun titleFrom(text: String, hasImages: Boolean): String {
    val line =
        text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }
            ?: return if (hasImages) "Image" else NEW_CHAT_TITLE
    if (line.length <= TITLE_MAX_CHARS) return line
    val head = line.take(TITLE_MAX_CHARS)
    val cut = if (line[40].isWhitespace()) head else head.substringBeforeLast(' ', head)
    return cut.trimEnd() + "…"
}
