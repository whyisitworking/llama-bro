package com.suhel.llamabro

import com.suhel.llamabro.chat.ChatEvent
import com.suhel.llamabro.chat.Message
import com.suhel.llamabro.chat.ReasoningFormat
import com.suhel.llamabro.chat.ResponseFormat
import com.suhel.llamabro.chat.ToolChoice
import com.suhel.llamabro.conversation.canCompact
import com.suhel.llamabro.conversation.compact
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** PA T3 (F38): Conversation reaches every request option, per send too; reply, isBusy, canCompact, regenerate. */
class ConversationOptionsTest {
    private suspend fun setup(vararg replies: List<String>): Pair<FakeEngine, Model> {
        val engine = FakeEngine()
        val model = testLlamaBro(engine).loadModel(ModelSource.file("/m.gguf"))
        engine.model.script(*replies)
        return engine to model
    }

    private fun obj(body: String) = Json.parseToJsonElement(body) as JsonObject

    private fun messagesOf(body: String): List<String> = (obj(body)["messages"] as JsonArray).map { it.toString() }

    private val weather = """{"type":"object","properties":{"city":{"type":"string"}}}"""

    @Test
    fun defaultsReachEveryRequest() =
        runTest {
            val (engine, model) =
                setup(
                    Replies.toolCalls(Triple("c1", "get_weather", """{"city":"Paris"}""")),
                    Replies.text("Sunny."),
                )
            val chat =
                model.conversation(system = "sys") {
                    defaults {
                        responseFormat = ResponseFormat.JsonObject
                        stop("END")
                        toolChoice = ToolChoice.Auto
                        parallelToolCalls = false
                        sampling {
                            temperature = 0.3f
                            dryMultiplier = 0.5f
                        }
                        reasoning {
                            enableThinking = false
                            format = ReasoningFormat.Auto
                        }
                        extra = """{"n_probs":2}"""
                    }
                    tool("get_weather", "w", weather) { "sunny" }
                }
            chat.send("weather?").toList()
            val bodies = engine.model.chatBodies
            assertEquals(2, bodies.size) // the tool turn and the answer
            for (b in bodies.map { obj(it) }) {
                assertEquals("""{"type":"json_object"}""", b["response_format"].toString())
                assertEquals("""["END"]""", b["stop"].toString())
                assertEquals("\"auto\"", b["tool_choice"].toString())
                assertEquals("false", b["parallel_tool_calls"].toString())
                assertEquals("0.3", b["temperature"].toString())
                assertEquals("0.5", b["dry_multiplier"].toString())
                assertEquals("""{"enable_thinking":false}""", b["chat_template_kwargs"].toString())
                assertEquals("\"auto\"", b["reasoning_format"].toString())
                assertEquals("2", b["n_probs"].toString())
                assertTrue(b.containsKey("tools"))
            }
        }

    @Test
    fun perSendOverrideAppliesToThatSendOnly() =
        runTest {
            val (engine, model) =
                setup(
                    Replies.toolCalls(Triple("c1", "get_weather", """{"city":"Paris"}""")),
                    Replies.text("Sunny."),
                    Replies.text("Bye."),
                )
            val chat =
                model.conversation {
                    defaults {
                        sampling {
                            temperature = 0.7f
                            topK = 20
                        }
                    }
                    tool("get_weather", "w", weather) { "sunny" }
                }
            chat
                .send("weather?") {
                    sampling { temperature = 0f }
                    stop("Z")
                }.toList()
            chat.send("thanks").toList()
            val (t1, t2, t3) = engine.model.chatBodies.map { obj(it) }
            for (b in listOf(t1, t2)) { // both turns of the first send
                assertEquals("0.0", b["temperature"].toString())
                assertEquals("20", b["top_k"].toString()) // the override adds to the defaults
                assertEquals("""["Z"]""", b["stop"].toString())
            }
            assertEquals("0.7", t3["temperature"].toString())
            assertFalse(t3.containsKey("stop"))
            // history only: the next request's messages extend the last one's (prompt cache, F5)
            val m2 = messagesOf(engine.model.chatBodies[1])
            assertEquals(m2, messagesOf(engine.model.chatBodies[2]).take(m2.size))
        }

