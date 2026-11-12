package com.suhel.llamabro.demo.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Reference B: one chat. [modelKey] is the model of the last turn (`HfModel.toString()`). */
@Entity(tableName = "chats", indices = [Index("updatedAt")])
data class ChatEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val modelKey: String,
)
