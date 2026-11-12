package com.suhel.llamabro.demo.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

/** `chats.db`, version 1; the schema is exported to `demo/app/schemas/`. */
@Database(entities = [ChatEntity::class, MessageEntity::class], version = 1, exportSchema = true)
abstract class ChatDatabase : RoomDatabase() {
    abstract fun chatDao(): ChatDao
}
