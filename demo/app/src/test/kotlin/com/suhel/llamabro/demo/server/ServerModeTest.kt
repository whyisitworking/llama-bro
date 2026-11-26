package com.suhel.llamabro.demo.server

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import com.suhel.llamabro.LlamaBroException
import com.suhel.llamabro.ModelSource
import com.suhel.llamabro.demo.chat.ChatEngine
import com.suhel.llamabro.demo.chat.FakeChatDao
import com.suhel.llamabro.demo.chat.HostState
import com.suhel.llamabro.demo.chat.Limit
import com.suhel.llamabro.demo.chat.ModelHost
import com.suhel.llamabro.demo.data.ChatRepository
import com.suhel.llamabro.demo.data.ImageStore
import com.suhel.llamabro.demo.data.SettingsRepository
import com.suhel.llamabro.demo.data.settings.LoadConfig
import com.suhel.llamabro.demo.eventually
import com.suhel.llamabro.demo.letAWrongActionLand
import com.suhel.llamabro.demo.models.FakeScheduler
import com.suhel.llamabro.demo.models.ModelRepository
import com.suhel.llamabro.demo.models.Names
import com.suhel.llamabro.demo.models.TestHub
import com.suhel.llamabro.hub.HfModel
import com.suhel.llamabro.server.ServerEvent
import com.suhel.llamabro.testing.FakeLlamaBro
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URL

/** Server mode over the fake engine and a real `LlamaBroServer` on a free port: never 8080. */
class ServerModeTest {
    @get:Rule val folder = TemporaryFolder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val a = HfModel.parse("unsloth/Qwen3.5-0.8B-GGUF:Q4_K_M")
    private val b = HfModel.parse("unsloth/Qwen3.5-4B-GGUF:Q4_K_M")
    private val chat = "/v1/chat/completions"

    private val fake = FakeLlamaBro()

    /** Where a load finds its files; the fake engine reads none, so any path loads. */
    private var onSource: suspend (HfModel) -> ModelSource = { ModelSource.file("/fake/${it.repo}.gguf") }
    private val host = ModelHost(fake.llamaBro, { onSource(it) }, scope)
    private val store by lazy {
        PreferenceDataStoreFactory.create(scope = scope) { File(folder.root, "settings.preferences_pb") }
    }
    private val settings by lazy { SettingsRepository(store) }

    /** Set, the key's first write waits for it: the start is then just past the model's hold. */
    private var keyGate: CompletableDeferred<Unit>? = null
    private val keyWriting = CompletableDeferred<Unit>()
    private val keys by lazy { ServerKey(ServerSettings(GatedStore(store))) }

