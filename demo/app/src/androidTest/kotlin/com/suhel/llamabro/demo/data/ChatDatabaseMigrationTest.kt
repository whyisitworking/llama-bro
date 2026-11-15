package com.suhel.llamabro.demo.data

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import com.suhel.llamabro.demo.data.db.ChatDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

/** A phone that kept chats in version 1 opens them in version 2 with every message as it was (the reply stats). */
class ChatDatabaseMigrationTest {
    private val name = "migration-test.db"

    @get:Rule val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), ChatDatabase::class.java)

    @Test fun version1ChatsOpenInVersion2() {
        helper.createDatabase(name, 1).apply {
            execSQL("INSERT INTO chats (id, title, createdAt, updatedAt, modelKey) VALUES (1, 'Hi', 1, 2, 'r/m:Q4')")
            execSQL(
                "INSERT INTO messages (chatId, position, kind, text, images, sentAt, wordsPerSecond, state) " +
                    "VALUES (1, 0, 'assistant', 'Hello!', '', 2, 12.5, 'done')",
            )
            close()
        }
        helper.runMigrationsAndValidate(name, 2, true)

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.databaseBuilder(context, ChatDatabase::class.java, name).build()
        try {
            val message =
                runBlocking {
                    db
                        .chatDao()
                        .messages(1)
                        .first()
                        .single()
                }
            assertEquals("Hello!", message.text)
            assertEquals(12.5, message.wordsPerSecond!!, 0.0)
            assertNull("a reply from version 1 has no stats", message.stats)
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }
}
