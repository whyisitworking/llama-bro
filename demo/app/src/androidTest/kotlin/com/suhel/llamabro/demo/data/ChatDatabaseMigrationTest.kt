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

    /** The Hub cache arrives in version 5 as empty tables; chats and messages are untouched. */
    @Test fun version4ChatsOpenInVersion5WithEmptyHubTables() {
        helper.createDatabase(name, 4).apply {
            execSQL("INSERT INTO chats (id, title, createdAt, updatedAt, modelKey) VALUES (1, 'Hi', 1, 2, 'r/m:Q4')")
            execSQL(
                "INSERT INTO messages (chatId, position, kind, text, images, sentAt, state) " +
                    "VALUES (1, 0, 'user', 'Hello!', '', 2, 'done')",
            )
            close()
        }
        helper.runMigrationsAndValidate(name, 5, true)

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.databaseBuilder(context, ChatDatabase::class.java, name).build()
        try {
            runBlocking {
                assertEquals(
                    "Hello!",
                    db
                        .chatDao()
                        .messages(1)
                        .first()
                        .single()
                        .text,
                )
                assertEquals(emptyList<Any>(), db.hubCacheDao().records().first())
                assertEquals(emptyList<Any>(), db.hubCacheDao().recentSearches(8).first())
                assertEquals(emptyList<Any>(), db.hubCacheDao().resultsOf("qwen"))
            }
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }

    /**
     * Version 6 adds a download record's model-file bytes: the phone's version-5 chats and a download under way keep
     * everything, and the record reads 0 for the bytes it never stored (the row then falls back to its total).
     */
    @Test fun version5ChatsAndRecordsOpenInVersion6() {
        helper.createDatabase(name, 5).apply {
            execSQL("INSERT INTO chats (id, title, createdAt, updatedAt, modelKey) VALUES (1, 'Hi', 1, 2, 'r/m:Q4')")
            execSQL(
                "INSERT INTO messages (chatId, position, kind, text, images, sentAt, state) " +
                    "VALUES (1, 0, 'user', 'Hello!', '', 2, 'done')",
            )
            execSQL(
                "INSERT INTO download_record (model, name, maker, totalBytes, startedAt) " +
                    "VALUES ('r/m:Q4_K_M', 'M', 'Google', 3400000000, 7)",
            )
            close()
        }
        helper.runMigrationsAndValidate(name, 6, true)

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.databaseBuilder(context, ChatDatabase::class.java, name).build()
        try {
            runBlocking {
                assertEquals(
                    "Hello!",
                    db
                        .chatDao()
                        .messages(1)
                        .first()
                        .single()
                        .text,
                )
                val record = db.hubCacheDao().record("r/m:Q4_K_M").first()!!
                assertEquals(3_400_000_000L, record.totalBytes)
                assertEquals("Google", record.maker)
                assertEquals(0L, record.modelBytes)
            }
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }

    /**
     * Version 7 replaces the one-page search answers with search pages and their cursors (D127): the phone's version-6
     * chats and records keep everything, the new tables are there and empty, and the old answers are gone.
     */
    @Test fun version6ChatsAndRecordsOpenInVersion7WithSearchPages() {
        helper.createDatabase(name, 6).apply {
            execSQL("INSERT INTO chats (id, title, createdAt, updatedAt, modelKey) VALUES (1, 'Hi', 1, 2, 'r/m:Q4')")
            execSQL(
                "INSERT INTO messages (chatId, position, kind, text, images, sentAt, state) " +
                    "VALUES (1, 0, 'user', 'Hello!', '', 2, 'done')",
            )
            execSQL(
                "INSERT INTO download_record (model, name, maker, totalBytes, modelBytes, startedAt) " +
                    "VALUES ('r/m:Q4_K_M', 'M', 'Google', 3400000000, 3000000000, 7)",
            )
            execSQL("INSERT INTO hub_search (`query`, rowsJson, fetchedAt) VALUES ('qwen', '[]', 3)")
            close()
        }
        helper.runMigrationsAndValidate(name, 7, true).apply {
            query("SELECT name FROM sqlite_master WHERE type = 'table' AND name = 'hub_search'").use {
                assertEquals("the one-page answers are dropped", 0, it.count)
            }
            for (table in listOf("hub_result", "hub_search_page")) {
                query("SELECT COUNT(*) FROM $table").use {
                    it.moveToFirst()
                    assertEquals("$table is there and empty", 0, it.getInt(0))
                }
            }
            close()
        }

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.databaseBuilder(context, ChatDatabase::class.java, name).build()
        try {
            runBlocking {
                assertEquals(
                    "Hello!",
                    db
                        .chatDao()
                        .messages(1)
                        .first()
                        .single()
                        .text,
                )
                val record = db.hubCacheDao().record("r/m:Q4_K_M").first()!!
                assertEquals(3_000_000_000L, record.modelBytes)
                assertEquals(emptyList<Any>(), db.hubCacheDao().resultsOf("qwen"))
                assertNull(db.hubCacheDao().searchPage("qwen"))
            }
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }

    /**
     * Version 8 adds a chat's "Use my library" and a reply's sources (docs/design/rag.md): the phone's version-7 chats
     * keep every message, start with the library off, and their replies have no sources.
     */
    @Test fun version7ChatsOpenInVersion8WithTheLibraryOffAndNoSources() {
        helper.createDatabase(name, 7).apply {
            execSQL("INSERT INTO chats (id, title, createdAt, updatedAt, modelKey) VALUES (1, 'Hi', 1, 2, 'r/m:Q4')")
            execSQL(
                "INSERT INTO messages (chatId, position, kind, text, images, sentAt, state) " +
                    "VALUES (1, 0, 'assistant', 'Hello!', '', 2, 'done')",
            )
            close()
        }
        helper.runMigrationsAndValidate(name, 8, true)

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.databaseBuilder(context, ChatDatabase::class.java, name).build()
        try {
            runBlocking {
                val dao = db.chatDao()
                val message = dao.messages(1).first().single()
                assertEquals("Hello!", message.text)
                assertNull("a reply from version 7 has no sources", message.sources)
                assertEquals(false, dao.useLibrary(1).first())
                assertEquals(false, dao.chat(1)!!.useLibrary)
            }
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }
}
