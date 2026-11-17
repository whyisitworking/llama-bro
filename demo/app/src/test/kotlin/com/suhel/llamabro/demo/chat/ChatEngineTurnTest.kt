package com.suhel.llamabro.demo.chat

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.suhel.llamabro.ModelSource
import com.suhel.llamabro.chat.ChatEvent
import com.suhel.llamabro.chat.Content
import com.suhel.llamabro.conversation.Conversation
import com.suhel.llamabro.demo.data.ChatRepository
import com.suhel.llamabro.demo.data.ImageStore
import com.suhel.llamabro.demo.data.MessageKind
import com.suhel.llamabro.demo.data.MessageState
import com.suhel.llamabro.demo.data.SettingsRepository
import com.suhel.llamabro.demo.data.StoredMessage
import com.suhel.llamabro.demo.designsystem.components.ThinkLevel
import com.suhel.llamabro.demo.models.FakeScheduler
import com.suhel.llamabro.demo.models.ModelRepository
import com.suhel.llamabro.demo.models.NotOnPhone
import com.suhel.llamabro.demo.models.TestHub
import com.suhel.llamabro.demo.models.installedModel
import com.suhel.llamabro.hub.HfModel
import com.suhel.llamabro.testing.FakeLlamaBro
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** A turn's life as one job: the load and the reply, stopped, waited for, released (the engine's set-up as ChatEngineTest's). */
class ChatEngineTurnTest {
    @get:Rule val folder = TemporaryFolder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val swift = HfModel.parse("unsloth/Qwen3.5-0.8B-GGUF:Q4_K_M")

    private val fake = FakeLlamaBro()
    private val testHub = TestHub()
    private val hub get() = testHub.hub
    private val dao = FakeChatDao()
    private val settings by lazy {
        SettingsRepository(
            PreferenceDataStoreFactory.create(scope = scope) { File(folder.root, "settings.preferences_pb") },
        )
    }
    private val chats by lazy {
        ChatRepository(
            dao,
            ImageStore(File(folder.root, "images")),
            System::currentTimeMillis,
        )
    }

    /** Where a load finds its files: the hub's installed copy, unless a test holds the load back ([slowLoad]). */
    private var onSource: suspend (HfModel) -> ModelSource = { hub.installedModel(it) }
    private val host by lazy { ModelHost(fake.llamaBro, { onSource(it) }, scope) }
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

    @After fun stop() {
        runBlocking { host.release() }
        scope.cancel()
        testHub.close()
    }

    private fun onPhone(vararg keys: HfModel) {
        testHub.install(*keys)
        models.changed()
    }

    private suspend fun until(what: String, timeoutMillis: Long = 10_000, condition: suspend () -> Boolean) {
        withTimeoutOrNull(timeoutMillis) { while (!condition()) delay(10) }
            ?: throw AssertionError("timed out waiting for: $what")
    }

    private fun rows(handle: ChatEngine.ChatHandle): List<StoredMessage> = handle.messages.value

    private fun reply(text: String) =
        fake.reply {
            content(text)
            timings(promptTokens = 10, predictedTokens = 20)
        }

    private suspend fun newChat(): ChatEngine.ChatHandle {
        settings.setLastModel(swift.toString())
        onPhone(swift)
        return engine.open(0)
    }

    /** A load that waits for [gate]: the model stays waking until the test opens it. */
    private fun slowLoad(gate: CompletableDeferred<Unit>) {
        onSource = {
            gate.await()
            hub.installedModel(it)
        }
    }

    @Test fun stopWhileTheModelWakesEndsTheTurnAndKeepsTheMessageHeld() =
        runBlocking {
            val gate = CompletableDeferred<Unit>()
            slowLoad(gate)
            reply("never")
            val chat = newChat()
            try {
                chat.send("go", emptyList())
                until("waking") { chat.busy.value && host.state.value is HostState.Waking }
                chat.stop()
                until("the turn ended") { !chat.busy.value }
                // the question waits for its model; no reply row was made, so none is left streaming
                assertEquals(listOf(MessageKind.User), rows(chat).map { it.kind })
                assertEquals(MessageState.Held, rows(chat).single().state)
                assertNull(chat.streaming.value)
            } finally {
                gate.complete(Unit)
            }
        }

    @Test fun editWhileTheModelWakesWaitsForTheLoadAndAnswersOnce() =
        runBlocking {
            val gate = CompletableDeferred<Unit>()
            slowLoad(gate)
            reply("the answer")
            val chat = newChat()
            try {
                chat.send("go", emptyList())
                until("waking") { chat.busy.value && host.state.value is HostState.Waking }
                chat.edit(0, "changed")
                until("edited") { rows(chat).map { it.text } == listOf("changed") }
            } finally {
                gate.complete(Unit)
            }
            until("answered") { rows(chat).any { it.kind == MessageKind.Assistant && it.state == MessageState.Done } }
            delay(200) // a second, stale turn would have written by now
            assertEquals(listOf("changed", "the answer"), rows(chat).map { it.text })
            assertEquals(1, fake.chatRequests.size)
        }

