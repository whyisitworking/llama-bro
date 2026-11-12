package com.suhel.llamabro.conversation

import com.suhel.llamabro.FakeEngine
import com.suhel.llamabro.ModelSource
import com.suhel.llamabro.Replies
import com.suhel.llamabro.chat.Message
import com.suhel.llamabro.chatBodies
import com.suhel.llamabro.conversation
import com.suhel.llamabro.script
import com.suhel.llamabro.testLlamaBro
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** PD T1 (F96): a Conversation started from an app's own stored messages. */
class ConversationHistoryTest {
    private val history = listOf(Message.user("hi"), Message.Assistant("hello", null, emptyList()))

    private fun messagesOf(body: String): List<String> =
        ((Json.parseToJsonElement(body) as JsonObject)["messages"] as JsonArray).map { it.toString() }

    @Test
    fun historyIsSentBeforeTheNewMessage() =
        runTest {
            val engine = FakeEngine()
            val model = testLlamaBro(engine).loadModel(ModelSource.file("/m.gguf"))
            engine.model.script(Replies.text("ok"))
            model.conversation("S", history).send("next").collect()
            assertEquals(
                listOf(
                    """{"role":"system","content":"S"}""",
                    """{"role":"user","content":"hi"}""",
                    """{"role":"assistant","content":"hello"}""",
                    """{"role":"user","content":"next"}""",
                ),
                messagesOf(engine.model.chatBodies.first()),
            )
        }

    @Test
    fun historyIsTheInitialMessages() =
        runTest {
            val model = testLlamaBro().loadModel(ModelSource.file("/m.gguf"))
            assertEquals(history, model.conversation(history = history).messages.value)
            // a system prompt comes first, as in any conversation
            assertEquals(listOf(Message.system("S")) + history, model.conversation("S", history).messages.value)
        }

    @Test
    fun historyMayNotContainASystemMessage() =
        runTest {
            val model = testLlamaBro().loadModel(ModelSource.file("/m.gguf"))
            assertThrows(IllegalArgumentException::class.java) {
                model.conversation(history = listOf(Message.system("S")) + history)
            }
        }
}
