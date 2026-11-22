package com.suhel.llamabro

import com.suhel.llamabro.spi.Engine
import com.suhel.llamabro.spi.EngineModel
import kotlinx.coroutines.CancellationException
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

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
        Thread.sleep(200)
        assertFalse("native model freed while a request was entering it", engine.model.closed)
        release.countDown()
        a.join(5_000)
        b.join(5_000)
        assertFalse(closedWhileInside)
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
            assertTrue(s.closing.await(5, TimeUnit.SECONDS))
            Thread.sleep(100)
            assertFalse("closeAndJoin returned before the native close", join.isCompleted)
            s.release.countDown()
            withTimeout(5_000) { join.await() }
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
            s.engine.makeModel = { FakeModel().also { it.onClose = s.engine.model.onClose } }
            val llama =
                LlamaBro(
                    com.suhel.llamabro.internal
                        .LlamaBroRuntime(s.engine, tempDir(), "/b", LlamaBroConfig.Default),
                )
            val first = llama.loadModel(ModelSource.file("/a.gguf"))
            val firstEngine = s.engine.loadConfigs.size
            first.close()
            val second = async(Dispatchers.Default) { llama.loadModel(ModelSource.file("/b.gguf")) }
            Thread.sleep(150)
            assertEquals(
                "the second load started before the first model was freed",
                firstEngine,
                s.engine.loadConfigs.size,
            )
            s.release.countDown()
            withTimeout(5_000) { second.await() }
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
}
