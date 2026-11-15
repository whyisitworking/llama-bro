package com.suhel.llamabro

import com.suhel.llamabro.internal.LlamaBroRuntime
import com.suhel.llamabro.internal.MemoryBudget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** A cancelled loadModel reaches the native load through its progress answer, and stops it there (D67, F145). */
class LoadCancelTest {
    private val gib = 1024L * 1024 * 1024

    /** Counts the models a load handed back, and which of them are still open. */
    private class Counting(val engine: FakeEngine) {
        val made = AtomicInteger()
        val models = mutableListOf<FakeModel>()

        init {
            engine.makeModel = {
                made.incrementAndGet()
                FakeModel().also { synchronized(models) { models += it } }
            }
        }

        fun open() = synchronized(models) { models.count { !it.closed } }
    }

    private fun llama(engine: FakeEngine, avail: Long? = null) =
        LlamaBro(
            LlamaBroRuntime(
                engine,
                tempDir(),
                null,
                LlamaBroConfig.Default,
                memorySnapshot = { avail?.let { MemoryBudget.Snapshot(it, gib) } },
            ),
        )

    /** Starts a load on another thread, cancels it once [reached] opens, and returns what loadModel threw. */
    private fun cancelLoad(llama: LlamaBro, reached: CountDownLatch, cancelled: CountDownLatch): Throwable? =
        runBlocking {
            var outcome: Throwable? = null
            val job =
                launch(Dispatchers.Default) {
                    outcome = runCatching { llama.loadModel(ModelSource.file("/m.gguf")) }.exceptionOrNull()
                }
            assertTrue(reached.await(5, TimeUnit.SECONDS))
            job.cancel()
            cancelled.countDown()
            job.join()
            outcome
        }

    @Test
    fun cancellingALoadStopsItAndFreesIt() {
        val engine = FakeEngine()
        val counting = Counting(engine)
        val reached = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        engine.progress = listOf(0.25f, 0.5f, 0.75f, 1f)
        engine.onStep = { p ->
            if (p == 0.5f) {
                reached.countDown()
                cancelled.await(5, TimeUnit.SECONDS)
            }
        }
        val outcome = cancelLoad(llama(engine), reached, cancelled)
        assertTrue("$outcome", outcome is CancellationException)
        assertEquals("the load stops at the tick after the cancel", 0.75f, engine.stoppedAt)
        assertEquals("a stopped load hands back no model", 0, counting.made.get())
        assertEquals(0, counting.open())
    }

    @Test
    fun aLoadAfterACancelledOneWorks() {
        val engine = FakeEngine()
        val counting = Counting(engine)
        val reached = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        engine.progress = listOf(0.25f, 0.5f, 1f)
        engine.onStep = { p ->
            if (p == 0.25f && reached.count > 0) {
                reached.countDown()
                cancelled.await(5, TimeUnit.SECONDS)
            }
        }
        val llama = llama(engine)
        assertTrue(cancelLoad(llama, reached, cancelled) is CancellationException)
        val model = runBlocking { llama.loadModel(ModelSource.file("/m.gguf")) }
        assertEquals(1, counting.made.get())
        assertEquals(1, counting.open())
        runBlocking { model.closeAndJoin() }
        assertEquals(0, counting.open())
    }

    /** The memory check refused the first load; a cancel before [MemoryCheck.Warn]'s second load starts it never. */
    @Test
    fun warnRetryIsNotStartedAfterCancel() {
        val engine = FakeEngine()
        val counting = Counting(engine)
        val reached = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        engine.loadError = { config ->
            if ("memory_budget_mib" in config) {
                reached.countDown()
                cancelled.await(5, TimeUnit.SECONDS)
                """{"error":"failed to load model: not enough memory","code":"insufficient_memory"}"""
            } else {
                null
            }
        }
        val outcome = cancelLoad(llama(engine, avail = gib), reached, cancelled)
        assertTrue("$outcome", outcome is CancellationException)
        assertEquals("the smallest-context retry must not start", 1, engine.loadConfigs.size)
        assertEquals(0, counting.made.get())
    }

    /** A cancel after the last tick can't stop the load: the model it returns is freed (the existing path, D67). */
    @Test
    fun aCancelAfterTheLastTickFreesTheLoadedModel() {
        val engine = FakeEngine()
        val counting = Counting(engine)
        val reached = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        engine.progress = listOf(0.5f, 1f)
        engine.onStep = { p ->
            if (p == 1f) {
                reached.countDown()
                cancelled.await(5, TimeUnit.SECONDS)
            }
        }
        val outcome = cancelLoad(llama(engine), reached, cancelled)
        assertTrue("$outcome", outcome is CancellationException)
        assertEquals(null, engine.stoppedAt)
        assertEquals(1, counting.made.get())
        assertFalse("the loaded model leaked", counting.open() > 0)
    }

    /** The encoder's device failed for a real reason while the caller was cancelled: no fallback load starts (D67). */
    @Test
    fun encoderFallbackIsNotStartedAfterCancel() {
        val engine = FakeEngine()
        engine.backendsJson =
            """[{"name":"cpu","loaded":true,"error":null,"devices":["CPU"]},""" +
            """{"name":"opencl","loaded":true,"error":null,"devices":["GPUOpenCL"]},""" +
            """{"name":"hexagon","loaded":true,"error":null,"devices":["HTP0"]}]"""
        val counting = Counting(engine)
        val reached = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        engine.loadError = { config ->
            if ("\"mmproj_device\":\"GPUOpenCL\"" in config) {
                reached.countDown()
                cancelled.await(5, TimeUnit.SECONDS)
                """{"error":"encoder failed on GPUOpenCL"}"""
            } else {
                null
            }
        }
        val llama = LlamaBro(LlamaBroRuntime(engine, tempDir(), "/backends", LlamaBroConfig.Default))
        val outcome =
            runBlocking {
                var thrown: Throwable? = null
                val job =
                    launch(Dispatchers.Default) {
                        thrown =
                            runCatching {
                                llama.loadModel(ModelSource.file("/m.gguf") { mmproj = "/mm.gguf" }) {
                                    encoderDevice = DevicePolicy.Prefer(DeviceKind.Gpu)
                                }
                            }.exceptionOrNull()
                    }
                assertTrue(reached.await(5, TimeUnit.SECONDS))
                job.cancel()
                cancelled.countDown()
                job.join()
                thrown
            }
        assertTrue("$outcome", outcome is CancellationException)
        assertEquals("the encoder fallback must not start", 1, engine.loadConfigs.size)
        assertEquals(0, counting.made.get())
    }
}