    private inner class GatedStore(private val inner: DataStore<Preferences>) : DataStore<Preferences> {
        override val data: Flow<Preferences> = inner.data

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
            keyWriting.complete(Unit)
            keyGate?.await()
            return inner.updateData(transform)
        }
    }

    private var port = 0
    private val server by lazy { ServerMode(host, settings, keys, scope, port = port) }

    @After fun stop() {
        runBlocking {
            server.stop()
            host.release()
        }
        scope.cancel()
    }

    private suspend fun serving(): ServingState.Serving =
        withTimeout(10_000) { server.state.first { it !is ServingState.Starting } } as? ServingState.Serving
            ?: throw AssertionError("not serving: ${server.state.value}")

    @Test fun startHoldsTheModelAndServes() =
        runBlocking {
            fake.raw(200, """{"choices":[]}""")
            server.start(a, 2)
            val serving = serving()
            assertEquals(a, serving.model)
            assertEquals(2, serving.slots)
            assertTrue(serving.port > 0)
            assertTrue(server.isServing(a))
            assertFalse(server.isServing(b))

            val listed = call(serving.port, "/v1/models")
            assertEquals(200, listed.status)
            assertTrue(listed.body, listed.body.contains("\"id\":\"$a\""))
            // the key is always set
            assertEquals(401, call(serving.port, chat, body = "{}").status)
            val key = keys.current()
            assertEquals(200, call(serving.port, chat, key = key, body = "{}").status)
            // the request had its own lease and the model is the one the host holds
            assertEquals(1, fake.openModels)
            withContext(Dispatchers.Default) {
                eventually("the refused request is logged") {
                    server.log.value.any { it.status == 401 && it.path == chat && it.method == null }
                }
                eventually("the answered request is logged") {
                    server.log.value.any { it.status == 200 && it.path == chat && it.method == "POST" }
                }
            }
        }

    @Test fun anyModelNameIsServed() =
        runBlocking {
            server.start(a, 1)
            val port = serving().port
            val key = keys.current()
            // Claude Code names its own model; as upstream's llama-server with one model loaded, the phone serves its
            // one model whatever the name (D164)
            fake.raw(200, "{}")
            assertEquals(200, call(port, chat, key = key, body = """{"model":"claude-sonnet-4-5"}""").status)
            fake.raw(200, "{}")
            assertEquals(200, call(port, "/v1/messages", key = key, body = """{"model":"claude-haiku-4-5"}""").status)
            // the listing still names the served model alone
            val listed = call(port, "/v1/models").body
            assertTrue(listed, listed.contains("\"id\":\"$a\"") && !listed.contains("claude"))
        }

    @Test fun requestsUseTheServingLeaseAndNeverHold() =
        runBlocking {
            server.start(a, 1)
            val port = serving().port
            // a close of the served model (a delete) lets go of the host's current load and waits for the serving
            // lease; a request that held the host itself would start a new load behind it and hang
            val closing = scope.launch { host.close(a) }
            withContext(Dispatchers.Default) {
                eventually("the host let go of the served model") { host.state.value == HostState.Idle }
            }
            fake.raw(200, "{}")
            assertEquals(200, withTimeout(10_000) { call(port, chat, key = keys.current(), body = "{}") }.status)
            assertEquals("no second load", 1, fake.openModels)
            assertFalse(closing.isCompleted)
            server.stop()
            withTimeout(10_000) { closing.join() }
            assertEquals(0, fake.openModels)
        }

    @Test fun stopReleasesTheLease() =
        runBlocking {
            server.start(a, 1)
            val port = serving().port
            val served = (host.state.value as HostState.Ready).model
            // a load of another model waits for the serving lease
            val other = scope.async { host.use(b, LoadConfig()) }
            withContext(Dispatchers.Default) {
                eventually("the other load waits") { (host.state.value as? HostState.Waking)?.key == b }
                letAWrongActionLand()
            }
            assertFalse("the other model loaded under the served one", other.isCompleted)
            server.stop()
            assertEquals(ServingState.Off, server.state.value)
            assertFalse(server.isServing(a))
            assertNotEquals(served, withTimeout(10_000) { other.await() })
            try {
                call(port, "/health")
                fail("the port still answers")
            } catch (_: ConnectException) {
                // unbound
            }
        }

    @Test fun startingShowsTheLoadThenServes() =
        runBlocking {
            val gate = CompletableDeferred<Unit>()
            onSource = {
                gate.await()
                ModelSource.file("/fake/a.gguf")
            }
            val starting = scope.launch { server.start(a, 1) }
            withTimeout(10_000) { server.state.first { it is ServingState.Starting } }
            assertTrue(server.isServing(a))
            gate.complete(Unit)
            starting.join()
            assertEquals(a, serving().model)
        }

    @Test fun stopWhileStartingCancelsTheLoad() =
        runBlocking {
            val gate = CompletableDeferred<Unit>()
            onSource = {
                gate.await()
                ModelSource.file("/fake/a.gguf")
            }
            val starting = scope.launch { server.start(a, 1) }
            withTimeout(10_000) { server.state.first { it is ServingState.Starting } }
            withTimeout(10_000) { server.stop() }
            starting.join()
            assertEquals(ServingState.Off, server.state.value)
            assertFalse(server.isServing(a))
            // the host's load goes on to its end, and nothing holds the model it makes
            gate.complete(Unit)
            assertTrue(withTimeout(10_000) { host.release() })
        }

    @Test fun stopDuringStartAfterHoldReleasesTheLease() =
        runBlocking {
            keyGate = CompletableDeferred()
            val starting = scope.launch { server.start(a, 1) }
            // the hold has returned its lease: the start now reads the key, which waits
            withTimeout(10_000) { keyWriting.await() }
            assertTrue(host.state.value is HostState.Ready)
            withTimeout(10_000) { server.stop() }
            starting.join()
            assertEquals(ServingState.Off, server.state.value)
            // the lease was released, not dropped: the host frees the model at once
            assertTrue(withTimeout(10_000) { host.release() })
            assertEquals(0, fake.openModels)
        }

    @Test fun turnOnAnotherModelWhileServingFailsFast() =
        runBlocking {
            val hub = TestHub()
            try {
                val engine = engine(hub)
                server.start(a, 1)
                serving()
                val served = (host.state.value as HostState.Ready).model
                val limit = MutableStateFlow<Limit?>(null)
                assertNull(withTimeout(10_000) { engine.holdModel(b, "Big", slots = 1, limit) })
                val line = checkNotNull(limit.value).line
                assertTrue(line, line.contains(Names.of(a)) && line.contains("Big"))
                // no load started: the served model is still the host's
                assertSame(served, (host.state.value as HostState.Ready).model)
                assertEquals(1, fake.openModels)
            } finally {
                hub.close()
            }
        }

    @Test fun serverRequestsUnaffectedByARefusedTurn() =
        runBlocking {
            val hub = TestHub()
            try {
                val engine = engine(hub)
                server.start(a, 1)
                val port = serving().port
                assertNull(withTimeout(10_000) { engine.holdModel(b, "Big", slots = 1, MutableStateFlow(null)) })
                fake.raw(200, "{}")
                val reply = withTimeout(10_000) { call(port, chat, key = keys.current(), body = "{}") }
                assertEquals(200, reply.status)
            } finally {
                hub.close()
            }
        }

    private fun engine(hub: TestHub): ChatEngine {
        val chats = ChatRepository(FakeChatDao(), ImageStore(File(folder.root, "images")), { 0L })
        val models = ModelRepository(hub.hub, settings, FakeScheduler(), scope, host)
        return ChatEngine(chats, host, models, settings, { 0L }, scope, served = { server.served.value })
    }

    @Test fun portInUseFallsBackToAFreeOne() =
        runBlocking {
            ServerSocket(0).use { taken ->
                port = taken.localPort
                server.start(a, 1)
                val serving = serving()
                assertNotEquals(taken.localPort, serving.port)
                assertEquals(200, call(serving.port, "/health").status)
            }
        }

    @Test fun keyIsStableAcrossRestarts() =
        runBlocking {
            server.start(a, 1)
            val key = keys.current()
            fake.raw(200, "{}")
            assertEquals(200, call(serving().port, chat, key = key, body = "{}").status)
            server.stop()
            server.start(a, 1)
            assertEquals(key, keys.current())
            fake.raw(200, "{}")
            assertEquals(200, call(serving().port, chat, key = key, body = "{}").status)
        }

    @Test fun renewChangesTheKey() =
        runBlocking {
            server.start(a, 1)
            val old = keys.current()
            serving()
            val renewed = server.renewKey()
            assertNotEquals(old, renewed)
            assertEquals(renewed, keys.current())
            // the running server restarted with the new key, on the same model
            val port = serving().port
            assertEquals(401, call(port, chat, key = old, body = "{}").status)
            fake.raw(200, "{}")
            assertEquals(200, call(port, chat, key = renewed, body = "{}").status)
            assertEquals(1, fake.openModels)
        }

    @Test fun chatOnTheServedModelShares() =
        runBlocking {
            val hub = TestHub()
            try {
                val engine = engine(hub)
                server.start(a, 1)
                serving()
                val served = (host.state.value as HostState.Ready).model
                // voice mode asks for two slots; the served model has one, and it is shared rather than replaced
                val hold =
                    withTimeout(10_000) { engine.holdModel(a, "A", slots = 2, MutableStateFlow<Limit?>(null)) }
                assertSame(served, hold?.model)
                hold?.release()
                assertEquals(1, fake.openModels)
                // leaving the app frees no model the server holds
                assertFalse(engine.releaseModelIfIdle())
                assertSame(served, (host.state.value as HostState.Ready).model)
            } finally {
                hub.close()
            }
        }

    @Test fun switchingTheChatModelWhileServingAsksFirst() =
        runBlocking {
            assertFalse(server.blocksSwitch(b))
            server.start(a, 1)
            serving()
            assertTrue(server.blocksSwitch(b))
            assertFalse("the served model is shared, not replaced", server.blocksSwitch(a))
            server.stop()
            assertFalse(server.blocksSwitch(b))
        }

    @Test fun failedLoadIsFailed() =
        runBlocking {
            // every try fails: the SDK tries again on its own before it gives up
            fake.failLoad(LlamaBroException.ModelLoadFailed.Reason.Other, "the file is corrupt", times = 100)
            server.start(a, 1)
            val failed = server.state.value
            assertTrue("state was $failed", failed is ServingState.Failed)
            assertTrue((failed as ServingState.Failed).reason.isNotEmpty())
            assertFalse(server.isServing(a))
            assertNull(server.served.value)
            // no lease left behind: the host frees at once
            assertTrue(withTimeout(10_000) { host.release() })
        }

    @Test fun logKeepsTwoHundred() {
        var now = 0L
        val log = ServerLog { now }
        repeat(205) {
            now = it.toLong()
            log.record(ServerEvent.Rejected("10.0.0.$it", "/v1/messages", 401, "invalid or missing API key"))
        }
        assertEquals(200, log.lines.value.size)
        assertEquals(
            "10.0.0.204",
            log.lines.value
                .first()
                .client,
        )
        assertEquals(
            "10.0.0.5",
            log.lines.value
                .last()
                .client,
        )

        now = 1_000
        log.record(ServerEvent.RequestStarted(7, "10.0.0.9", "POST", "/v1/chat/completions", "curl"))
        now = 2_000
        log.record(ServerEvent.RequestEnded(7, 200, 1_400, 12, 34))
        assertEquals(
            LogLine(1_000, "10.0.0.9", "POST", "/v1/chat/completions", 200, 1_400, 12, 34),
            log.lines.value.first(),
        )
        assertEquals(200, log.lines.value.size)
        // an end whose start was missed (the events dropped it) is no line
        log.record(ServerEvent.RequestEnded(99, 200, 1, null, null))
        assertEquals(
            LogLine(1_000, "10.0.0.9", "POST", "/v1/chat/completions", 200, 1_400, 12, 34),
            log.lines.value.first(),
        )
    }
}

private class Reply(val status: Int, val body: String)

private const val READ_TIMEOUT_MILLIS = 10_000

/** A plain HTTP call, as a client on the Wi-Fi makes one; a [body] makes it a POST. */
private suspend fun call(port: Int, path: String, key: String? = null, body: String? = null): Reply =
    withContext(Dispatchers.IO) {
        val connection = URL("http://127.0.0.1:$port$path").openConnection() as HttpURLConnection
        // a bound, so a request that hangs fails the test rather than holding it: a blocking read ignores a cancel
        connection.readTimeout = READ_TIMEOUT_MILLIS
        try {
            key?.let { connection.setRequestProperty("Authorization", "Bearer $it") }
            if (body != null) {
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(body.toByteArray()) }
            }
            val status = connection.responseCode
            val stream = if (status >= 400) connection.errorStream else connection.inputStream
            Reply(status, stream?.bufferedReader()?.use { it.readText() }.orEmpty())
        } finally {
            connection.disconnect()
        }
    }
