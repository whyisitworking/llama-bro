package com.suhel.llamabro

import com.suhel.llamabro.chat.FinishReason
import com.suhel.llamabro.chat.Message
import com.suhel.llamabro.conversation.addToolResult
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Edge cases from the P1 final review that an app developer will meet. */
class ApiEdgesTest {
    private suspend fun setup(): Pair<FakeEngine, Model> {
        val engine = FakeEngine()
        return engine to testLlamaBro(engine).loadModel(ModelSource.file("/m.gguf"))
    }

    @Test
    fun corruptSavedConversationThrowsIllegalArgument() =
        runTest {
            val (_, model) = setup()
            for (bad in listOf(
                "not json",
                "[]",
                """{"version":1}""",
                """{"version":1,"messages":[{"role":"user"}]}""",
                """{"version":1,"messages":[{"role":"assistant","tool_calls":[{"id":"c"}]}]}""",
                """{"version":1,"messages":["x"]}""",
                """{"version":2,"messages":[]}""", // v2 always has settings
                """{"version":2,"settings":{"options":{}},"messages":[]}""",
                """{"version":3,"messages":[]}""",
            )) {
                val e = runCatching { model.restoreConversation(bad) }.exceptionOrNull()
                assertTrue("$bad -> $e", e is IllegalArgumentException)
            }
        }

    @Test
    fun askThrowsWhenToolsArePending() =
        runTest {
            val (engine, model) = setup()
            engine.model.script(Replies.toolCalls(Triple("c1", "nope", "{}")))
            val e = runCatching { model.conversation().ask("x") }.exceptionOrNull()
            assertTrue(
                "$e",
                e is LlamaBroException.Incomplete && e.reason == FinishReason.ToolCallsPending,
            )
        }

    @Test
    fun askThrowsAtTurnLimit() =
        runTest {
            val (engine, model) = setup()
            engine.model.script(Replies.toolCalls(Triple("c1", "t", "{}")))
            val chat =
                model.conversation {
                    maxTurns = 1
                    tool("t", "t", "{}") { "r" }
                }
            val e = runCatching { chat.ask("x") }.exceptionOrNull()
            assertTrue(
                "$e",
                e is LlamaBroException.Incomplete && e.reason == FinishReason.TurnLimit,
            )
        }

    @Test
    fun askThrowsIncompleteOnLength() =
        runTest {
            val (engine, model) = setup()
            engine.model.script(Replies.text("half an ans", finish = "length"))
            val e = runCatching { model.conversation().ask("x") }.exceptionOrNull()
            e as LlamaBroException.Incomplete
            assertEquals(FinishReason.Length, e.reason)
            assertEquals("half an ans", e.partial)
        }

    @Test
    fun addToolResultRejectsSecondResultForSameCall() =
        runTest {
            val (engine, model) = setup()
            engine.model.script(Replies.toolCalls(Triple("c1", "nope", "{}")))
            val chat = model.conversation()
            chat.send("x").toList()
            chat.addToolResult("c1", "first")
            val e = runCatching { chat.addToolResult("c1", "second") }.exceptionOrNull()
            assertTrue("$e", e is IllegalArgumentException)
            assertEquals(1, chat.messages.value.count { it is Message.ToolResult })
        }
}
