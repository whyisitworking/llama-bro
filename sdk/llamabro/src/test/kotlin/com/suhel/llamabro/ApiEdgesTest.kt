package com.suhel.llamabro

import com.suhel.llamabro.chat.FinishReason
import com.suhel.llamabro.chat.Message
import com.suhel.llamabro.conversation.addToolResult
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Edge cases an app developer will meet. */
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
                """{"version":3,"messages":[]}""", // v3 always has settings
                """{"version":4,"settings":{"options":{}},"messages":[]}""", // a newer version is unknown
            )) {
                val e = runCatching { model.restoreConversation(bad) }.exceptionOrNull()
                assertTrue("$bad -> $e", e is IllegalArgumentException)
            }
            val newer = runCatching { model.restoreConversation("""{"version":4,"messages":[]}""") }.exceptionOrNull()
            assertTrue("$newer", newer!!.message!!.contains("unsupported conversation version: 4"))
        }

    @Test
    fun askIsIncompleteWhenToolsArePending() =
        runTest {
            val (engine, model) = setup()
            engine.model.script(Replies.toolCalls(Triple("c1", "manual", "{}")))
            val result = model.conversation { tool("manual", "m", "{}") }.ask("x")
            assertTrue("$result", result is Typed.Incomplete && result.reason == FinishReason.ToolCallsPending)
        }

    @Test
    fun askIsIncompleteAtTurnLimit() =
        runTest {
            val (engine, model) = setup()
            engine.model.script(Replies.toolCalls(Triple("c1", "t", "{}")))
            val chat =
                model.conversation {
                    maxTurns = 1
                    tool("t", "t", "{}") { "r" }
                }
            val result = chat.ask("x")
            assertTrue("$result", result is Typed.Incomplete && result.reason == FinishReason.TurnLimit)
        }

    @Test
    fun askIsIncompleteOnLength() =
        runTest {
            val (engine, model) = setup()
            engine.model.script(Replies.text("half an ans", finish = "length"))
            assertEquals(
                Typed.Incomplete(FinishReason.Length, "half an ans"),
                model.conversation().ask("x"),
            )
        }

    @Test
    fun addToolResultRejectsSecondResultForSameCall() =
        runTest {
            val (engine, model) = setup()
            engine.model.script(Replies.toolCalls(Triple("c1", "manual", "{}")))
            val chat = model.conversation { tool("manual", "m", "{}") }
            chat.send("x").toList()
            chat.addToolResult("c1", "first")
            val e = runCatching { chat.addToolResult("c1", "second") }.exceptionOrNull()
            assertTrue("$e", e is IllegalArgumentException)
            assertEquals(1, chat.messages.value.count { it is Message.ToolResult })
        }
}