    @Test fun theModelIsReleasedOnlyWhenNoTurnIsUnderWay() =
        runBlocking {
            streamer = { _, _, _, _ ->
                flow {
                    emit(ChatEvent.ContentDelta("slow"))
                    delay(60_000)
                }
            }
            val chat = newChat()
            chat.send("go", emptyList())
            until("streaming") { chat.streaming.value?.text == "slow" }
            assertFalse(engine.releaseModelIfIdle())
            assertTrue(host.state.value is HostState.Ready)
            chat.stop()
            until("idle") { !chat.busy.value }
            assertTrue(engine.releaseModelIfIdle())
            assertEquals(HostState.Idle, host.state.value)
        }

    @Test fun aChatAnswersAgainAfterItsModelWasReleased() =
        runBlocking {
            reply("First.")
            val chat = newChat()
            chat.send("Hi", emptyList())
            until("first reply") { rows(chat).count { it.state == MessageState.Done } == 2 }
            // the app went to the background (or the person unloaded it): the next send loads the model again
            assertTrue(engine.releaseModelIfIdle())
            reply("Second.")
            chat.send("Again", emptyList())
            until("second reply, or a failure") {
                rows(chat).count { it.kind == MessageKind.Assistant && it.state != MessageState.Streaming } == 2
            }
            assertEquals("Second.", rows(chat).last().text)
            assertEquals(MessageState.Done, rows(chat).last().state)
        }

    @Test fun aTurnWaitsForTheRecoveryOfStaleReplies() =
        runBlocking {
            val recovered = CompletableDeferred<Unit>()
            reply("hi")
            settings.setLastModel(swift.toString())
            onPhone(swift)
            val chat =
                ChatEngine(chats, host, models, settings, System::currentTimeMillis, scope, beforeTurn = {
                    recovered.await()
                }).open(0)
            chat.send("go", emptyList())
            delay(200)
            assertTrue(rows(chat).none { it.kind == MessageKind.Assistant })
            recovered.complete(Unit)
            until("answered") { rows(chat).any { it.kind == MessageKind.Assistant && it.state == MessageState.Done } }
        }

    @Test fun deletingAChatFromElsewhereStopsItsReplyFirst() =
        runBlocking {
            // the drawer deletes a chat whose reply streams: the reply reads the chat's pictures until it ends (D87)
            var ended = false
            streamer = { _, _, _, _ ->
                flow {
                    try {
                        emit(ChatEvent.ContentDelta("slow"))
                        delay(60_000)
                    } finally {
                        ended = true
                    }
                }
            }
            val chat = newChat()
            chat.send("go", emptyList())
            until("streaming") { chat.streaming.value?.text == "slow" }
            val id = chat.chatId.value
            engine.delete(id).join()
            assertTrue("the reply ended before the chat went", ended)
            assertNull(chats.modelKey(id))
            until("idle") { !chat.busy.value }
        }

    @Test fun deletingAChatWithAnotherMessageHeldStartsNoAnswerToIt() =
        runBlocking {
            // two questions wait for the model; the first one's reply streams when the chat is deleted: the pump must
            // not go on to answer the second, whose pictures and recordings the delete is removing (D87)
            val calls =
                java.util.concurrent.atomic
                    .AtomicInteger()
            streamer = { _, _, _, _ ->
                calls.incrementAndGet()
                flow {
                    emit(ChatEvent.ContentDelta("slow"))
                    delay(60_000)
                }
            }
            settings.setLastModel(swift.toString())
            val chat = engine.open(0)
            until("the model is known") { chat.current.value == swift }
            assertTrue(chat.send("one", emptyList()))
            assertTrue(chat.send("two", emptyList()))
            onPhone(swift)
            until("streaming") { chat.streaming.value?.text == "slow" }
            val id = chat.chatId.value
            engine.delete(id).join()
            assertNull(chats.modelKey(id))
            until("idle") { !chat.busy.value }
            delay(300)
            assertEquals("no answer started after the delete", 1, calls.get())
        }

    @Test fun aChatDeletedFromTheDrawerTakesNoMoreMessages() =
        runBlocking {
            // the drawer deletes the chat on screen: its handle must not write to the deleted id, whose rows the
            // database refuses (a foreign key), nor edit, ask again or continue in it
            reply("one")
            val chat = newChat()
            assertTrue(chat.send("first", emptyList()))
            until("answered") { rows(chat).any { it.kind == MessageKind.Assistant && it.state == MessageState.Done } }
            val id = chat.chatId.value
            engine.delete(id).join()
            assertFalse("the deleted chat refuses the message", chat.send("second", emptyList()))
            chat.edit(0, "edited")
            chat.regenerate(1)
            chat.continueReply(1)
            delay(300)
            assertTrue(chats.messages(id).first().isEmpty())
            assertEquals(1, fake.chatRequests.size)
        }

    @Test fun aModelGoneSinceItWasChosenSaysSoInsteadOfTryAgain() =
        runBlocking {
            val chat = newChat()
            until("named") { chat.name.value.isNotEmpty() }
            // its files go, and the library hasn't been read again: the load finds it gone
            assertTrue(hub.delete(swift))
            chat.send("hi", emptyList())
            until("the line") { chat.limit.value != null }
            assertEquals(Limits.modelGone(chat.name.value), chat.limit.value)
            assertEquals(Limits.modelGone("Seer"), Limits.forLoadFailure("Seer", NotOnPhone(swift)))
        }
}
