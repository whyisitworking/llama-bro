package com.suhel.llamabro.demo.data

import com.suhel.llamabro.demo.chat.Knobs
import com.suhel.llamabro.demo.data.db.ChatDao
import com.suhel.llamabro.demo.data.db.ChatEntity
import com.suhel.llamabro.demo.data.db.MessageEntity
import com.suhel.llamabro.demo.data.db.NEW_CHAT_TITLE
import com.suhel.llamabro.demo.data.db.ReplyStats
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.io.File

enum class MessageKind { User, Assistant, Switch }

/** [Cut]: the reply reached its length limit (D60); it keeps what it wrote and can be continued. */
enum class MessageState { Done, Held, Streaming, Stopped, Cut, Failed }

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
    /** A finished reply's measurements; null for other messages and for replies stored before they were kept. */
    val stats: ReplyStats? = null,
    /** A voice message's recording (D76); null for any other message. */
    val voice: StoredVoice? = null,
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
    /** A voice message's recording, in the [AudioStore] (D76). */
    val voice: NewVoice? = null,
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
    val stats: ReplyStats? = null,
)

/** Room is the source of truth for chats (demo design §3). Every call runs on Room's own threads. */
class ChatRepository(
    private val dao: ChatDao,
    private val imageStore: ImageStore,
    private val clock: () -> Long,
    private val audioStore: AudioStore? = null,
) {
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
                    lastStopped = it.lastState == "stopped" || it.lastState == "cut",
                    isEmpty = it.lastState.isEmpty(),
                )
            }
        }
    }

    fun messages(chatId: Long): Flow<List<StoredMessage>> =
        dao.messages(chatId).map { rows -> rows.map { it.toStored() } }

    /** When each model (by key) last answered, for the library; re-read as replies are written. */
    fun lastUsed(): Flow<Map<String, Long>> =
        dao.lastUseByModel().map { rows -> rows.associate { it.modelKey to it.lastUsed } }

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
                audio = message.voice?.name,
                audioMillis = message.voice?.millis,
                audioLevels = message.voice?.levels?.let(Levels::encode),
            )
        val title =
            if (message.kind == MessageKind.User) {
                titleFrom(message.text, message.images.isNotEmpty(), message.voice != null)
            } else {
                null
            }
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
                stats = change.stats ?: old.stats,
                state = change.state?.key ?: old.state,
                failure =
                    change.failure
                        ?: if (change.state != null && change.state != MessageState.Failed) null else old.failure,
            )
        }
    }

    /**
     * Removes the message at [position] and everything after it (edit, regenerate), and the voice messages among them:
     * a voice message is never edited or sent again, so its recording goes with its row.
     */
    suspend fun truncateFrom(chatId: Long, position: Int) {
        val recordings = dao.audioFrom(chatId, position)
        dao.deleteFrom(chatId, position)
        audioStore?.delete(recordings)
    }

    /** The model of the chat's last turn, or null when the chat is gone. */
    suspend fun modelKey(chatId: Long): String? = dao.chat(chatId)?.modelKey

    /** Records the model of the chat's last turn (switching models). */
    suspend fun setModel(chatId: Long, modelKey: String) = dao.modifyChat(chatId) { it.copy(modelKey = modelKey) }

    /** The chat's own sampling values (D60); none for a chat not created yet or one without changes. */
    fun sampling(chatId: Long): Flow<Knobs> = dao.sampling(chatId).map { Knobs.decode(it) }

    /** Changes the chat's own sampling values in one step; [Knobs.None] gives the chat back to its model's. */
    suspend fun updateSampling(chatId: Long, change: (Knobs) -> Knobs) =
        dao.modifyChat(chatId) { it.copy(sampling = change(Knobs.decode(it.sampling)).encode().ifEmpty { null }) }

    /** Titles are never empty: a blank [title] is rejected. */
    suspend fun rename(chatId: Long, title: String) {
        require(title.isNotBlank()) { "a chat title cannot be blank" }
        dao.modifyChat(chatId) { it.copy(title = title.trim()) }
    }

    /** Deletes the chat, its messages (cascade) and the images and recordings they hold. */
    suspend fun delete(chatId: Long) {
        val names = dao.imagesOf(chatId).flatMap(::imageNames)
        val recordings = dao.audioOf(chatId)
        dao.deleteChat(chatId)
        imageStore.delete(names)
        audioStore?.delete(recordings)
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
            stats = stats,
            voice =
                audio?.let { name ->
                    StoredVoice(audioStore?.file(name) ?: File(name), audioMillis ?: 0, Levels.decode(audioLevels))
                },
        )

    private companion object {
        fun imageNames(joined: String): List<String> = joined.split('\n').filter { it.isNotEmpty() }
    }
}

private val MessageKind.key get() = name.lowercase()
private val MessageState.key get() = name.lowercase()

/**
 * The title of a chat: the first line of its first message, cut at 40 characters on a word boundary with an
 * ellipsis; a voice message is "Voice message" and a message of only images "Image". Never generated by the model.
 */
private const val TITLE_MAX_CHARS = 40

internal fun titleFrom(text: String, hasImages: Boolean, isVoice: Boolean = false): String {
    val line =
        text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }
            ?: return when {
                isVoice -> "Voice message"
                hasImages -> "Image"
                else -> NEW_CHAT_TITLE
            }
    if (line.length <= TITLE_MAX_CHARS) return line
    val head = line.take(TITLE_MAX_CHARS)
    val cut = if (line[40].isWhitespace()) head else head.substringBeforeLast(' ', head)
    return cut.trimEnd() + "…"
}
