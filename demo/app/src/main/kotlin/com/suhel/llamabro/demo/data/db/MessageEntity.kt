package com.suhel.llamabro.demo.data.db

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One row of a chat. [kind] is `user`, `assistant` or `switch`; [state] is `done`, `held`, `streaming`,
 * `stopped` or `failed`; [images] holds newline-separated file names under `filesDir/images/`, empty for none.
 * [audio] is a voice message's file name under `filesDir/audio/` (D76), with its length and its levels
 * ([com.suhel.llamabro.demo.data.Levels]); null for a message that isn't one.
 */
@Entity(
    tableName = "messages",
    foreignKeys = [
        ForeignKey(
            ChatEntity::class,
            parentColumns = ["id"],
            childColumns = ["chatId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("chatId", "position", unique = true)],
)
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val chatId: Long,
    val position: Int,
    val kind: String,
    val text: String,
    val reasoning: String? = null,
    val reasoningMillis: Long? = null,
    val images: String = "",
    val modelKey: String? = null,
    val sentAt: Long,
    val wordsPerSecond: Double? = null,
    val state: String = "done",
    val failure: String? = null,
    /** Added in version 2; null for replies stored before, and for messages that aren't replies. */
    @Embedded(prefix = "stats_") val stats: ReplyStats? = null,
    /** Added in version 4 (D76). */
    val audio: String? = null,
    val audioMillis: Long? = null,
    val audioLevels: String? = null,
    /** Added in version 8: the library's passages a reply used, as `SourcesColumn` writes them; null for none. */
    val sources: String? = null,
    /**
     * Added in version 8: the library's passages a question was asked with, sent to the model before its words and
     * kept for its history, never shown; null for none.
     */
    val passages: String? = null,
)
