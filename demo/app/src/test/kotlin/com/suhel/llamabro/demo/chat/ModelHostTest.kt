package com.suhel.llamabro.demo.chat

import com.suhel.llamabro.Endpoint
import com.suhel.llamabro.LlamaBroException
import com.suhel.llamabro.Model
import com.suhel.llamabro.ModelSource
import com.suhel.llamabro.demo.data.settings.LoadConfig
import com.suhel.llamabro.hub.HfModel
import com.suhel.llamabro.testing.FakeLlamaBro
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

class ModelHostTest {
    private val a = HfModel.parse("unsloth/Qwen3.5-0.8B-GGUF:Q4_K_M")
    private val b = HfModel.parse("unsloth/Qwen3.5-4B-GGUF:Q4_K_M")

    /** Where a load finds its files; the fake engine reads none, so any path loads. */
    private var onSource: suspend (HfModel) -> ModelSource = { ModelSource.file("/fake/${it.repo}.gguf") }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val host = ModelHost(FakeLlamaBro().llamaBro, { onSource(it) }, scope)

    @After fun stop() {
        runBlocking { host.release() }
        scope.cancel()
    }

    private suspend fun isClosed(model: Model): Boolean =
        try {
            model.rawRequest(Endpoint.Control, "{}").toList()
            false
        } catch (_: LlamaBroException.ModelClosed) {
            true
        }

    @Test fun secondUseOfSameKeySharesOneLoad() =
        runBlocking {
            val sources = AtomicInteger()
            val gate = CompletableDeferred<Unit>()
            onSource = {
                sources.incrementAndGet()
                gate.await()
                ModelSource.file("/fake/a.gguf")
            }
            val first = async { host.use(a, LoadConfig()) }
            val second = async { host.use(a, LoadConfig()) }
            gate.complete(Unit)
            val (m1, m2) = awaitAll(first, second)
            assertSame(m1, m2)
            assertEquals(1, sources.get())
            assertEquals(HostState.Ready(a, m1, LoadConfig()), host.state.value)
            assertSame(m1, host.use(a, LoadConfig()))
            assertEquals(1, sources.get())
        }

    @Test fun switchingClosesThePreviousModelFirst() =
        runBlocking {
            val first = host.use(a, LoadConfig())
            var firstWasClosedWhenBLoaded: Boolean? = null
            onSource = {
                if (it == b) firstWasClosedWhenBLoaded = isClosed(first)
                ModelSource.file("/fake/b.gguf")
            }
            val second = host.use(b, LoadConfig())
            assertEquals(
                "the first model closes before the second one's files are even looked up",
                true,
                firstWasClosedWhenBLoaded,
            )
            assertNotEquals(first, second)
            assertEquals(HostState.Ready(b, second, LoadConfig()), host.state.value)
            assertFalse(isClosed(second))
        }

    @Test fun loadFailureIsFailedStateNotACrash() =
        runBlocking {
            onSource = { throw IOException("the file is gone") }
            assertThrows(IOException::class.java) { runBlocking { host.use(a, LoadConfig()) } }
            val state = host.state.value as HostState.Failed
            assertEquals(a, state.key)
            assertEquals("the file is gone", state.error.message)
            // the host still works afterwards: a later use loads
            onSource = { ModelSource.file("/fake/a.gguf") }
            val model = withTimeout(10_000) { host.use(a, LoadConfig()) }
            assertEquals(HostState.Ready(a, model, LoadConfig()), host.state.value)
        }

    @Test fun releaseReturnsToIdle() =
        runBlocking {
            val model = host.use(a, LoadConfig())
            host.release()
            assertEquals(HostState.Idle, host.state.value)
            assertTrue(isClosed(model))
            host.release() // nothing loaded: does nothing
            assertEquals(HostState.Idle, host.state.value)
        }

    @Test fun modelHostLoadsWithSdkDefaults() =
        runBlocking {
            val model = host.use(a, LoadConfig())
            // the default config asks for ContextSize.Auto and sets no Speculative: the report says each is the SDK's
            // own choice
            assertNotEquals("set by the app", model.loadReport.contextReason)
            assertFalse(
                model.loadReport.speculativeReason,
                model.loadReport.speculativeReason.contains("set by the app"),
            )
        }

    @Test fun stateIsWakingWhileTheModelLoads() =
        runBlocking {
            val gate = CompletableDeferred<Unit>()
            onSource = {
                gate.await()
                ModelSource.file("/fake/a.gguf")
            }
            val pending = async { host.use(a, LoadConfig()) }
            withTimeout(10_000) { while ((host.state.value as? HostState.Waking)?.key != a) kotlinx.coroutines.yield() }
            gate.complete(Unit)
            pending.await()
            assertTrue(host.state.value is HostState.Ready)
        }
}
