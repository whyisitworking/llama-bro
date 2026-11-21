package com.suhel.llamabro.demo.chat

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.suhel.llamabro.DeviceKind
import com.suhel.llamabro.DevicePolicy
import com.suhel.llamabro.Endpoint
import com.suhel.llamabro.LlamaBroException
import com.suhel.llamabro.Model
import com.suhel.llamabro.ModelSource
import com.suhel.llamabro.chat.ChatEvent
import com.suhel.llamabro.demo.data.ChatRepository
import com.suhel.llamabro.demo.data.ImageStore
import com.suhel.llamabro.demo.data.MessageKind
import com.suhel.llamabro.demo.data.MessageState
import com.suhel.llamabro.demo.data.SettingsRepository
import com.suhel.llamabro.demo.models.FakeScheduler
import com.suhel.llamabro.demo.models.Format
import com.suhel.llamabro.demo.models.ModelRepository
import com.suhel.llamabro.demo.models.TestHub
import com.suhel.llamabro.demo.models.installedModel
import com.suhel.llamabro.hub.HfModel
import com.suhel.llamabro.testing.FakeLlamaBro
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/** A model is never closed while a turn streams from it: each turn holds a lease, and a load of another model waits. */
class ModelLeaseTest {
    @get:Rule val folder = TemporaryFolder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val swift = HfModel.parse("unsloth/Qwen3.5-0.8B-GGUF:Q4_K_M")
    private val big = HfModel.parse("unsloth/Qwen3.5-4B-GGUF:Q4_K_M")

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
    private var streamer: Streamer = ::sdkStream
    private val engine by lazy {
        ChatEngine(chats, host, models, settings, System::currentTimeMillis, scope, streamer = streamer)
    }

    /** Both models are on the phone: the host loads the hub's installed copies, as the app's does. */
    @Before fun install() = testHub.install(swift, big)

    @After fun stop() {
        // turns first: a lease a test left held would keep the release waiting
        scope.cancel()
        runBlocking {
            scope.coroutineContext[Job]?.join()
            host.release()
        }
        testHub.close()
    }

    private suspend fun until(what: String, condition: suspend () -> Boolean) {
        withTimeoutOrNull(10_000) { while (!condition()) delay(10) }
            ?: throw AssertionError("timed out waiting for: $what")
    }

    private suspend fun isClosed(model: Model): Boolean =
        try {
            model.rawRequest(Endpoint.Control, "{}").toList()
            false
        } catch (_: LlamaBroException.ModelClosed) {
            true
        }

    private suspend fun newChat(): ChatEngine.ChatHandle {
        settings.setLastModel(swift.toString())
        testHub.install(swift, big)
        models.changed()
        return engine.open(0)
    }

    private fun replies(chat: ChatEngine.ChatHandle) = chat.messages.value.filter { it.kind == MessageKind.Assistant }

    private suspend fun answered(chat: ChatEngine.ChatHandle, count: Int) =
        until("answered $count") { replies(chat).count { it.state == MessageState.Done } == count }

    /** A streamer whose first turn writes a word, then waits for [gate] before the fake answers. */
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

    @Test fun anotherChatOnAnotherModelWaitsForTheReplyAndBothAreAnswered() =
        runBlocking {
            val gate = CompletableDeferred<Unit>()
            firstTurnWaitsFor(gate)
            repeat(2) { fake.reply { content("Done.") } }
            val a = newChat()
            a.send("go", emptyList())
            until("A streams") { a.streaming.value != null }
            val first = (host.state.value as HostState.Ready).model

            val b = engine.open(0)
            b.switchModel(big)
            until("B is on the 4B") { b.name.value == "Qwen3.5 4B" }
            b.send("hi", emptyList())
            // B waits behind A's reply with the wake line; A's model stays open under it
            until("B wakes") { (host.state.value as? HostState.Waking)?.key == big }
            until("B shows the wake line") { b.model.value is ChatModelState.Waking }
            delay(200)
            assertFalse(isClosed(first))
            assertTrue(replies(b).isEmpty())
            assertEquals("slow ", a.streaming.value?.text)

            gate.complete(Unit)
            answered(a, 1)
            assertEquals("slow Done.", replies(a).single().text)
            answered(b, 1)
            val ready = host.state.value as HostState.Ready
            assertEquals(big, ready.key)
            assertTrue(isClosed(first))
            assertFalse(isClosed(ready.model))
        }

    @Test fun aLoadOfAnotherKeyClosesTheOldModelOnlyOnceItsLeaseIsReleased() =
        runBlocking {
            val lease = host.hold(swift, LoadConfig())
            val next = async { host.hold(big, LoadConfig()) }
            delay(200)
            assertFalse(next.isCompleted)
            assertFalse(isClosed(lease.model))
            // the wake line, and the model still in memory until its lease is released
            val outgoing = HostState.Ready(swift, lease.model, LoadConfig())
            assertEquals(HostState.Waking(big, outgoing = outgoing), host.state.value)
            val oldInMemory = host.state.value.inMemory()
            assertEquals("Qwen3.5 0.8B", oldInMemory?.name)
            assertEquals(outgoing, host.state.value.loaded())

            lease.release()
            lease.release() // a second release does nothing
            val bigLease = next.await()
            assertTrue(isClosed(lease.model))
            assertEquals(HostState.Ready(big, bigLease.model, LoadConfig()), host.state.value)
            val newInMemory = host.state.value.inMemory()
            assertEquals("Qwen3.5 4B", newInMemory?.name)
            bigLease.release()
        }

