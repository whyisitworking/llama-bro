package com.suhel.llamabro.demo.chat

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.suhel.llamabro.ContextSize
import com.suhel.llamabro.chat.ChatEvent
import com.suhel.llamabro.demo.data.ChatRepository
import com.suhel.llamabro.demo.data.ContextChoice
import com.suhel.llamabro.demo.data.ImageStore
import com.suhel.llamabro.demo.data.MessageKind
import com.suhel.llamabro.demo.data.MessageState
import com.suhel.llamabro.demo.data.ReplyLength
import com.suhel.llamabro.demo.data.SettingsRepository
import com.suhel.llamabro.demo.data.loadConfig
import com.suhel.llamabro.demo.models.FakeScheduler
import com.suhel.llamabro.demo.models.ModelRepository
import com.suhel.llamabro.demo.models.TestHub
import com.suhel.llamabro.demo.models.installedModel
import com.suhel.llamabro.hub.HfModel
import com.suhel.llamabro.testing.FakeLlamaBro
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * The reply length (Settings › Sampling) reaching the request, and the context length (Settings › Memory) reaching the
 * load and reloading the model as a chip change does (D72).
 */
class ChatEngineLengthTest {
    @get:Rule val folder = TemporaryFolder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val swift = HfModel.parse("unsloth/Qwen3.5-0.8B-GGUF:Q4_K_M")
    private val fake = FakeLlamaBro()
    private val testHub = TestHub()
    private val hub get() = testHub.hub
    private val settings by lazy {
        SettingsRepository(
            PreferenceDataStoreFactory.create(scope = scope) { File(folder.root, "settings.preferences_pb") },
        )
    }
    private val chats by lazy {
        ChatRepository(FakeChatDao(), ImageStore(File(folder.root, "images")), System::currentTimeMillis)
    }
    private val host by lazy { ModelHost(fake.llamaBro, { hub.installedModel(it) }, scope) }
    private val models by lazy { ModelRepository(hub, settings, FakeScheduler(), scope, host) }
    private var streamer: Streamer? = null
    private val engine by lazy {
        val custom = streamer
        if (custom != null) {
            ChatEngine(chats, host, models, settings, System::currentTimeMillis, scope, streamer = custom)
        } else {
            ChatEngine(chats, host, models, settings, System::currentTimeMillis, scope)
        }
    }
    private val memory by lazy { ModelMemory(host, engine) }

    /** The model is on the phone: the host loads the hub's installed copy, as the app's does. */
    @Before fun installModel() = testHub.install(swift)

    @After fun stop() {
        runBlocking { host.release() }
        scope.cancel()
        testHub.close()
    }

    private suspend fun until(what: String, condition: suspend () -> Boolean) {
        withTimeoutOrNull(10_000) { while (!condition()) delay(10) }
            ?: throw AssertionError("timed out waiting for: $what")
    }

    private suspend fun newChat(): ChatEngine.ChatHandle {
        settings.setLastModel(swift.toString())
        testHub.install(swift)
        models.changed()
        return engine.open(0)
    }

    private suspend fun answered(chat: ChatEngine.ChatHandle, count: Int) =
        until("answered $count") {
            chat.messages.value.count { it.kind == MessageKind.Assistant && it.state == MessageState.Done } == count
        }

    private fun ready(): HostState.Ready = host.state.value as HostState.Ready

    private fun maxTokens(n: Int): Int? =
        Json
            .parseToJsonElement(fake.chatRequests[n])
            .jsonObject["max_tokens"]
            ?.jsonPrimitive
            ?.int

    private suspend fun chosen(): LoadConfig = settings.settings.first().loadConfig

    @Test fun theReplyLengthChosenReachesTheRequest() =
        runBlocking {
            repeat(4) { fake.reply { content("Hi.") } }
            val chat = newChat()
            // Automatic, Think Off: the level's own limit
            chat.send("one", emptyList())
            answered(chat, 1)
            assertEquals(2_048, maxTokens(0))

            settings.setReplyLength(ReplyLength.Short)
            chat.send("two", emptyList())
            answered(chat, 2)
            assertEquals(1_024, maxTokens(1))

            settings.setReplyLength(ReplyLength.Long)
            chat.send("three", emptyList())
            answered(chat, 3)
            assertEquals(8_192, maxTokens(2))

            // No limit sends none: the reply runs until the model stops or the context is full
            settings.setReplyLength(ReplyLength.NoLimit)
            chat.send("four", emptyList())
            answered(chat, 4)
            assertNull(maxTokens(3))
        }

