package com.suhel.llamabro.testing

import com.suhel.llamabro.DeviceKind
import com.suhel.llamabro.DevicePolicy
import com.suhel.llamabro.LlamaBro
import com.suhel.llamabro.LlamaBroException
import com.suhel.llamabro.LoadReport
import com.suhel.llamabro.MediaSource
import com.suhel.llamabro.Model
import com.suhel.llamabro.ModelSource
import com.suhel.llamabro.ModelTask
import com.suhel.llamabro.Ranked
import com.suhel.llamabro.Typed
import com.suhel.llamabro.chat.ChatEvent
import com.suhel.llamabro.chat.Content
import com.suhel.llamabro.chat.FinishReason
import com.suhel.llamabro.chat.Message
import com.suhel.llamabro.conversation
import com.suhel.llamabro.conversation.Conversation
import com.suhel.llamabro.conversation.JsonArgs
import com.suhel.llamabro.conversation.canCompact
import com.suhel.llamabro.conversation.compact
import com.suhel.llamabro.embed
import com.suhel.llamabro.rerank
import com.suhel.llamabro.transcribe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What an app's own test looks like (D27): a screen model that takes [LlamaBro], driven by [FakeLlamaBro]
 * on the JVM, with the real `Model`, `Conversation`, agent loop and compaction.
 */
class FakeLlamaBroTest {
    /** The app's code under test: the shape of design §6's ViewModel, without androidx. */
    private class ChatScreenModel(private val llama: LlamaBro) {
        private var model: Model? = null
        lateinit var chat: Conversation
        var compactions = 0

        suspend fun load() {
            model = llama.loadModel(ModelSource.file("/models/any.gguf"))
            chat =
                model!!.conversation(system = "You are terse.") {
                    tool(
                        "get_weather",
                        "Current weather for a city",
                        WEATHER_SCHEMA,
                    ) { args -> "sunny in ${args.string("city")}" }
                }
        }

        suspend fun send(text: String) {
            try {
                chat.send(text).collect()
            } catch (e: LlamaBroException.ContextExceeded) {
                if (!chat.canCompact(preservedSteps = 2)) throw e
                compactions++
                chat.compact(preservedSteps = 2).collect()
                chat.resume().collect()
            }
        }

        fun close() {
            model?.close()
        }
    }

    /** Device resolution runs over the fake's CPU-only engine, so apps' tests need nothing new. */
    @Test
    fun modelLoadsOnCpuWithNoAttempts() =
        runBlocking {
            val fake = FakeLlamaBro { reply { content("Hi") } }
            val model = fake.llamaBro.loadModel(ModelSource.file("/fake.gguf")) { devicePolicy = DevicePolicy.Auto }
            assertEquals(DeviceKind.Cpu, model.loadReport.device)
            assertEquals("cpu", model.loadReport.backend)
            assertEquals(emptyList<LoadReport.Attempt>(), model.loadReport.attempts)
            assertEquals(listOf(DeviceKind.Cpu), fake.llamaBro.devices().map { it.kind })
            model.close()
        }

    @Test
    fun replyReachesTheConversation() =
        runBlocking {
            val fake =
                FakeLlamaBro {
                    reply {
                        reasoning("Greet back.")
                        content("Hello!")
                    }
                }
            val screen = ChatScreenModel(fake.llamaBro).apply { load() }
            screen.send("Hi")
            val last =
                screen.chat.messages.value
                    .last() as Message.Assistant
            assertEquals("Hello!", last.content)
            assertEquals("Greet back.", last.reasoning)
            assertNull(screen.chat.reply.value)
            assertEquals(1, fake.chatRequests.size)
            assertTrue(fake.chatRequests[0], fake.chatRequests[0].contains("\"content\":\"Hi\""))
            screen.close()
        }

    @Test
    fun toolLoopRunsTheAppsHandler() =
        runBlocking {
            val fake =
                FakeLlamaBro {
                    reply { toolCall("get_weather", """{"city":"Paris"}""") }
                    reply { content("It is sunny.") }
                }
            val screen = ChatScreenModel(fake.llamaBro).apply { load() }
            screen.send("Weather in Paris?")
            assertEquals(2, fake.chatRequests.size)
            assertTrue(fake.chatRequests[1], fake.chatRequests[1].contains("\"content\":\"sunny in Paris\""))
            assertEquals(
                "It is sunny.",
                (
                    screen.chat.messages.value
                        .last() as Message.Assistant
                ).content,
            )
        }

