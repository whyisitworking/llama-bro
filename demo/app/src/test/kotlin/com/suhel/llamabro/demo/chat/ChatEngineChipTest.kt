package com.suhel.llamabro.demo.chat

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.suhel.llamabro.DeviceKind
import com.suhel.llamabro.DevicePolicy
import com.suhel.llamabro.chat.ChatEvent
import com.suhel.llamabro.demo.data.ChatRepository
import com.suhel.llamabro.demo.data.ChipChoice
import com.suhel.llamabro.demo.data.ImageStore
import com.suhel.llamabro.demo.data.MessageKind
import com.suhel.llamabro.demo.data.MessageState
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
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
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

/** The chip chosen in Settings › Memory (D72): the load's policy, the note on a fallback, and the reload on a change. */
class ChatEngineChipTest {
    @get:Rule val folder = TemporaryFolder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val swift = HfModel.parse("unsloth/Qwen3.5-0.8B-GGUF:Q4_K_M")

    // the fake has a CPU only: a GPU or NPU asked for falls back to it, as on a phone without one
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

    @Test fun eachChoiceMapsToAutoOrPrefer() {
        assertEquals(DevicePolicy.Auto, ChipChoice.Automatic.policy)
        assertEquals(DevicePolicy.Prefer(DeviceKind.Npu), ChipChoice.Npu.policy)
        assertEquals(DevicePolicy.Prefer(DeviceKind.Gpu), ChipChoice.Gpu.policy)
        assertEquals(DevicePolicy.Prefer(DeviceKind.Cpu), ChipChoice.Cpu.policy)
    }

    @Test fun aChipThatCouldNotBeUsedIsNotedOnEveryLoad() =
        runBlocking {
            settings.setChip(ChipChoice.Gpu)
            fake.reply { content("Hi!") }
            val chat = newChat()
            chat.send("hello", emptyList())
            answered(chat, 1)
            // the reply came, on the chip that loaded it; the note says where and why, and opens the choice
            val fallback = ChipFallback(DeviceKind.Cpu, DeviceKind.Gpu, "this phone has no GPU")
            val note = Limits.chipFallback("Qwen3.5 0.8B", fallback)
            assertEquals("Qwen3.5 0.8B is running on the CPU: this phone has no GPU.", note.line)
            assertEquals(note, chat.limit.value)
            assertEquals(LimitAction.OpenMemorySettings, note.action)
            assertTrue(note.dismissible)
            assertEquals(DevicePolicy.Prefer(DeviceKind.Gpu), ready().config.policy)
            assertEquals(DeviceKind.Cpu, ready().model.loadReport.device)
            // Settings › Memory says it too, under the choice it was loaded for
            assertEquals(fallback, ready().model.loadReport.fallbackFrom(ready().config.policy))
            assertEquals("Running on the CPU: this phone has no GPU.", runsOnLine(ready(), ChipChoice.Gpu, false))
            // a turn on the model already loaded doesn't say it again
            fake.reply { content("Again.") }
            chat.send("once more", emptyList())
            answered(chat, 2)
            assertNull(chat.limit.value)
            // loaded again by the next message: noted again
            assertTrue(engine.releaseModelIfIdle())
            fake.reply { content("Back.") }
            chat.send("and again", emptyList())
            answered(chat, 3)
            assertEquals(note, chat.limit.value)
        }

    @Test fun automaticAndAChipThatLoadedSayNothing() =
        runBlocking {
            fake.reply { content("Hi!") }
            val chat = newChat()
            chat.send("hello", emptyList())
            answered(chat, 1)
            assertNull(chat.limit.value)
            assertEquals(DevicePolicy.Auto, ready().config.policy)
            assertNull(ready().model.loadReport.fallbackFrom(ready().config.policy))

            settings.setChip(ChipChoice.Cpu)
            memory.loadConfigChanged { LoadConfig(ChipChoice.Cpu.policy) }
            fake.reply { content("On the CPU.") }
            chat.send("again", emptyList())
            answered(chat, 2)
            assertNull(chat.limit.value)
            assertEquals(DevicePolicy.Prefer(DeviceKind.Cpu), ready().config.policy)
        }

    @Test fun aChangedChoiceFreesTheModelAndTheNextMessageLoadsItOnTheNewChip() =
        runBlocking {
            fake.reply { content("Hi!") }
            val chat = newChat()
            chat.send("hello", emptyList())
            answered(chat, 1)
            val first = ready().model

            // the same choice again keeps it
            memory.loadConfigChanged { LoadConfig(ChipChoice.Automatic.policy) }
            assertSame(first, ready().model)

            settings.setChip(ChipChoice.Gpu)
            memory.loadConfigChanged { LoadConfig(ChipChoice.Gpu.policy) }
            assertEquals(HostState.Idle, host.state.value)

            fake.reply { content("Reloaded.") }
            chat.send("again", emptyList())
            answered(chat, 2)
            assertEquals(DevicePolicy.Prefer(DeviceKind.Gpu), ready().config.policy)
            assertTrue(ready().model !== first)
        }

    @Test fun theHostNeverReusesAModelLoadedForAnotherChoice() =
        runBlocking {
            val auto = host.use(swift, LoadConfig(DevicePolicy.Auto))
            val cpu = host.use(swift, LoadConfig(DevicePolicy.Prefer(DeviceKind.Cpu)))
            assertTrue(auto !== cpu)
            assertSame(cpu, host.use(swift, LoadConfig(DevicePolicy.Prefer(DeviceKind.Cpu))))
        }