    @Test fun theContextChosenReachesTheLoadAndMemorySaysWhatWasPicked() =
        runBlocking {
            settings.setContext(ContextChoice.UpTo8k)
            fake.reply { content("Hi.") }
            val chat = newChat()
            chat.send("hello", emptyList())
            answered(chat, 1)
            assertEquals(ContextSize.UpTo(8_192), ready().config.context)
            // the fake picks 8 192 tokens
            assertEquals(
                "0.0 GB · CPU · 8k context",
                host.state.value
                    .inMemory()
                    ?.detail,
            )
        }

    @Test fun aChangedContextFreesTheModelAndTheNextMessageLoadsIt() =
        runBlocking {
            fake.reply { content("Hi!") }
            val chat = newChat()
            chat.send("hello", emptyList())
            answered(chat, 1)
            val first = ready().model

            // the same config again keeps it
            memory.loadConfigChanged { chosen() }
            assertSame(first, ready().model)

            settings.setContext(ContextChoice.UpTo32k)
            memory.loadConfigChanged { chosen() }
            assertEquals(HostState.Idle, host.state.value)

            fake.reply { content("Reloaded.") }
            chat.send("again", emptyList())
            answered(chat, 2)
            assertEquals(ContextSize.UpTo(32_768), ready().config.context)
            assertTrue(ready().model !== first)
        }

    @Test fun aContextChangedWhileAReplyStreamsDoesNotCutItOffWhenAnotherChatSends() =
        runBlocking {
            val gate = CompletableDeferred<Unit>()
            val calls = AtomicInteger()
            streamer = { convo, text, images, options ->
                flow {
                    if (calls.getAndIncrement() == 0) {
                        emit(ChatEvent.ContentDelta("slow "))
                        gate.await()
                    }
                    emitAll(sdkStream(convo, text, images, options))
                }
            }
            repeat(3) { fake.reply { content("Done.") } }
            val a = newChat()
            a.send("go", emptyList())
            until("A streams") { a.streaming.value != null }
            val model = ready().model

            settings.setContext(ContextChoice.UpTo4k)
            val change = async { memory.loadConfigChanged { chosen() } }
            // another chat sends while A's reply streams: it shares A's model, loaded for Automatic
            val b = engine.open(0)
            b.send("hi", emptyList())
            answered(b, 1)
            assertSame(model, ready().model)
            assertEquals(ContextSize.Auto, ready().config.context)
            assertFalse(change.isCompleted)

            gate.complete(Unit)
            answered(a, 1)
            // both replies ended: the change frees the model, and the next message loads it at the new length
            change.await()
            assertEquals(HostState.Idle, host.state.value)
            b.send("again", emptyList())
            answered(b, 2)
            assertEquals(ContextSize.UpTo(4_096), ready().config.context)
        }

    @Test fun aReplyThatFillsTheContextEndsAsTooLongInsteadOfCut() =
        runBlocking {
            settings.setReplyLength(ReplyLength.NoLimit)
            // the fake's context is 8 192 tokens: this reply ends there, with the chat filling it
            fake.reply {
                content("On and on")
                timings(promptTokens = 8_000, predictedTokens = 192)
                finishLength()
            }
            // a reply cut at its own limit, with room left, stays cut and can be continued
            fake.reply {
                content("Cut short")
                timings(promptTokens = 100, predictedTokens = 50)
                finishLength()
            }
            val chat = newChat()
            chat.send("tell me everything", emptyList())
            until("failed") { chat.messages.value.any { it.state == MessageState.Failed } }
            val limit = Limits.contextExceeded("Qwen3.5 0.8B")
            val reply = chat.messages.value.last()
            assertEquals(MessageKind.Assistant, reply.kind)
            assertEquals(limit.line, reply.failure)
            assertEquals("On and on", reply.text)
            assertEquals(limit, chat.limit.value)

            chat.send("shorter", emptyList())
            until("cut") {
                chat.messages.value
                    .lastOrNull()
                    ?.state == MessageState.Cut
            }
        }

    @Test fun theLineUnderTheContextSaysWhenTheModelReloads() =
        runBlocking {
            host.use(swift, LoadConfig())
            assertNull(contextLine(ready(), ContextChoice.Automatic, replying = false))
            assertEquals("Reloads at 8k with the next message.", contextLine(ready(), ContextChoice.UpTo8k, false))
            assertEquals("Reloads at 8k after this reply.", contextLine(ready(), ContextChoice.UpTo8k, true))
            assertNull(contextLine(null, ContextChoice.UpTo8k, false))
            host.use(swift, LoadConfig(context = ContextSize.UpTo(8_192)))
            assertNull(contextLine(ready(), ContextChoice.UpTo8k, false))
            assertEquals(
                "Reloads at the most that fits with the next message.",
                contextLine(ready(), ContextChoice.Automatic, false),
            )
        }
}
