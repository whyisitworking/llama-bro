package com.suhel.llamabro

import com.suhel.llamabro.chat.ChatEvent
import com.suhel.llamabro.chat.FinishReason
import com.suhel.llamabro.chat.Message
import com.suhel.llamabro.conversation.JsonArgs
import com.suhel.llamabro.conversation.LbToolAbort
import com.suhel.llamabro.conversation.LbToolError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentLoopTest {
    private val schema = """{"type":"object","properties":{"city":{"type":"string"}},"required":["city"]}"""

    private suspend fun setup(): Pair<FakeEngine, Model> {
        val engine = FakeEngine()
        return engine to testLlamaBro(engine).loadModel(ModelSource.file("/m.gguf"))
    }

    private fun messagesOf(body: String): List<String> =
        ((Json.parseToJsonElement(body) as JsonObject)["messages"] as JsonArray).map { it.toString() }

    private fun call(id: String, name: String = "get_weather", args: String = """{"city":"Paris"}""") =
        Triple(id, name, args)

    @Test
    fun singleToolCallRoundTrip() =
        runTest {
            val (engine, model) = setup()
            engine.model.script(Replies.toolCalls(call("c1")), Replies.text("It is sunny."))
            val chat =
                model.conversation {
                    tool("get_weather", "Weather for a city", schema) { args ->
                        if (args.string("city") == "Paris") {
                            "sunny"
                        } else {
                            "?"
                        }
                    }
                }
            val events = chat.send("Weather in Paris?").toList()
            val bodies = engine.model.chatBodies
            assertEquals(2, bodies.size)
            val m2 = messagesOf(bodies[1])
            assertEquals(
                """{"role":"assistant","tool_calls":[{"id":"c1","type":"function","function":{"name":"get_weather","arguments":"{\"city\":\"Paris\"}"}}]}""",
                m2[m2.size - 2],
            )
            assertEquals("""{"role":"tool","tool_call_id":"c1","content":"sunny"}""", m2.last())
            assertTrue(bodies[0].contains("\"tools\":[{\"type\":\"function\",\"function\":{\"name\":\"get_weather\""))
            val kinds = events.map { it::class.simpleName }
            assertEquals(listOf("ToolCallReceived", "ToolResult"), kinds.take(2))
            assertTrue(kinds.drop(2).dropLast(1).all { it == "ContentDelta" })
            assertEquals(ChatEvent.ToolResult("c1", "get_weather", "sunny"), events[1])
            assertEquals(FinishReason.Stop, (events.last() as ChatEvent.Done).reason)
            assertEquals(1, events.count { it is ChatEvent.Done })
        }

    @Test
    fun toolErrorMessageReachesTheModel() =
        runTest {
            val (engine, model) = setup()
            engine.model.script(Replies.toolCalls(call("c1")), Replies.text("sorry"))
            val chat = model.conversation { tool("get_weather", "w", schema) { throw LbToolError("city not found") } }
            val events = chat.send("x").toList()
            assertEquals(
                """{"role":"tool","tool_call_id":"c1","content":"Error: city not found"}""",
                messagesOf(engine.model.chatBodies[1]).last(),
            )
            val result = events.filterIsInstance<ChatEvent.ToolResult>().single()
            assertEquals("Error: city not found", result.result)
            assertNull(result.error)
        }

    @Test
    fun toolErrorCauseReachesTheAppButNotTheModel() =
        runTest {
            val (engine, model) = setup()
            engine.model.script(Replies.toolCalls(call("c1")), Replies.text("sorry"))
            val cause = IllegalStateException("token=sk-FAKE-123")
            val chat =
                model.conversation { tool("get_weather", "w", schema) { throw LbToolError("city not found", cause) } }
            val events = chat.send("x").toList()
            assertEquals(
                """{"role":"tool","tool_call_id":"c1","content":"Error: city not found"}""",
                messagesOf(engine.model.chatBodies[1]).last(),
            )
            val result = events.filterIsInstance<ChatEvent.ToolResult>().single()
            assertEquals("Error: city not found", result.result)
            assertSame(cause, result.error)
        }

    @Test
    fun aToolsOwnTimeoutIsAToolFailureAndTheTurnGoesOn() =
        runTest {
            // A3: withTimeout inside the handler throws a CancellationException while the turn is still active
            val (engine, model) = setup()
            engine.model.script(Replies.toolCalls(call("c1")), Replies.text("sorry"))
            val chat =
                model.conversation {
                    tool("get_weather", "w", schema) { withTimeout(10) { awaitCancellation() } }
                }
            val events = chat.send("x").toList()
            assertEquals(
                """{"role":"tool","tool_call_id":"c1","content":"Error: the tool failed"}""",
                messagesOf(engine.model.chatBodies[1]).last(),
            )
            val result = events.filterIsInstance<ChatEvent.ToolResult>().single()
            assertTrue(result.error is TimeoutCancellationException)
            assertEquals(FinishReason.Stop, (events.last() as ChatEvent.Done).reason)
        }

    @Test
    fun anErrorFromAToolIsNotAToolFailure() =
        runTest {
            // A3: a JVM Error (out of memory, a stack overflow) is not the tool's answer: it ends the turn
            val (engine, model) = setup()
            engine.model.script(Replies.toolCalls(call("c1")), Replies.text("sorry"))
            val chat = model.conversation { tool("get_weather", "w", schema) { throw StackOverflowError("deep") } }
            val thrown =
                try {
                    chat.send("x").collect()
                    null
                } catch (e: StackOverflowError) {
                    e
                }
            // a copy (coroutines' stack-trace recovery), so the message identifies it
            assertEquals("deep", thrown?.message)
            assertEquals(1, engine.model.chatBodies.size)
        }

    @Test
    fun otherExceptionSendsGenericErrorAndReachesTheApp() =
        runTest {
            val (engine, model) = setup()
            engine.model.script(Replies.toolCalls(call("c1")), Replies.text("sorry"))
            val secret = IllegalStateException("token=sk-FAKE-123 for alice@example.com")
            val chat = model.conversation { tool("get_weather", "w", schema) { throw secret } }
            val events = chat.send("x").toList()
            assertEquals(
                """{"role":"tool","tool_call_id":"c1","content":"Error: the tool failed"}""",
                messagesOf(engine.model.chatBodies[1]).last(),
            )
            assertTrue(engine.model.chatBodies.none { "sk-FAKE-123" in it })
            assertFalse("sk-FAKE-123" in chat.toJson())
            val result = events.filterIsInstance<ChatEvent.ToolResult>().single()
            assertEquals("Error: the tool failed", result.result)
            assertSame(secret, result.error)
            assertEquals(FinishReason.Stop, (events.last() as ChatEvent.Done).reason)
        }

    @Test
    fun abortEndsTheTurn() =
        runTest {
            val (engine, model) = setup()
            engine.model.script(Replies.toolCalls(call("a", "tool_a", "{}"), call("b", "tool_b", "{}")))
            val abort = LbToolAbort("no network")
            var secondCalled = false
            val chat =
                model.conversation {
                    tool("tool_a", "a", "{}") { throw abort }
                    tool("tool_b", "b", "{}") {
                        secondCalled = true
                        "B"
                    }
                }
            val events = chat.send("go").toList()
            val done = events.last() as ChatEvent.Done
            assertEquals(FinishReason.ToolAborted, done.reason)
            assertSame(abort, done.abort)
            assertFalse(secondCalled)
            assertEquals(1, engine.model.chatBodies.size)
            assertTrue(events.none { it is ChatEvent.ToolResult })
            assertTrue(chat.messages.value.none { it is Message.ToolResult })
        }

    @Test
    fun askIsIncompleteOnAbort() =
        runTest {
            val (engine, model) = setup()
            engine.model.script(Replies.toolCalls(call("c1")))
            val chat = model.conversation { tool("get_weather", "w", schema) { throw LbToolAbort("no network") } }
            val result = chat.ask("x")
            assertTrue("$result", result is Typed.Incomplete)
            assertEquals(FinishReason.ToolAborted, (result as Typed.Incomplete).reason)
        }

    @Test
    fun callsRunSequentiallyInOrder() =
        runTest {
            val (engine, model) = setup()
            engine.model.script(
                Replies.toolCalls(call("a", "tool_a", "{}"), call("b", "tool_b", "{}")),
                Replies.text("done"),
            )
            val log = mutableListOf<String>()
            val chat =
                model.conversation {
                    tool("tool_a", "a", "{}") {
                        log += "a start"
                        kotlinx.coroutines.delay(50)
                        log += "a end"
                        "A"
                    }
                    tool("tool_b", "b", "{}") {
                        log += "b start"
                        "B"
                    }
                }
            val events = chat.send("go").toList()
            assertEquals(listOf("a start", "a end", "b start"), log)
            val m2 = messagesOf(engine.model.chatBodies[1])
            assertEquals(
                listOf(
                    """{"role":"tool","tool_call_id":"a","content":"A"}""",
                    """{"role":"tool","tool_call_id":"b","content":"B"}""",
                ),
                m2.takeLast(2),
            )
            assertEquals(
                listOf("ToolCallReceived", "ToolResult", "ToolCallReceived", "ToolResult"),
                events.take(4).map {
                    it::class.simpleName
                },
            )
        }

    @Test
    fun turnLimitStopsAndResumeContinues() =
        runTest {
            val (engine, model) = setup()
            engine.model.script(Replies.toolCalls(call("c1")), Replies.toolCalls(call("c2")), Replies.text("finally"))
            val chat =
                model.conversation {
                    maxTurns = 2
                    tool("get_weather", "w", schema) { "sunny" }
                }
            val events = chat.send("loop").toList()
            assertEquals(2, engine.model.chatBodies.size)
            assertEquals(FinishReason.TurnLimit, (events.last() as ChatEvent.Done).reason)
            val resumed = chat.resume().toList()
            assertEquals(3, engine.model.chatBodies.size)
            assertEquals(FinishReason.Stop, (resumed.last() as ChatEvent.Done).reason)
        }

    @Test
    fun unknownToolAnswersTheModel() =
        runTest {
            val (engine, model) = setup()
            engine.model.script(Replies.toolCalls(call("c9", "nope", "{}")), Replies.text("thanks"))
            val chat = model.conversation { tool("get_weather", "w", schema) { "sunny" } }
            val events = chat.send("x").toList()
            assertEquals(2, engine.model.chatBodies.size)
            assertEquals(
                """{"role":"tool","tool_call_id":"c9","content":"Error: Unknown tool: nope"}""",
                messagesOf(engine.model.chatBodies[1]).last(),
            )
            assertEquals(ChatEvent.ToolResult("c9", "nope", "Error: Unknown tool: nope"), events[1])
            assertEquals(FinishReason.Stop, (events.last() as ChatEvent.Done).reason)
        }

    @Test
    fun toolWithoutHandlerStopsWithPending() =
        runTest {
            val (engine, model) = setup()
            engine.model.script(Replies.toolCalls(call("c1")))
            val chat = model.conversation { tool("get_weather", "w", schema) }
            val events = chat.send("x").toList()
            assertEquals(FinishReason.ToolCallsPending, (events.last() as ChatEvent.Done).reason)
            assertTrue(
                engine.model.chatBodies
                    .single()
                    .contains("get_weather"),
            )
        }

    @Test
    fun malformedArgumentsBecomeErrorResult() =
        runTest {
            val (engine, model) = setup()
            engine.model.script(Replies.toolCalls(call("c1", args = """{"city":""")), Replies.text("ok"))
            var called = false
            val chat =
                model.conversation {
                    tool("get_weather", "w", schema) {
                        called = true
                        "sunny"
                    }
                }
            val events = chat.send("x").toList()
            assertFalse(called)
            val toolMsg = messagesOf(engine.model.chatBodies[1]).last()
            assertTrue(
                toolMsg,
                toolMsg.startsWith("""{"role":"tool","tool_call_id":"c1","content":"Error: invalid arguments: """),
            )
            assertFalse(toolMsg, toolMsg.contains("JSON input"))
            assertEquals(2, engine.model.chatBodies.size)
            assertEquals(FinishReason.Stop, (events.last() as ChatEvent.Done).reason)
        }

    @Test
    fun missingArgumentBecomesInvalidArguments() =
        runTest {
            val (engine, model) = setup()
            engine.model.script(Replies.toolCalls(call("c1", args = "{}")), Replies.text("ok"))
            val chat = model.conversation { tool("get_weather", "w", schema) { args -> args.string("city") } }
            chat.send("x").toList()
            assertEquals(
                """{"role":"tool","tool_call_id":"c1","content":"Error: invalid arguments: missing string argument: city"}""",
                messagesOf(engine.model.chatBodies[1]).last(),
            )
        }

    @Test
    fun cancelDuringHandlerCancelsIt() =
        runTest {
            val (engine, model) = setup()
            engine.model.script(Replies.toolCalls(call("c1")))
            val entered = CompletableDeferred<Unit>()
            val cancelled = CompletableDeferred<Throwable>()
            val chat =
                model.conversation {
                    tool("get_weather", "w", schema) {
                        entered.complete(Unit)
                        try {
                            awaitCancellation()
                        } catch (e: CancellationException) {
                            cancelled.complete(e)
                            throw e
                        }
                    }
                }
            val job = launch { chat.send("x").collect() }
            entered.await()
            job.cancel()
            job.join()
            assertTrue(cancelled.await() is CancellationException)
            assertEquals(1, engine.model.chatBodies.size)
        }

    @Test
    fun closeDuringToolHandler() =
        runTest {
            val (engine, model) = setup()
            engine.model.script(Replies.toolCalls(call("c1")))
            val entered = CompletableDeferred<Unit>()
            val cancelled = CompletableDeferred<Unit>()
            val chat =
                model.conversation {
                    tool("get_weather", "w", schema) {
                        entered.complete(Unit)
                        try {
                            awaitCancellation()
                        } finally {
                            cancelled.complete(Unit)
                        }
                    }
                }
            val result = async { runCatching { chat.send("x").collect() }.exceptionOrNull() }
            entered.await()
            model.close()
            val e = withTimeout(5_000) { result.await() }
            assertTrue("$e", e is LlamaBroException.ModelClosed)
            assertTrue(cancelled.isCompleted)
            assertEquals(1, engine.model.chatBodies.size)
        }

    @Test
    fun jsonArgsAccessors() {
        val args =
            JsonArgs.parse("""{"s":"x","i":3,"d":1.5,"b":true}""")
        assertEquals("x", args.string("s"))
        assertEquals(null, args.stringOrNull("missing"))
        assertEquals(3, args.int("i"))
        assertEquals(1.5, args.double("d"), 0.0)
        assertTrue(args.boolean("b"))
        val e = runCatching { args.string("i") }.exceptionOrNull()
        assertTrue("$e", e is LbToolError)
        assertEquals("invalid arguments: missing string argument: i", e?.message)
        assertTrue(runCatching { JsonArgs.parse("[1]") }.exceptionOrNull() is LbToolError)
        assertTrue(runCatching { JsonArgs.parse("{") }.exceptionOrNull() is LbToolError)
    }
}