    @Test
    fun contextExceededThenCompactAndResume() =
        runBlocking {
            val fake =
                FakeLlamaBro {
                    reply { content("First answer.") }
                    reply { content("Second answer.") }
                    reply { content("Third answer.") }
                    contextExceeded(promptTokens = 9000, contextSize = 8192)
                    reply { content("A short summary.") } // the compaction request
                    reply { content("Answer after compacting.") }
                }
            val screen = ChatScreenModel(fake.llamaBro).apply { load() }
            screen.send("one")
            screen.send("two")
            screen.send("three")
            screen.send("four")
            assertEquals(1, screen.compactions)
            assertEquals(
                "Answer after compacting.",
                (
                    screen.chat.messages.value
                        .last() as Message.Assistant
                ).content,
            )
            assertEquals(6, fake.chatRequests.size)
        }

    @Test
    fun eventsArriveInLlamaServerOrder() =
        runBlocking {
            val fake =
                FakeLlamaBro {
                    reply {
                        reasoning("r")
                        content("abc")
                        timings(promptTokens = 20, cachedTokens = 5, predictedTokens = 3)
                    }
                }
            val model = fake.llamaBro.loadModel(ModelSource.file("/m.gguf"))
            val events = model.conversation().send("x").toList()
            assertEquals(
                listOf("ReasoningDelta", "ContentDelta", "Done"),
                events.map { it::class.simpleName }.distinct(),
            )
            val done = events.last() as ChatEvent.Done
            assertEquals(FinishReason.Stop, done.reason)
            assertEquals(5, done.timings!!.cachedTokens)
        }

    @Test
    fun askIsIncompleteOnLength() =
        runBlocking {
            val fake =
                FakeLlamaBro {
                    reply {
                        content("half an")
                        finishLength()
                    }
                }
            val chat = fake.llamaBro.loadModel(ModelSource.file("/m.gguf")).conversation()
            assertEquals(Typed.Incomplete(FinishReason.Length, "half an"), chat.ask("Explain everything."))
        }

    @Test
    fun unscriptedRequestFailsWithAClearMessage() =
        runBlocking {
            val fake = FakeLlamaBro()
            val chat = fake.llamaBro.loadModel(ModelSource.file("/m.gguf")).conversation()
            val e = runCatching { chat.send("x").collect() }.exceptionOrNull()
            assertTrue("$e", e is LlamaBroException.ServerError && e.message!!.contains("no reply scripted"))
        }

    @Test
    fun contextSizeIsTheModelInfo() =
        runBlocking {
            val fake = FakeLlamaBro { contextSize = 2048 }
            assertEquals(
                2048,
                fake.llamaBro
                    .loadModel(ModelSource.file("/m.gguf"))
                    .info.contextSize,
            )
        }

    @Test
    fun toolHandlersAreTestableAlone() {
        val args =
            JsonArgs.parse(
                """{"city":"Paris","days":3,"units":{"temp":"C"},"tags":["a","b"],"stops":[{"at":"Lyon"}]}""",
            )
        assertEquals("Paris", args.string("city"))
        assertEquals(3, args.int("days"))
        assertEquals("C", args.obj("units").string("temp"))
        assertEquals(listOf("a", "b"), args.stringList("tags"))
        assertEquals("Lyon", args.objList("stops").single().string("at"))
        assertNull(args.objOrNull("missing"))
    }

    /** A cancelled load stops at the fake's next progress step and leaves nothing open, as on the phone (D67). */
    @Test
    fun cancellingALoadStopsIt() =
        runBlocking {
            val fake = FakeLlamaBro()
            fake.loadMillis = 2_000
            var outcome: Throwable? = null
            val job =
                launch(Dispatchers.Default) {
                    outcome = runCatching { fake.llamaBro.loadModel(ModelSource.file("/fake.gguf")) }.exceptionOrNull()
                }
            delay(100)
            val cancelledAt = System.nanoTime()
            job.cancel()
            job.join()
            val ms = (System.nanoTime() - cancelledAt) / 1_000_000
            assertTrue("$outcome", outcome is CancellationException)
            assertTrue("loadModel returned $ms ms after the cancel", ms < 1_000)
            assertEquals(0, fake.openModels)

            fake.loadMillis = 0
            val model = fake.llamaBro.loadModel(ModelSource.file("/fake.gguf"))
            assertEquals(1, fake.openModels)
            model.closeAndJoin()
            assertEquals(0, fake.openModels)
        }

