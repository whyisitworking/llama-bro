package com.suhel.llamabro

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** Races between closing or cancelling and native calls (final review of P1, Important #1 and #2). */
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

    /** A loadModel cancelled during the (uninterruptible) native load must not leak the loaded model. */
    @Test
    fun cancelledLoadClosesTheModel() = runBlocking {
        val loading = CountDownLatch(1)
        val release = CountDownLatch(1)
        val engine = object : com.suhel.llamabro.internal.Engine by FakeEngine() {
            val model = FakeModel()
            override fun load(configJson: String, onProgress: (Float) -> Unit): com.suhel.llamabro.internal.EngineModel {
                loading.countDown()
                release.await(5, TimeUnit.SECONDS)
                return model
            }
        }
        val llama = LlamaBro(com.suhel.llamabro.internal.LlamaBroRuntime(engine, tempDir(), "/b", LlamaBroConfig()))
        var outcome: Throwable? = null
        val job = launch(Dispatchers.Default) {
            outcome = runCatching { llama.loadModel(ModelSource.file("/m.gguf")) }.exceptionOrNull()
        }
        assertTrue(loading.await(5, TimeUnit.SECONDS))
        job.cancel()
        release.countDown()
        job.join()
        assertTrue("$outcome", outcome is CancellationException)
        assertTrue("the loaded model leaked", engine.model.closed)
    }
}
