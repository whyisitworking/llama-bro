package com.suhel.llamabro.demo.ui.settings

import android.content.ContextWrapper
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.suhel.llamabro.DeviceKind
import com.suhel.llamabro.chat.ChatEvent
import com.suhel.llamabro.demo.chat.ChatEngine
import com.suhel.llamabro.demo.chat.FakeChatDao
import com.suhel.llamabro.demo.chat.HostState
import com.suhel.llamabro.demo.chat.ModelHost
import com.suhel.llamabro.demo.chat.ModelMemory
import com.suhel.llamabro.demo.chat.SamplingStore
import com.suhel.llamabro.demo.chat.sdkStream
import com.suhel.llamabro.demo.data.ChatRepository
import com.suhel.llamabro.demo.data.ChipChoice
import com.suhel.llamabro.demo.data.ContextChoice
import com.suhel.llamabro.demo.data.ImageStore
import com.suhel.llamabro.demo.data.MessageState
import com.suhel.llamabro.demo.data.SettingsRepository
import com.suhel.llamabro.demo.data.TokenStore
import com.suhel.llamabro.demo.data.settings.LoadConfig
import com.suhel.llamabro.demo.eventually
import com.suhel.llamabro.demo.models.DeviceFacts
import com.suhel.llamabro.demo.models.FakeScheduler
import com.suhel.llamabro.demo.models.ModelRepository
import com.suhel.llamabro.demo.models.TestHub
import com.suhel.llamabro.demo.models.installedModel
import com.suhel.llamabro.demo.ui.keepAlive
import com.suhel.llamabro.demo.ui.shutDown
import com.suhel.llamabro.hub.HfModel
import com.suhel.llamabro.testing.FakeLlamaBro
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** Settings › Memory's chip choice as the screen reads it (D72): the kinds marked, and the line under the choice. */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelChipTest {
    @get:Rule val folder = TemporaryFolder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val collectors = CoroutineScope(Dispatchers.Unconfined)
    private val swift = HfModel.parse("unsloth/Qwen3.5-0.8B-GGUF:Q4_K_M")
    private val fake = FakeLlamaBro()
    private val testHub = TestHub()
    private val hub get() = testHub.hub
    private val settings by lazy {
        SettingsRepository(PreferenceDataStoreFactory.create(scope = scope) { File(folder.root, "s.preferences_pb") })
    }
    private val chats by lazy { ChatRepository(FakeChatDao(), ImageStore(File(folder.root, "images")), { 1_000L }) }
    private val host by lazy { ModelHost(fake.llamaBro, { hub.installedModel(it) }, scope) }
    private val models by lazy { ModelRepository(hub, settings, FakeScheduler(), scope, host) }
    private val gate = CompletableDeferred<Unit>()

    // the first reply waits for [gate], so a chip can change while it streams
    private val engine by lazy {
        ChatEngine(chats, host, models, settings, { 1_000L }, scope, streamer = { convo, text, images, options ->
            flow {
                emit(ChatEvent.ContentDelta("slow "))
                gate.await()
                emitAll(sdkStream(convo, text, images, options))
            }
        })
    }
    private val viewModels = mutableListOf<SettingsViewModel>()

    private val main = UnconfinedTestDispatcher()

    @Before fun setUpMain() = Dispatchers.setMain(main)

    /** The model is on the phone: the host loads the hub's installed copy, as the app's does. */
    @Before fun installModel() = testHub.install(swift)

    @After fun stop() {
        gate.complete(Unit)
        shutDown(main, viewModels)
        collectors.cancel()
        runBlocking { host.release() }
        scope.cancel()
        testHub.close()
    }

    private fun viewModel(facts: suspend () -> DeviceFacts): SettingsViewModel =
        SettingsViewModel(
            settings,
            TokenStore(ContextWrapper(null), File(folder.root, "token.bin")),
            models,
            ModelMemory(host, engine),
            SamplingStore(settings, chats),
            facts,
        ).also { viewModels += it }

    @Test fun theChipsAreThePhonesOnceTheSdkAnswers() =
        runBlocking {
            val phone = DeviceFacts(8L shl 30, DeviceKind.Gpu, setOf(DeviceKind.Gpu, DeviceKind.Cpu))
            val chips = viewModel { phone }.chips.keepAlive(collectors)
            eventually("the phone's chips") { chips.value == setOf(DeviceKind.Gpu, DeviceKind.Cpu) }
        }

    @Test fun unknownFactsMarkNothingMissing() =
        runBlocking {
            val asked = AtomicBoolean()
            val chips =
                viewModel {
                    asked.set(true)
                    DeviceFacts.Fallback
                }.chips.keepAlive(collectors)
            // the answer is emitted right after it is asked, on the same unconfined collector
            eventually("the SDK is asked") { asked.get() }
            assertEquals(DeviceKind.entries.toSet(), chips.value)
        }

    @Test fun theLineUnderTheChoiceSaysWhereTheModelRunsOrWhenItMoves() =
        runBlocking {
            val vm = viewModel { DeviceFacts.Fallback }
            val line = vm.runsOn.keepAlive(collectors)
            assertNull(line.value) // nothing loaded

            // loaded for the GPU on a phone with a CPU only (the fake): where it runs and why
            settings.setChip(ChipChoice.Gpu)
            host.use(swift, LoadConfig(ChipChoice.Gpu.policy))
            eventually("the fallback") { line.value == "Running on the CPU: this phone has no GPU." }

            // the choice changed, and the model loaded for the GPU is still in memory: when it moves
            settings.setChip(ChipChoice.Automatic)
            eventually("the move") { line.value == "Moves to the fastest chip with the next message." }

            host.release()
            eventually("nothing loaded") { line.value == null }
        }

    @Test fun choosingAChipAndBackDuringAReplyKeepsTheModel() =
        runBlocking {
            settings.setLastModel(swift.toString())
            testHub.install(swift)
            models.changed()
            fake.reply { content("Done.") }
            val chat = engine.open(0)
            chat.send("go", emptyList())
            eventually("streaming") { chat.streaming.value != null }
            val model = (host.state.value as HostState.Ready).model

            // NPU, then straight back to Automatic, while the reply streams
            val vm = viewModel { DeviceFacts.Fallback }
            vm.setChip(ChipChoice.Npu)
            vm.setChip(ChipChoice.Automatic)
            eventually("saved") { settings.settings.first().chip == ChipChoice.Automatic }
            gate.complete(Unit)
            eventually("answered") { chat.messages.value.count { it.state == MessageState.Done } == 2 }
            vm.awaitLoadChange()
            // the model loaded for Automatic, what is chosen now, stays
            assertSame(model, (host.state.value as? HostState.Ready)?.model)
        }

    private suspend fun streamingChat(): ChatEngine.ChatHandle {
        settings.setLastModel(swift.toString())
        testHub.install(swift)
        models.changed()
        fake.reply { content("Done.") }
        val chat = engine.open(0)
        chat.send("go", emptyList())
        eventually("streaming") { chat.streaming.value != null }
        return chat
    }

    @Test fun choosingAContextAndBackDuringAReplyKeepsTheModel() =
        runBlocking {
            val chat = streamingChat()
            val model = (host.state.value as HostState.Ready).model
            val vm = viewModel { DeviceFacts.Fallback }
            vm.setContext(ContextChoice.UpTo32k)
            vm.setContext(ContextChoice.Automatic)
            eventually("saved") { settings.settings.first().context == ContextChoice.Automatic }
            gate.complete(Unit)
            eventually("answered") { chat.messages.value.count { it.state == MessageState.Done } == 2 }
            vm.awaitLoadChange()
            assertSame(model, (host.state.value as? HostState.Ready)?.model)
        }

    @Test fun aContextChangeAfterAChipChangeWaitsInItsPlaceAndFreesTheModelOnceTheReplyEnds() =
        runBlocking {
            val chat = streamingChat()
            val vm = viewModel { DeviceFacts.Fallback }
            // one wait at a time: the context change replaces the chip change's, and reads both choices when it ends
            vm.setChip(ChipChoice.Npu)
            vm.setContext(ContextChoice.UpTo8k)
            eventually("saved") { settings.settings.first().context == ContextChoice.UpTo8k }
            assertTrue(host.state.value is HostState.Ready)
            gate.complete(Unit)
            eventually("answered") { chat.messages.value.count { it.state == MessageState.Done } == 2 }
            vm.awaitLoadChange()
            assertEquals(HostState.Idle, host.state.value)
            // the chip saved just before is kept: only the wait was replaced
            assertEquals(ChipChoice.Npu, settings.settings.first().chip)
        }
}