    @Test
    fun embedAndRerankAreScripted() =
        runBlocking {
            val fake =
                FakeLlamaBro {
                    embeddings(floatArrayOf(1f, 0f), floatArrayOf(0f, 1f))
                    ranking(0.1f, 0.9f, 0.5f)
                    inputTooLong(tokens = 700)
                }
            val embedder = fake.llamaBro.loadModel(ModelSource.file("/e.gguf")) { task = ModelTask.Embedding() }
            val vectors = embedder.embed(listOf("cats", "stocks"))
            assertEquals(listOf(listOf(1f, 0f), listOf(0f, 1f)), vectors.map { it.toList() })
            assertTrue(fake.embedRequests.single().contains("\"cats\""))

            val reranker = fake.llamaBro.loadModel(ModelSource.file("/r.gguf")) { task = ModelTask.Rerank }
            // scores are per document, in the documents' order; the answer is best first, cut at topN as upstream
            assertEquals(listOf(Ranked(1, 0.9f), Ranked(2, 0.5f)), reranker.rerank("q", listOf("a", "b", "c"), 2))
            assertTrue(fake.rerankRequests.single().contains("\"q\""))

            val e = runCatching { embedder.embed("long") }.exceptionOrNull()
            assertTrue("$e", e is LlamaBroException.InputTooLong && e.tokens == 700 && e.limit == 512)
            val unscripted = runCatching { embedder.embed("more") }.exceptionOrNull()
            assertTrue("$unscripted", unscripted is LlamaBroException.ServerError)
        }

    @Test
    fun audioAndTranscribeAreScripted() =
        runBlocking {
            val wav = "RIFF".toByteArray() + ByteArray(4) + "WAVEfmt ".toByteArray()
            val fake =
                FakeLlamaBro {
                    supportsAudio = true
                    reply { content("You asked about the weather.") }
                    transcript("What's the weather?")
                    transcript("What's the", cut = true)
                }
            val model = fake.llamaBro.loadModel(ModelSource.file("/omni.gguf"))
            val reply = model.conversation().ask("What did I say?", Content.Audio(MediaSource.bytes(wav)))
            assertEquals("You asked about the weather.", (reply as Typed.Value).value)
            assertTrue(fake.chatRequests.single().contains("lb-attachment:0"))
            assertEquals(
                Typed.Value("What's the weather?", "What's the weather?", null),
                model.transcribe(MediaSource.file("/a.wav")),
            )
            assertEquals(
                Typed.Incomplete(FinishReason.Length, "What's the"),
                model.transcribe(MediaSource.bytes(wav), language = "en") { sampling { maxTokens = 3 } },
            )
            assertTrue(fake.transcribeRequests[1].contains("\"language\":\"en\""))
            // a model that doesn't hear refuses audio before any request, as on the phone
            val deaf = FakeLlamaBro().llamaBro.loadModel(ModelSource.file("/text.gguf"))
            val e = runCatching { deaf.transcribe(MediaSource.bytes(wav)) }.exceptionOrNull()
            assertTrue("$e", e is LlamaBroException.InvalidRequest)
        }

    @Test
    fun cutTranscriptWithoutMaxTokensFailsTheScript() =
        runBlocking {
            val fake =
                FakeLlamaBro {
                    supportsAudio = true
                    transcript("What's the", cut = true)
                }
            val model = fake.llamaBro.loadModel(ModelSource.file("/omni.gguf"))
            val e = runCatching { model.transcribe(MediaSource.file("/a.wav")) }.exceptionOrNull()
            assertTrue("$e", e is LlamaBroException.ServerError)
            assertTrue("$e", e!!.message!!.contains("set maxTokens to script a cut transcript"))
        }

    private companion object {
        const val WEATHER_SCHEMA = """{"type":"object","properties":{"city":{"type":"string"}},"required":["city"]}"""
    }
}
