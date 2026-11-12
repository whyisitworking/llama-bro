package com.suhel.llamabro.demo.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Reference B: one row of a chat. [kind] is `user`, `assistant` or `switch`; [state] is `done`, `held`, `streaming`,
 * `stopped` or `failed`; [images] holds newline-separated file names under `filesDir/images/`, empty for none.
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
)
