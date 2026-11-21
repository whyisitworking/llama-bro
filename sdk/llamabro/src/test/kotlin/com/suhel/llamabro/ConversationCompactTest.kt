package com.suhel.llamabro

import com.suhel.llamabro.chat.ChatEvent
import com.suhel.llamabro.chat.Message
import com.suhel.llamabro.conversation.Compaction
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
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationCompactTest {
    private fun obj(body: String) = Json.parseToJsonElement(body) as JsonObject

    private fun messagesOf(body: String): List<String> = (obj(body)["messages"] as JsonArray).map { it.toString() }

    private fun isPreEncode(body: String) = body.contains("\"n_predict\":0")

    /** Scripted chat replies; pre-encode requests stay open until [releasePreEncode]. */
    private class Harness(val engine: FakeEngine, val model: Model, replies: List<List<String>>) {
        private val queue = ArrayDeque(replies)
        val preEncodes = mutableListOf<FakeRequest>()

        init {
            engine.model.onRequest = { r ->
                when {
                    r.endpoint == "chat" && r.body.contains("\"n_predict\":0") -> {
                        synchronized(preEncodes) {
                            preEncodes +=
                                r
                        }
                    }

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
        }

        val chatBodies get() = engine.model.chatBodies.filterNot { it.contains("\"n_predict\":0") }

        suspend fun awaitPreEncode(n: Int = 1): FakeRequest =
            withContext(Dispatchers.Default) {
                withTimeout(5_000) {
                    while (synchronized(preEncodes) { preEncodes.size } < n) delay(5)
                    synchronized(preEncodes) { preEncodes[n - 1] }
                }
            }
    }

    private suspend fun harness(vararg replies: List<String>): Harness {
        val engine = FakeEngine()
        val model = testLlamaBro(engine).loadModel(ModelSource.file("/m.gguf"))
        return Harness(engine, model, replies.toList())
    }

    private suspend fun threeTurns(h: Harness) =
        h.model
            .conversation(system = "sys") {
                defaults { sampling { temperature = 0.2f } }
                tool("get_weather", "w", """{"type":"object"}""")
            }.also { c ->
                c.send("u1").toList()
                c.send("u2").toList()
                c.send("u3").toList()
            }

    /** Chat requests answer from [replies] in order; a reply of null is a context overflow. */
    private fun FakeEngine.answers(vararg replies: List<String>?) {
        val queue = ArrayDeque(replies.toList())
        model.onRequest = { r ->
            when {
                r.endpoint == "chat" && isPreEncode(r.body) -> {
                    r.chunk("{}")
                }

                r.endpoint == "chat" -> {
                    val next = queue.removeFirst()
                    if (next ==
                        null
                    ) {
                        r.chunk(Replies.exceed(9000, 8192), status = 400)
                    } else {
                        next.forEach { r.chunk(it) }
                    }
                }

                else -> {
                    r.chunk("{}")
                }
            }
            r.done()
        }
    }

    @Test
    fun autoCompactSummarisesAndAnswersInTheSameSend() =
        runTest {
            val engine = FakeEngine()
            val model = testLlamaBro(engine).loadModel(ModelSource.file("/m.gguf"))
            val turns = (1..6).map { Replies.text("a$it") }.toTypedArray()
            engine.answers(*turns, null, Replies.text("the summary"), Replies.text("the answer"))
            val chat = model.conversation { autoCompact = true }
            (1..6).forEach { chat.send("u$it").toList() }
            val events = chat.send("u7").toList()
            // the summary is history, not the reply: only the answer streams
            assertEquals(
                "the answer",
                events.filterIsInstance<ChatEvent.ContentDelta>().joinToString("") { it.delta },
            )
            assertEquals(1, events.filterIsInstance<ChatEvent.Done>().size)
            assertTrue(chat.messages.value.any { it == Message.Compaction("the summary", 10) })
            assertEquals(Message.Assistant("the answer"), chat.messages.value.last())
        }

    @Test
    fun autoCompactWithNothingToCompactStillFails() =
        runTest {
            val engine = FakeEngine()
            val model = testLlamaBro(engine).loadModel(ModelSource.file("/m.gguf"))
            engine.answers(null)
            val chat = model.conversation { autoCompact = true }
            val e = runCatching { chat.send("a very long document").toList() }.exceptionOrNull()
            assertTrue("$e", e is LlamaBroException.ContextExceeded)
            assertEquals(Message.user("a very long document"), chat.messages.value.last())
        }

    @Test
    fun summaryRequestSharesChatPrefix() =
        runTest {
            val h = harness(Replies.text("a1"), Replies.text("a2"), Replies.text("a3"), Replies.text("the summary"))
            val chat = threeTurns(h)
            chat.compact(preservedSteps = 2).toList()
            val chatBody = h.chatBodies[2]
            val summaryBody = h.chatBodies[3]
            // view = sys u1 a1 u2 a2 u3 a3; the last 2 steps (u3, a3) are preserved, so the cut is at u3
            assertEquals(
                messagesOf(chatBody).take(5) +
                    """{"role":"user","content":${Json.encodeToString(
                        JsonPrimitive(Compaction.DEFAULT_PROMPT),
                    )}}""",
                messagesOf(summaryBody),
            )
            assertEquals(obj(chatBody) - "messages", obj(summaryBody) - "messages")
            assertEquals(Message.Compaction("the summary", 2), chat.messages.value.last())
        }

    @Test
    fun truncatedSummaryRollsBack() =
        runTest {
            val h =
                harness(
                    Replies.text("a1"),
                    Replies.text("a2"),
                    Replies.text("a3"),
                    Replies.text("cut off", finish = "length"),
                )
            val chat = threeTurns(h)
            val before = chat.messages.value
            val e = runCatching { chat.compact(preservedSteps = 2).toList() }.exceptionOrNull()
            assertTrue("$e", e is LlamaBroException.CompactionTruncated)
            assertEquals(before, chat.messages.value)
        }

    @Test
    fun nextSendUsesModelView() =
        runTest {
            val h =
                harness(
                    Replies.text("a1"),
                    Replies.text("a2"),
                    Replies.text("a3"),
                    Replies.text("S"),
                    Replies.text("a4"),
                )
            val chat = threeTurns(h)
            chat.compact(preservedSteps = 2).toList()
            chat.send("u4").toList()
            val framed = Compaction.CONTEXT_TEMPLATE.replace(Compaction.SUMMARY_PLACEHOLDER, "S")
            assertEquals(
                listOf(
                    """{"role":"system","content":"sys"}""",
                    """{"role":"user","content":${Json.encodeToString(
                        JsonPrimitive(framed),
                    )}}""",
                    """{"role":"user","content":"u3"}""",
                    """{"role":"assistant","content":"a3"}""",
                    """{"role":"user","content":"u4"}""",
                ),
                messagesOf(h.chatBodies.last()),
            )
        }

    @Test
    fun preEncodeSentAndCancelledBySend() =
        runTest {
            val h =
                harness(
                    Replies.text("a1"),
                    Replies.text("a2"),
                    Replies.text("a3"),
                    Replies.text("S"),
                    Replies.text("a4"),
                )
            val chat = threeTurns(h)
            chat.compact(preservedSteps = 2).toList()
            val pre = h.awaitPreEncode()
            val body = obj(pre.body)
            assertEquals("0", body["n_predict"].toString())
            assertEquals("false", body["stream"].toString())
            assertTrue(body.containsKey("tools"))
            chat.send("u4").toList()
            val nextMessages = messagesOf(h.chatBodies.last())
            assertEquals(messagesOf(pre.body), nextMessages.dropLast(1)) // the pre-encode warmed the very prefix
            assertEquals(1, pre.cancelCount)
        }

    @Test
    fun preEncodeAfterReplyWhenEnabled() =
        runTest {
            val h = harness(Replies.text("a1"))
            val chat = h.model.conversation { preEncodeAfterReply = true }
            chat.send("u1").toList()
            val pre = h.awaitPreEncode()
            assertEquals(
                listOf("""{"role":"user","content":"u1"}""", """{"role":"assistant","content":"a1"}"""),
                messagesOf(pre.body),
            )
        }

    /**
     * tools/ui keeps one pre-encode controller for the whole store, so a send in any chat cancels it. On a one-slot
     * phone a pre-encode left running from chat A would delay chat B's reply by a whole prefill.
     */
    @Test
    fun preEncodeOfOneConversationCancelledByAnother() =
        runTest {
            val h = harness(Replies.text("a1"), Replies.text("b1"))
            val a = h.model.conversation { preEncodeAfterReply = true }
            a.send("u1").toList()
            val pre = h.awaitPreEncode()
            h.model
                .conversation()
                .send("other chat")
                .toList()
            assertEquals(1, pre.cancelCount)
        }

    @Test
    fun closingTheModelCancelsAPendingPreEncode() =
        runTest {
            val h = harness(Replies.text("a1"))
            h.model
                .conversation { preEncodeAfterReply = true }
                .send("u1")
                .toList()
            val pre = h.awaitPreEncode()
            h.model.closeAndJoin()
            assertTrue(pre.terminal)
        }

    @Test
    fun restoredCompactedConversationSendsIdenticalRequest() =
        runTest {
            val h =
                harness(
                    Replies.text("a1"),
                    Replies.text("a2"),
                    Replies.text("a3"),
                    Replies.text("S"),
                    Replies.text("x1"),
                    Replies.text("x2"),
                )
            val chat = threeTurns(h)
            chat.compact(preservedSteps = 2).toList()
            val saved = chat.toJson()
            chat.send("x").toList()
            val restored =
                h.model.restoreConversation(saved) {
                    tool("get_weather", "w", """{"type":"object"}""")
                }
            restored.send("x").toList()
            val bodies = h.chatBodies
            assertEquals(bodies[bodies.size - 2], bodies.last())
        }
}