    @Test
    fun replyStreamsThenClears() =
        runTest {
            val (_, model) = setup(Replies.text("Hello there", reasoning = "hmm"))
            val chat = model.conversation()
            assertNull(chat.reply.value)
            val seen = mutableListOf<String?>()
            chat.send("hi").collect { ev ->
                if (ev is ChatEvent.ContentDelta) seen += chat.reply.value?.content
                if (ev is ChatEvent.ReasoningDelta) assertEquals("hmm", chat.reply.value?.reasoning)
            }
            assertEquals(listOf("Hell", "Hello th", "Hello there"), seen)
            assertNull(chat.reply.value)
            assertEquals(Message.Assistant("Hello there", reasoning = "hmm"), chat.messages.value.last())
        }

    @Test
    fun isBusyTracksOperations() =
        runTest {
            val (_, model) = setup(Replies.text("a"))
            val chat = model.conversation()
            assertFalse(chat.isBusy.value)
            var during = false
            chat.send("x").collect { during = during || chat.isBusy.value }
            assertTrue(during)
            assertFalse(chat.isBusy.value)
        }

    @Test
    fun canCompactMatchesToolsUi() =
        runTest {
            val (_, model) = setup(Replies.text("a1"), Replies.text("a2"), Replies.text("a3"))
            val chat = model.conversation(system = "sys")
            assertFalse(chat.canCompact(0))
            chat.send("u1").toList()
            chat.send("u2").toList()
            chat.send("u3").toList()
            // six steps (three user messages, three replies), as tools/ui counts them
            assertTrue(chat.canCompact(2))
            assertTrue(chat.canCompact(5))
            assertFalse(chat.canCompact(6))
        }

    @Test
    fun compactionIgnoresFormatAndStop() =
        runTest {
            val (engine, model) = setup(Replies.text("a1"), Replies.text("a2"), Replies.text("summary"))
            val chat =
                model.conversation {
                    defaults {
                        responseFormat = ResponseFormat.JsonObject
                        stop("END")
                        toolChoice = ToolChoice.Required
                        parallelToolCalls =
                            true
                        sampling { temperature = 0.2f }
                        reasoning { enableThinking = false }
                        extra = """{"n_probs":1}"""
                    }
                    tool("get_weather", "w", weather) { "sunny" }
                }
            chat.send("u1").toList()
            chat.send("u2").toList()
            chat.compact(preservedSteps = 2).toList()
            val summary =
                obj(
                    engine.model.chatBodies
                        .filterNot { it.contains("\"n_predict\":0") }
                        .last(),
                )
            for (k in listOf(
                "response_format",
                "stop",
                "tool_choice",
                "parallel_tool_calls",
            )) {
                assertFalse(k, summary.containsKey(k))
            }
            // what shapes the prompt stays, so the summary request shares the chat's cached prefix
            assertTrue(summary.containsKey("tools"))
            assertEquals("""{"enable_thinking":false}""", summary["chat_template_kwargs"].toString())
            assertEquals("0.2", summary["temperature"].toString())
            assertEquals("1", summary["n_probs"].toString())
        }

    /**
     * tools/ui chat.service.ts preEncode sends messages and tools only, whatever the chat's options (reference rule).
     */
    @Test
    fun preEncodeBodyFollowsToolsUi() =
        runTest {
            val engine = FakeEngine()
            val model = testLlamaBro(engine).loadModel(ModelSource.file("/m.gguf"))
            val queue = ArrayDeque(listOf(Replies.text("a1")))
            engine.model.onRequest = { r ->
                when {
                    r.endpoint == "chat" && r.body.contains("\"n_predict\":0") -> {
                        Unit
                    }

                    // left open
                    r.endpoint == "chat" -> {
                        queue.removeFirst().forEach { r.chunk(it) }
                        r.done()
                    }

                    r.endpoint == "slots" -> {
                        r.chunk("""[{"id":0,"is_processing":false}]""")
                        r.done()
                    }

                    else -> {
                        r.chunk("{}")
                        r.done()
                    }
                }
            }
            val chat =
                model.conversation {
                    preEncodeAfterReply = true
                    defaults {
                        reasoning { enableThinking = false }
                        sampling { temperature = 0.1f }
                        stop("X")
                    }
                    tool("get_weather", "w", weather) { "sunny" }
                }
            chat.send("u1").toList()
            val pre =
                withContext(Dispatchers.Default) {
                    withTimeout(5_000) {
                        while (engine.model.chatBodies.none { it.contains("\"n_predict\":0") }) delay(5)
                        engine.model.chatBodies.first { it.contains("\"n_predict\":0") }
                    }
                }
            assertEquals(listOf("messages", "n_predict", "stream", "tools"), obj(pre).keys.sorted())
        }