    /** The model in memory says the one size, its file and its image reader, when it is known. */
    @Test fun theModelInMemorySaysTheOneSize() =
        runBlocking {
            val lease = host.hold(swift, LoadConfig())
            val weights = lease.model.info.sizeBytes
            val state = host.state.value
            assertEquals(Format.sizeInGb(weights), state.inMemory()?.detail?.substringBefore(" · "))
            assertEquals("1.2 GB", state.inMemory(totalBytes = 1_200_000_000)?.detail?.substringBefore(" · "))
            lease.release()
        }

    @Test fun aWaitThatIsCancelledHoldsNothing() =
        runBlocking {
            val lease = host.hold(swift, LoadConfig())
            val waiting = async { host.hold(big, LoadConfig()) }
            delay(100)
            waiting.cancelAndJoin()
            lease.release()
            // the load of the 4B goes on and lands; the cancelled wait took no lease on it, so it can be released
            until("the 4B is loaded") { (host.state.value as? HostState.Ready)?.key == big }
            val loaded = (host.state.value as HostState.Ready).model
            assertTrue(host.release())
            assertTrue(isClosed(loaded))
            assertEquals(HostState.Idle, host.state.value)
        }

    @Test fun aLoadDuringAReleaseThatWaitsForALeaseWaitsForTheOldModelToClose() =
        runBlocking {
            val lease = host.hold(swift, LoadConfig())
            // deleting the model in use while its reply streams: the release waits for the lease
            val release = async { host.release() }
            until("released") { host.state.value == HostState.Idle }
            val next = async { host.hold(big, LoadConfig()) }
            delay(200)
            // the 4B doesn't load beside the 0.8B
            assertFalse(next.isCompleted)
            assertFalse(release.isCompleted)
            assertFalse(isClosed(lease.model))
            val outgoing = HostState.Ready(swift, lease.model, LoadConfig())
            assertEquals(HostState.Waking(big, outgoing = outgoing), host.state.value)

            lease.release()
            assertTrue(release.await())
            val bigLease = next.await()
            assertTrue(isClosed(lease.model))
            assertFalse(isClosed(bigLease.model))
            assertEquals(HostState.Ready(big, bigLease.model, LoadConfig()), host.state.value)
            bigLease.release()
        }

    @Test fun aLoadReplacedWhileItWaitsAndHeldByNoTurnIsNeverLoaded() =
        runBlocking {
            val sourced = java.util.Collections.synchronizedList(mutableListOf<HfModel>())
            val host = ModelHost(fake.llamaBro, { model -> hub.installedModel(model).also { sourced += model } }, scope)
            val lease = host.hold(swift, LoadConfig())
            val waiting = async { host.hold(big, LoadConfig()) }
            until("the 4B waits") { (host.state.value as? HostState.Waking)?.key == big }
            waiting.cancelAndJoin()
            // the 0.8B again, under another config: it replaces the 4B's load before that one started
            val again = async { host.hold(swift, LoadConfig(DevicePolicy.Prefer(DeviceKind.Cpu))) }
            lease.release()
            val swiftAgain = again.await()
            assertEquals(listOf(swift, swift), sourced.toList())
            assertTrue(isClosed(lease.model))
            assertEquals(HostState.Ready(swift, swiftAgain.model, swiftAgain.config), host.state.value)
            swiftAgain.release()
            assertTrue(host.release())
        }

    @Test fun aReleaseWithNothingInUseStillWaitsForAModelClosing() =
        runBlocking {
            val lease = host.hold(swift, LoadConfig())
            val first = async { host.release() }
            until("released") { host.state.value == HostState.Idle }
            // nothing is current now, but the 0.8B is still open under its reply
            val second = async { host.release() }
            delay(200)
            assertFalse(second.isCompleted)
            lease.release()
            assertTrue(second.await())
            assertTrue(first.await())
            assertTrue(isClosed(lease.model))
        }

    @Test fun theOutgoingModelLeavesTheWakeLineWhenItCloses() =
        runBlocking {
            // the 4B's load waits at its source, so the wake line stays up after the 0.8B closes
            val gate = CompletableDeferred<Unit>()
            val gated: suspend (HfModel) -> ModelSource = { model ->
                if (model == big) gate.await()
                hub.installedModel(model)
            }
            val host = ModelHost(fake.llamaBro, gated, scope)
            val lease = host.hold(swift, LoadConfig())
            val next = async { host.hold(big, LoadConfig()) }
            until("the 4B waits") { (host.state.value as? HostState.Waking)?.outgoing != null }
            lease.release()
            until("the 0.8B closed") { isClosed(lease.model) }
            until("nothing in memory") { host.state.value == HostState.Waking(big) }
            assertNull(host.state.value.inMemory())
            gate.complete(Unit)
            val bigLease = next.await()
            assertEquals(big, (host.state.value as HostState.Ready).key)
            bigLease.release()
            assertTrue(host.release())
        }

    @Test fun aReleaseWaitsForTheLeaseBeforeClosing() =
        runBlocking {
            val lease = host.hold(swift, LoadConfig())
            val release = async { host.release() }
            delay(200)
            assertFalse(release.isCompleted)
            assertFalse(isClosed(lease.model))
            lease.release()
            assertTrue(release.await())
            assertTrue(isClosed(lease.model))
        }
}
