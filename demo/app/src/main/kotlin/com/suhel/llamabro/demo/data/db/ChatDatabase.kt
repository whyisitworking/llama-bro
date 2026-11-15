package com.suhel.llamabro.demo.data.db

import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * `chats.db`; each version's schema is exported to `demo/app/schemas/`. Version 2 adds a reply's measurements
 * ([ReplyStats]), version 3 a chat's own sampling values (D60): nullable columns, so Room migrates on its own.
 */
@Database(
    entities = [ChatEntity::class, MessageEntity::class],
    version = 3,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 1, to = 2), AutoMigration(from = 2, to = 3)],
)
abstract class ChatDatabase : RoomDatabase() {
    abstract fun chatDao(): ChatDao
}