    @Test
    fun regenerateReplacesTheLastReply() =
        runTest {
            val (engine, model) =
                setup(
                    Replies.text("a1"),
                    Replies.toolCalls(Triple("c1", "get_weather", """{"city":"Paris"}""")),
                    Replies.text("Rainy."),
                    Replies.text("Sunny."),
                )
            val chat = model.conversation(system = "sys") { tool("get_weather", "w", weather) { "sunny" } }
            chat.send("hi").toList()
            chat.send("weather?").toList()
            assertEquals(7, chat.messages.value.size) // sys, hi, a1, weather?, tool call, tool result, Rainy.
            chat.regenerate { sampling { seed = 9 } }.toList()
            val history = chat.messages.value
            assertEquals(5, history.size)
            assertEquals(Message.Assistant("Sunny."), history.last())
            val bodies = engine.model.chatBodies
            // the regenerated request repeats the original's messages exactly, so its whole prompt is cached
            assertEquals(messagesOf(bodies[1]), messagesOf(bodies[3]))
            assertEquals("9", obj(bodies[3])["seed"].toString())
        }

    @Test
    fun regenerateNeedsAUserMessageAndNoLaterCompaction() =
        runTest {
            val (_, model) = setup(Replies.text("a1"), Replies.text("a2"), Replies.text("S"))
            val chat = model.conversation()
            assertThrows(
                IllegalStateException::class.java,
            ) { kotlinx.coroutines.runBlocking { chat.regenerate().toList() } }
            chat.send("u1").toList()
            chat.send("u2").toList()
            chat.compact(preservedSteps = 0).toList()
            val e =
                assertThrows(
                    IllegalStateException::class.java,
                ) { kotlinx.coroutines.runBlocking { chat.regenerate().toList() } }
            assertTrue(e.message, e.message!!.contains("compaction"))
        }

    @Test
    fun savedSettingsRestoreWithoutRepeatingThem() =
        runTest {
            val (engine, model) = setup(Replies.text("a1"), Replies.text("x1"), Replies.text("x2"))
            val original =
                model.conversation(system = "sys") {
                    maxTurns = 7
                    defaults {
                        responseFormat = ResponseFormat.JsonSchema("""{"type":"object"}""", name = "answer")
                        toolChoice = ToolChoice.Function("get_weather")
                        parallelToolCalls = false
                        stop("END", "STOP")
                        sampling {
                            temperature = 0.7f
                            topK = 40
                            seed = 1234567890123L
                            samplers = listOf("top_k", "temperature")
                            drySequenceBreakers = listOf("\n")
                            logitBias =
                                mapOf("15043" to 2f, "Hi" to Float.NEGATIVE_INFINITY)
                            mirostat = 2
                            ignoreEos = false
                        }
                        reasoning {
                            format = ReasoningFormat.DeepSeek
                            budgetTokens = 64
                            budgetMessage = "done"
                            enableThinking =
                                true
                        }
                        extra = """{"n_probs":3,"chat_template_kwargs":{"x":1}}"""
                    }
                    tool("get_weather", "w", weather) { "sunny" }
                }
            original.send("u1").toList()
            val saved = original.toJson()
            assertTrue(saved, saved.startsWith("""{"version":3"""))
            original.send("x").toList()
            // tool handlers can't be saved: the app registers them again, nothing else
            val restored = model.restoreConversation(saved) { tool("get_weather", "w", weather) { "sunny" } }
            restored.send("x").toList()
            val bodies = engine.model.chatBodies
            assertEquals(bodies[1], bodies[2])
        }

    @Test
    fun versionOneStillRestores() =
        runTest {
            val (engine, model) = setup(Replies.text("x"))
            val v1 =
                """{"version":1,"system":"sys","messages":[{"role":"user","content":[{"type":"text","text":"hi"}]},""" +
                    """{"role":"assistant","content":"hello","reasoning":null,"tool_calls":[]}]}"""
            model.restoreConversation(v1).send("again").toList()
            assertEquals(
                listOf(
                    """{"role":"system","content":"sys"}""",
                    """{"role":"user","content":"hi"}""",
                    """{"role":"assistant","content":"hello"}""",
                    """{"role":"user","content":"again"}""",
                ),
                messagesOf(engine.model.chatBodies.single()),
            )
        }
}
