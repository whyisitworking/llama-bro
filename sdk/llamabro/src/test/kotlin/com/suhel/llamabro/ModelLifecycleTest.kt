package com.suhel.llamabro

import com.suhel.llamabro.spi.Engine
import com.suhel.llamabro.spi.EngineModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.LockSupport
import kotlin.concurrent.thread
import kotlin.coroutines.CoroutineContext

/** Races between closing or cancelling and native calls. */
class ModelLifecycleTest {
    /**
     * Thread A is inside engine.request() (past the closed check, before the native lock) while thread B closes the
     * model. The native model must not be freed until A's call returns, or lb_model_request reads freed memory.
     */
    @Test
    fun closeWaitsForARequestEnteringNative() {
        val engine = FakeEngine()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        var closedWhileInside = false
        engine.model.onRequest = { r ->
            entered.countDown()
            release.await(5, TimeUnit.SECONDS)
            closedWhileInside = engine.model.closed
            r.chunk("{}")
            r.done()
        }
        val model = runBlocking { testLlamaBro(engine).loadModel(ModelSource.file("/m.gguf")) }
        val a = thread { runCatching { runBlocking { model.rawRequest(Endpoint.Props, "").collect() } } }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        val b = thread { model.close() }
        // the signal that close got as far as it may: its native close is queued behind the request on the model's
        // gate (or, with the bug, the native close already ran)
        awaitTrue("the native close waiting for the request") { model.closeQueued || engine.model.closed }
        assertFalse("native model freed while a request was entering it", engine.model.closed)
        release.countDown()
        a.join(5_000)
        b.join(5_000)
        assertFalse(closedWhileInside)
        // close() returned at once; the native close runs once the request is out, in the background (D136)
        runBlocking { withTimeout(5_000) { model.closeAndJoin() } }
        assertTrue(engine.model.closed)
    }

    /** A model whose info can't be read after the native load is freed, not leaked, and the load fails typed. */
    @Test
    fun unreadableInfoFreesTheLoadedModel() {
        val engine = FakeEngine()
        engine.model.infoJson = "{}" // no n_ctx
        val failure =
            runCatching { runBlocking { testLlamaBro(engine).loadModel(ModelSource.file("/m.gguf")) } }
                .exceptionOrNull()
        assertTrue("expected ModelLoadFailed, got $failure", failure is LlamaBroException.ModelLoadFailed)
        assertTrue("the loaded native model leaked", engine.model.closed)
    }

    /** A loadModel cancelled during the (uninterruptible) native load must not leak the loaded model. */
    @Test
    fun cancelledLoadClosesTheModel() =
        runBlocking {
            val loading = CountDownLatch(1)
            val release = CountDownLatch(1)
            val engine =
                object : Engine by FakeEngine() {
                    val model = FakeModel()

                    override fun load(configJson: String, onProgress: (Float) -> Boolean): EngineModel {
                        loading.countDown()
                        release.await(5, TimeUnit.SECONDS)
                        return model
                    }
                }
            val llama =
                LlamaBro(
                    com.suhel.llamabro.internal
                        .LlamaBroRuntime(engine, tempDir(), "/b", LlamaBroConfig.Default),
                )
            var outcome: Throwable? = null
            val job =
                launch(Dispatchers.Default) {
                    outcome = runCatching { llama.loadModel(ModelSource.file("/m.gguf")) }.exceptionOrNull()
                }
            assertTrue(loading.await(5, TimeUnit.SECONDS))
            job.cancel()
            release.countDown()
            job.join()
            assertTrue("$outcome", outcome is CancellationException)
            assertTrue("the loaded model leaked", engine.model.closed)
        }

    // --- Close off the caller's thread (F40) ---------------------------------------------------------

    /** A model whose native close blocks until [release] opens, as lb_model_free does while it joins its worker. */
    private class SlowClose {
        val engine = FakeEngine()
        val release = CountDownLatch(1)
        val closing = CountDownLatch(1)
        val model: Model

        init {
            engine.model.onClose = {
                closing.countDown()
                release.await(10, TimeUnit.SECONDS)
            }
            model = runBlocking { testLlamaBro(engine).loadModel(ModelSource.file("/m.gguf")) }
        }
    }

    @Test
    fun closeReturnsBeforeEngineFinishes() {
        val s = SlowClose()
        // the native close can't finish until the gate opens, so a close() that returns at all didn't wait for it
        val caller = thread { s.model.close() }
        caller.join(5_000)
        assertFalse("close() waited for the native close", caller.isAlive)
        assertTrue("the native close never started", s.closing.await(5, TimeUnit.SECONDS))
        assertFalse(s.engine.model.closed)
        s.release.countDown()
    }

