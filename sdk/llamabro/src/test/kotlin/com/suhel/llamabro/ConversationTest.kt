package com.suhel.llamabro

import com.suhel.llamabro.chat.ChatEvent
import com.suhel.llamabro.chat.Content
import com.suhel.llamabro.chat.FinishReason
import com.suhel.llamabro.chat.Message
import com.suhel.llamabro.conversation.ContextUsage
import com.suhel.llamabro.conversation.askAgain
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
    fun askReturnsValueWithReasoning() =
        runTest {
            val (engine, model) = setup()
            engine.model.script(Replies.text("Paris", reasoning = "France's capital"))
            assertEquals(
                Typed.Value("Paris", "Paris", "France's capital"),
                model.conversation().ask("Capital of France?"),
            )
        }

    @Test
    fun askWithoutReasoningHasNone() =
        runTest {
            val (engine, model) = setup()
            engine.model.script(Replies.text("Paris"))
            assertEquals(Typed.Value("Paris", "Paris", null), model.conversation().ask("Capital of France?"))
        }

    @Test
    fun askCutAtLengthIsIncomplete() =
        runTest {
            val (engine, model) = setup()
            engine.model.script(Replies.text("Par", finish = "length"))
            assertEquals(
                Typed.Incomplete(FinishReason.Length, "Par"),
                model.conversation().ask("Capital of France?"),
            )
        }

    @Test
    fun askAgainReplacesTheLastReplyAndReturnsIt() =
        runTest {
            val (engine, model) = setup()
            engine.model.script(Replies.text("Lyon"), Replies.text("Paris", reasoning = "the capital"))
            val chat = model.conversation()
            chat.ask("Capital of France?")
            assertEquals(Typed.Value("Paris", "Paris", "the capital"), chat.askAgain { sampling { seed = 3 } })
            assertEquals(listOf("Capital of France?", "Paris"), chat.messages.value.map { it.text() })
            val (first, again) = engine.model.chatBodies
            assertEquals(messagesOf(first), messagesOf(again))
            assertEquals("3", (Json.parseToJsonElement(again) as JsonObject)["seed"].toString())
        }

    @Test
    fun askAgainCutAtLengthIsIncomplete() =
        runTest {
            val (engine, model) = setup()
            engine.model.script(Replies.text("Paris"), Replies.text("Par", finish = "length"))
            val chat = model.conversation()
            chat.ask("Capital of France?")
            assertEquals(Typed.Incomplete(FinishReason.Length, "Par"), chat.askAgain())
        }

    @Test
    fun valueOrNullIsTheValueOnlyForAValue() {
        assertEquals("Paris", Typed.Value("Paris", "Paris", null).valueOrNull())
        assertEquals(null, Typed.Incomplete(FinishReason.Length, "Par").valueOrNull())
        assertEquals(null, Typed.Invalid("{", IllegalArgumentException("bad")).valueOrNull())
    }

    private fun Message.text(): String? =
        when (this) {
            is Message.User -> (content.first() as Content.Text).text
            is Message.Assistant -> content
            else -> null
        }

    @Test
    fun askWithAnAttachmentSendsIt() =
        runTest {
            val (engine, model) = setup()
            engine.model.script(Replies.text("A cat."))
            val chat = model.conversation()
            chat.ask("What is this?", Content.Image(byteArrayOf(9, 8, 7), "image/png"))
            val user = chat.messages.value.first() as Message.User
            assertEquals(2, user.content.size)
            assertEquals(Content.Text("What is this?"), user.content[0])
            assertTrue(user.content[1] is Content.Image)
            assertTrue(messagesOf(engine.model.chatBodies.single()).single().contains("image_url"))
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

    /** An error after some text keeps that text, so the person sees it; the tool call still in pieces is dropped. */
    @Test
    fun anErrorMidReplyKeepsTheStreamedTextWithoutPartialCalls() =
        runTest {
            val (engine, model) = setup()
            engine.model.onRequest = { r ->
                // the role, the text, the first piece of a tool call, then an error
                Replies.toolCalls(Triple("c1", "get_weather", "{}"), content = "half").take(3).forEach { r.chunk(it) }
                r.chunk("data: ${Replies.exceed(400, 256)}\n\n")
                r.done()
            }
            val chat = model.conversation()
            val e = runCatching { chat.send("q").toList() }.exceptionOrNull()
            assertTrue("$e", e is LlamaBroException.ContextExceeded)
            assertEquals(listOf(Message.user("q"), Message.Assistant("half")), chat.messages.value)
            assertNull(chat.reply.value)
        }

    /**
     * tools/ui creates the assistant message before streaming and a stop never removes it
     * (stores/chat/index.svelte.ts sendMessage + savePartialResponseIfNeeded), so the next request still alternates
     * roles. Templates like Gemma 3's raise "Conversation roles must alternate" otherwise (found on the phone).
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

    /** The natural call: a positional string is the system prompt (the old fromJson overload took it). */
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