    @Test fun aTurnBesideAnotherSharesTheLoadWhateverItsPolicy() =
        runBlocking {
            val auto = host.hold(swift, LoadConfig())
            val shared = host.hold(swift, LoadConfig(DevicePolicy.Prefer(DeviceKind.Gpu)), keepConfig = { true })
            assertSame(auto.model, shared.model)
            assertEquals(DevicePolicy.Auto, shared.config.policy)
            assertEquals(DevicePolicy.Auto, ready().config.policy)
            // a release while a turn holds it closes nothing
            assertFalse(host.release { true })
            assertSame(auto.model, ready().model)
            auto.release()
            shared.release()
        }

    @Test fun aModelAlreadyOnTheChipChosenSaysNothingMoves() =
        runBlocking {
            host.use(swift, LoadConfig(DevicePolicy.Auto)) // the fake's CPU
            assertNull(runsOnLine(ready(), ChipChoice.Cpu, replying = false))
            assertEquals(
                "Moves to the GPU with the next message.",
                runsOnLine(ready(), ChipChoice.Gpu, replying = false),
            )
        }

    @Test fun aWaitReadsTheChoiceAsItIsWhenTheReplyEnds() =
        runBlocking {
            val gate = CompletableDeferred<Unit>()
            firstTurnWaitsFor(gate)
            fake.reply { content("Done.") }
            val chat = newChat()
            chat.send("go", emptyList())
            until("streaming") { chat.streaming.value != null }
            val model = ready().model
            // NPU, then back to Automatic, while the reply streams: an older wait must not free the model
            settings.setChip(ChipChoice.Npu)
            val first =
                async {
                    memory.loadConfigChanged { settings.settings.first().loadConfig }
                }
            settings.setChip(ChipChoice.Automatic)
            val second =
                async {
                    memory.loadConfigChanged { settings.settings.first().loadConfig }
                }
            gate.complete(Unit)
            answered(chat, 1)
            first.await()
            second.await()
            assertSame(model, ready().model)
        }

    /** A streamer whose first turn waits for [gate] before the fake answers; later turns answer at once. */
    private fun firstTurnWaitsFor(gate: CompletableDeferred<Unit>) {
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
    }

    @Test fun aChipChangedWhileAReplyStreamsDoesNotCutItOffWhenAnotherChatSends() =
        runBlocking {
            val gate = CompletableDeferred<Unit>()
            firstTurnWaitsFor(gate)
            repeat(3) { fake.reply { content("Done.") } }
            val a = newChat()
            a.send("go", emptyList())
            until("A streams") { a.streaming.value != null }
            val model = ready().model

            settings.setChip(ChipChoice.Gpu)
            val change = async { memory.loadConfigChanged { LoadConfig(ChipChoice.Gpu.policy) } }
            // another chat on the same model sends while A's reply streams: it shares A's model, loaded for Automatic
            val b = engine.open(0)
            b.send("hi", emptyList())
            answered(b, 1)
            assertSame(model, ready().model)
            assertEquals(DevicePolicy.Auto, ready().config.policy)
            assertNull(b.limit.value)

            gate.complete(Unit)
            answered(a, 1)
            // both replies ended: the change frees the model, and the next message loads it for the GPU
            change.await()
            assertEquals(HostState.Idle, host.state.value)
            b.send("again", emptyList())
            answered(b, 2)
            assertEquals(DevicePolicy.Prefer(DeviceKind.Gpu), ready().config.policy)
        }

    @Test fun aChangeWhoseWaitIsCancelledStillReloadsWithTheNextMessage() =
        runBlocking {
            val gate = CompletableDeferred<Unit>()
            firstTurnWaitsFor(gate)
            repeat(2) { fake.reply { content("Done.") } }
            val chat = newChat()
            chat.send("go", emptyList())
            until("streaming") { chat.streaming.value != null }
            val model = ready().model

            settings.setChip(ChipChoice.Gpu)
            val change = async { memory.loadConfigChanged { LoadConfig(ChipChoice.Gpu.policy) } }
            delay(100)
            assertEquals("Moves to the GPU after this reply.", runsOnLine(ready(), ChipChoice.Gpu, replying = true))
            // the person left Settings: the wait goes with its screen
            change.cancelAndJoin()
            gate.complete(Unit)
            answered(chat, 1)
            assertSame(model, ready().model)
            assertEquals(
                "Moves to the GPU with the next message.",
                runsOnLine(ready(), ChipChoice.Gpu, replying = false),
            )

            chat.send("again", emptyList())
            answered(chat, 2)
            assertTrue(ready().model !== model)
            assertEquals(DevicePolicy.Prefer(DeviceKind.Gpu), ready().config.policy)
            // loaded for the GPU now, it says where it runs instead
            assertEquals("Running on the CPU: this phone has no GPU.", runsOnLine(ready(), ChipChoice.Gpu, false))
        }

    @Test fun aChangeWhileAReplyIsWrittenWaitsForItsEnd() =
        runBlocking {
            val gate = CompletableDeferred<Unit>()
            streamer = { _, _, _, _ ->
                flow {
                    emit(ChatEvent.ContentDelta("slow"))
                    gate.await()
                }
            }
            val chat = newChat()
            chat.send("go", emptyList())
            until("streaming") { chat.streaming.value?.text == "slow" }
            val loaded = ready().model

            settings.setChip(ChipChoice.Cpu)
            val change = async { memory.loadConfigChanged { LoadConfig(ChipChoice.Cpu.policy) } }
            delay(200)
            // the reply goes on, on the model it started on
            assertFalse(change.isCompleted)
            assertSame(loaded, ready().model)

            gate.complete(Unit)
            change.await()
            assertEquals(HostState.Idle, host.state.value)
        }
}