    @Test
    fun closeAndJoinWaits() =
        runBlocking {
            val s = SlowClose()
            val join = async(Dispatchers.Default) { s.model.closeAndJoin() }
            // what the native close had done at the moment closeAndJoin returned, whenever that was
            val closedAtReturn = CompletableDeferred<Boolean>()
            join.invokeOnCompletion { closedAtReturn.complete(s.engine.model.closed) }
            assertTrue(s.closing.await(5, TimeUnit.SECONDS))
            s.release.countDown()
            withTimeout(5_000) { join.await() }
            assertTrue("closeAndJoin returned before the native close", closedAtReturn.await())
            assertTrue(s.engine.model.closed)
            s.model.closeAndJoin() // a second call returns at once
        }

    @Test
    fun requestsAfterCloseFail() =
        runBlocking {
            val s = SlowClose()
            val started = CountDownLatch(1)
            s.engine.model.onRequest = { started.countDown() } // never finishes on its own
            val running =
                async(
                    Dispatchers.Default,
                ) { runCatching { s.model.rawRequest(Endpoint.Props, "").collect() }.exceptionOrNull() }
            assertTrue(started.await(5, TimeUnit.SECONDS))
            s.model.close()
            // both fail while the native close is still running
            val err = withTimeout(5_000) { running.await() }
            assertTrue("$err", err is LlamaBroException.ModelClosed)
            val after = runCatching { s.model.rawRequest(Endpoint.Props, "").collect() }.exceptionOrNull()
            assertTrue("$after", after is LlamaBroException.ModelClosed)
            assertFalse(s.engine.model.closed)
            s.release.countDown()
        }

    /** Closing one model and loading the next never holds both: the load waits for the native close. */
    @Test
    fun loadWaitsForPendingClose() =
        runBlocking {
            val s = SlowClose()
            val made = Collections.synchronizedList(mutableListOf<FakeModel>())
            s.engine.makeModel = { FakeModel().also { it.onClose = s.engine.model.onClose }.also { made += it } }
            val io = CountingDispatcher()
            val llama =
                LlamaBro(
                    com.suhel.llamabro.internal
                        .LlamaBroRuntime(s.engine, tempDir(), "/b", LlamaBroConfig.Default, io),
                )
            val first = llama.loadModel(ModelSource.file("/a.gguf"))
            val firstEngine = s.engine.loadConfigs.size
            // whether the first model's native close had finished when the second native load began
            val firstFreedAtLoad = CompletableDeferred<Boolean>()
            s.engine.loadError = {
                firstFreedAtLoad.complete(made.first().closed)
                null
            }
            first.close()
            assertTrue("the native close never started", s.closing.await(5, TimeUnit.SECONDS))
            // started undispatched, the load runs on this thread until it first suspends: parked behind the close it
            // asks nothing of the IO dispatcher, while a load that didn't wait would already have gone there to load
            val dispatchesBefore = io.dispatches.get()
            val second =
                async(Dispatchers.Default, CoroutineStart.UNDISPATCHED) { llama.loadModel(ModelSource.file("/b.gguf")) }
            assertEquals(
                "the second load went on before the first model was freed",
                dispatchesBefore,
                io.dispatches.get(),
            )
            assertEquals(firstEngine, s.engine.loadConfigs.size)
            s.release.countDown()
            withTimeout(5_000) { second.await() }
            assertTrue("the second load started before the first model was freed", firstFreedAtLoad.await())
            assertEquals(firstEngine + 1, s.engine.loadConfigs.size)
        }

    @Test
    fun progressReported() =
        runBlocking {
            val engine = FakeEngine()
            engine.progress = listOf(0f, 0.5f, 1f)
            val seen = mutableListOf<Float>()
            val threads = mutableSetOf<Thread>()
            testLlamaBro(engine).loadModel(ModelSource.file("/m.gguf")) {
                onProgress {
                    seen += (it as LoadProgress.Loading).fraction
                    threads += Thread.currentThread()
                }
            }
            // conflated: values may be skipped, never reordered, and the last one always arrives
            assertEquals(1f, seen.last())
            assertEquals(seen.sorted(), seen)
            assertEquals("delivered on the caller's context", setOf(Thread.currentThread()), threads)
        }

    /** The runtime's IO dispatcher, counting the work handed to it. */
    private class CountingDispatcher : CoroutineDispatcher() {
        val dispatches = AtomicInteger()

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            dispatches.incrementAndGet()
            Dispatchers.IO.dispatch(context, block)
        }
    }

    /** Polls [condition] every millisecond; fails naming [what] after 5 s, a hang bound, not a latency check. */
    private fun awaitTrue(what: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!condition()) {
            if (System.nanoTime() > deadline) throw AssertionError("timed out waiting for: $what")
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1))
        }
    }
}
