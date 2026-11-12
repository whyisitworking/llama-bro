package com.suhel.llamabro

import com.suhel.llamabro.chat.ChatEvent
import com.suhel.llamabro.chat.Content
import com.suhel.llamabro.chat.Message
import com.suhel.llamabro.conversation.ContextUsage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationTest {
    private suspend fun setup(): Pair<FakeEngine, Model> {
        val engine = FakeEngine()
        return engine to testLlamaBro(engine).loadModel(ModelSource.file("/m.gguf"))
    }

    private fun messagesOf(body: String): List<String> =
        ((Json.parseToJsonElement(body) as JsonObject)["messages"] as JsonArray).map { it.toString() }

    @Test
    fun historyGrowsAppendOnly() =
        runTest {
            val (engine, model) = setup()
            engine.model.script(Replies.text("Hello!", reasoning = "greet"), Replies.text("Fine."))
            val chat = model.conversation(system = "Be brief.")
            chat.send("Hi").toList()
            chat.send("How are you?").toList()
            val (b1, b2) = engine.model.chatBodies
            val m1 = messagesOf(b1)
            val m2 = messagesOf(b2)
            assertEquals(m1, m2.take(m1.size))
            assertEquals(
                listOf(
                    """{"role":"system","content":"Be brief."}""",
                    """{"role":"user","content":"Hi"}""",
                    """{"role":"assistant","content":"Hello!","reasoning_content":"greet"}""",
                    """{"role":"user","content":"How are you?"}""",
                ),
                m2,
            )
            assertEquals(5, chat.messages.value.size)
            assertEquals(Message.Assistant("Fine."), chat.messages.value.last())
        }

    @Test
    fun askReturnsContent() =
        runTest {
            val (engine, model) = setup()
            engine.model.script(Replies.text("Paris"))
            assertEquals("Paris", model.conversation().ask("Capital of France?"))
        }

    @Test
    fun restoredConversationSendsIdenticalRequest() =
        runTest {
            val (engine, model) = setup()
            engine.model.script(Replies.text("A cat.", reasoning = "looks"), Replies.text("x1"), Replies.text("x2"))
            val original = model.conversation(system = "sys") { defaults { sampling { temperature = 0.3f } } }
            original.send("What is this?", Content.Image(byteArrayOf(9, 8, 7), "image/png")).toList()
            val saved = original.toJson()
            original.send("x").toList()
            val restored = model.restoreConversation(saved) // the settings were saved with it (format v2)
            assertEquals(original.messages.value.dropLast(2), restored.messages.value)
            restored.send("x").toList()
            val bodies = engine.model.chatBodies
            assertEquals(bodies[1], bodies[2])
        }

    @Test
    fun contextExceededKeepsUserMessageAndResumeRetries() =
        runTest {
            val (engine, model) = setup()
            var n = 0
            engine.model.onRequest = { r ->
                if (n++ == 0) {
                    r.chunk(Replies.exceed(400, 256), status = 400)
                } else {
                    Replies.text("ok").forEach { r.chunk(it) }
                }
                r.done()
            }
            val chat = model.conversation()
            val e = runCatching { chat.send("long").toList() }.exceptionOrNull()
            assertTrue("$e", e is LlamaBroException.ContextExceeded)
            assertEquals(Message.user("long"), chat.messages.value.last())
            chat.resume().toList()
            val (b1, b2) = engine.model.chatBodies
            assertEquals(b1, b2)
            assertEquals(Message.Assistant("ok"), chat.messages.value.last())
        }

    @Test
    fun endReasoningPostsControl() =
        runTest {
            val (engine, model) = setup()
            lateinit var chatRequest: FakeRequest
            engine.model.onRequest = { r ->
                if (r.endpoint == "chat") {
                    chatRequest = r
                    r.chunk(Replies.text("", reasoning = "hmm", id = "chatcmpl-77").take(2).joinToString(""))
                } else {
                    r.chunk("{}")
                    r.done()
                }
            }
            val chat = model.conversation()
            val started = CompletableDeferred<Unit>()
            val job =
                launch { chat.send("think").collect { if (it is ChatEvent.ReasoningDelta) started.complete(Unit) } }
            started.await()
            chat.endReasoning()
            val control = engine.model.requests.single { it.endpoint == "control" }
            assertEquals("""{"id":"chatcmpl-77","action":"reasoning_end"}""", control.body)
            Replies.text("done", id = "chatcmpl-77").drop(1).forEach { chatRequest.chunk(it) }
            chatRequest.done()
            job.join()
        }

    @Test
    fun contextUsageFromTimings() =
        runTest {
            val (engine, model) = setup()
            engine.model.script(
                Replies.text("hi", timings = Replies.timings(promptN = 10, cacheN = 90, predictedN = 5)),
            )
            val chat = model.conversation()
            chat.send("hello").toList()
            assertEquals(ContextUsage(105, 8192), chat.contextUsage.value)
        }

    @Test
    fun busyRaisedOnCollect() =
        runTest {
            val (engine, model) = setup()
            engine.model.onRequest = { r -> r.chunk(Replies.text("x").take(2).joinToString("")) } // never finishes
            val chat = model.conversation()
            val started = CompletableDeferred<Unit>()
            val job = launch { chat.send("a").collect { started.complete(Unit) } }
            started.await()
            val second = chat.send("b") // creating the flow doesn't throw
            val e = runCatching { second.toList() }.exceptionOrNull()
            assertTrue("$e", e is LlamaBroException.Busy)
            job.cancel()
            job.join()
            // after the first send ends, sending works again
            engine.model.script(Replies.text("y"))
            chat.send("c").toList()
        }

    @Test
    fun stoppedReplyKeepsStreamedText() =
        runTest {
            val (engine, model) = setup()
            engine.model.onRequest = { r -> Replies.text("partial answer").dropLast(2).forEach { r.chunk(it) } }
            val chat = model.conversation()
            val got = CompletableDeferred<Unit>()
            val job = launch { chat.send("q").collect { if (it is ChatEvent.ContentDelta) got.complete(Unit) } }
            got.await()
            job.cancel()
            job.join()
            val last = chat.messages.value.last() as Message.Assistant
            assertTrue(last.content!!.isNotEmpty())
            assertTrue(last.toolCalls.isEmpty())
        }

    /**
     * tools/ui creates the assistant message before streaming and a stop never removes it
     * (stores/chat/index.svelte.ts sendMessage + savePartialResponseIfNeeded), so the next request still alternates
     * roles. Templates like Gemma 3's raise "Conversation roles must alternate" otherwise (found on the phone, P1 T12).
     */
    @Test
    fun stopBeforeAnyTokenKeepsEmptyAssistant() =
        runTest {
            val (engine, model) = setup()
            var n = 0
            val firstStarted = CompletableDeferred<Unit>()
            engine.model.onRequest = { r ->
                if (n++ == 0) {
                    firstStarted.complete(Unit)
                } else {
                    Replies.text("OK").forEach { r.chunk(it) }
                    r.done()
                }
            }
            val chat = model.conversation()
            val job = launch { chat.send("long prompt").collect() }
            firstStarted.await()
            job.cancel()
            job.join()
            assertEquals(Message.Assistant(""), chat.messages.value.last())
            chat.send("Say OK.").toList()
            assertEquals(
                listOf(
                    """{"role":"user","content":"long prompt"}""",
                    """{"role":"assistant","content":""}""",
                    """{"role":"user","content":"Say OK."}""",
                ),
                messagesOf(engine.model.chatBodies.last()),
            )
        }

    /** The natural call: a positional string is the system prompt (P1 review: the old fromJson overload took it). */
    @Test
    fun positionalStringIsTheSystemPrompt() =
        runTest {
            val (engine, model) = setup()
            engine.model.script(Replies.text("x"))
            model.conversation("You are helpful.").send("hi").toList()
            assertEquals(
                """{"role":"system","content":"You are helpful."}""",
                messagesOf(engine.model.chatBodies.single()).first(),
            )
        }

    @Test
    fun emptySystemPromptNotSent() =
        runTest {
            val (engine, model) = setup()
            engine.model.script(Replies.text("x"))
            model.conversation(system = "  ").send("hi").toList()
            assertEquals(listOf("""{"role":"user","content":"hi"}"""), messagesOf(engine.model.chatBodies.single()))
        }
}
