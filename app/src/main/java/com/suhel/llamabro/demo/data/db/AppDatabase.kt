package com.suhel.llamabro.demo.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import com.suhel.llamabro.demo.data.db.dao.ConversationDao
import com.suhel.llamabro.demo.data.db.dao.MessageDao
import com.suhel.llamabro.demo.data.db.entity.ConversationEntity
import com.suhel.llamabro.demo.data.db.entity.MessageEntity

@Database(
    entities = [ConversationEntity::class, MessageEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun conversationDao(): ConversationDao
    abstract fun messageDao(): MessageDao
}
