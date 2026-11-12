package com.suhel.llamabro.demo.data

import android.database.sqlite.SQLiteConstraintException
import com.suhel.llamabro.demo.data.db.ChatDao
import com.suhel.llamabro.demo.data.db.ChatEntity
import com.suhel.llamabro.demo.data.db.MessageEntity
import com.suhel.llamabro.demo.testing.TestAppRule
import com.suhel.llamabro.demo.testing.demoRules
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test

class ChatDaoTest {
    private val app = TestAppRule()

    @get:Rule val rules = demoRules(app)
    private val dao: ChatDao get() = app.container.database.chatDao()

    private suspend fun chat(title: String, updatedAt: Long) =
        dao.insertChat(ChatEntity(title = title, createdAt = updatedAt, updatedAt = updatedAt, modelKey = "r/m:Q4"))

    private fun message(chatId: Long, text: String = "hi") =
        MessageEntity(chatId = chatId, position = 0, kind = "user", text = text, sentAt = 1)

    @Test fun positionsAreUniquePerChat() =
        runBlocking<Unit> {
            val a = chat("a", 1)
            val b = chat("b", 2)
            dao.append(message(a), 1, null)
            dao.append(message(a), 1, null)
            dao.append(message(b), 1, null)
            assertEquals(listOf(0, 1), dao.messagesOf(a).map { it.position })
            assertEquals(listOf(0), dao.messagesOf(b).map { it.position })
            assertThrows(SQLiteConstraintException::class.java) {
                runBlocking { dao.insertMessage(message(a).copy(position = 1)) }
            }
        }

    @Test fun deletingAChatCascadesToItsMessages() =
        runBlocking<Unit> {
            val a = chat("a", 1)
            val b = chat("b", 2)
            val doomed = dao.append(message(a), 1, null)
            dao.append(message(b), 1, null)
            dao.deleteChat(a)
            assertNull(dao.message(doomed))
            assertEquals(1, dao.messagesOf(b).size)
            assertEquals(listOf("b"), dao.summaries("").first().map { it.title })
        }

    @Test fun chatsAreOrderedByUpdatedAtDescending() =
        runBlocking<Unit> {
            chat("old", 10)
            chat("newest", 30)
            chat("middle", 20)
            assertEquals(listOf("newest", "middle", "old"), dao.summaries("").first().map { it.title })
        }

    @Test fun summaryCarriesTheLastSpokenLineAndSkipsSwitchRows() =
        runBlocking<Unit> {
            val a = chat("a", 1)
            dao.append(message(a, "question"), 2, null)
            dao.append(message(a, "answer").copy(kind = "assistant"), 3, null)
            dao.append(message(a, "").copy(kind = "switch", modelKey = "r/other:Q4"), 4, null)
            val row = dao.summaries("").first().single()
            assertEquals("answer", row.lastText)
            assertEquals(false, row.lastFromUser)
            assertEquals("r/other:Q4", row.modelKey)
        }
}
