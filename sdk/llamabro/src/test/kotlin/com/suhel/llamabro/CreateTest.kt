package com.suhel.llamabro

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** `LlamaBro.create`'s registry (D70): one runtime per process, the same object for an equal config. */
class CreateTest {
    @Before
    @After
    fun reset() = LlamaBro.resetCreatedForTest()

    private val logger = Logger { _, _ -> }

    @Test
    fun createWithAnEqualConfigReturnsTheSameObject() {
        var builds = 0
        val first =
            LlamaBro.create(LlamaBroConfig { this.logger = logger }) {
                builds++
                testLlamaBro()
            }
        val second =
            LlamaBro.create(LlamaBroConfig { this.logger = logger }) {
                builds++
                testLlamaBro()
            }
        assertSame(first, second)
        assertEquals(1, builds)
    }

    @Test
    fun createWithADifferentConfigThrowsBeforeNative() {
        LlamaBro.create(LlamaBroConfig { logLevel = LogLevel.Info }) { testLlamaBro() }
        val engine = FakeEngine()
        var built = false
        val error =
            assertThrows(LlamaBroException.InvalidRequest::class.java) {
                LlamaBro.create(LlamaBroConfig { logLevel = LogLevel.Debug }) {
                    built = true
                    testLlamaBro(engine)
                }
            }
        assertFalse(built)
        assertTrue(engine.calls.isEmpty())
        assertTrue(error.message!!.contains("logLevel=Info"))
        assertTrue(error.message!!.contains("logLevel=Debug"))
    }

    @Test
    fun refusedCreateLeavesTheFirstLoggerAttached() {
        val lines = mutableListOf<String>()
        val config = LlamaBroConfig { logger = Logger { _, msg -> lines += msg } }
        // One engine for both builds, like the process-global JniEngine: a second runtime that attached its logger
        // would replace the first's listener.
        val engine = FakeEngine()
        val first = LlamaBro.create(config) { testLlamaBro(engine, config) }
        runBlocking { first.devices() }
        val callsBefore = engine.calls.size
        assertThrows(LlamaBroException.InvalidRequest::class.java) {
            LlamaBro.create(LlamaBroConfig { logLevel = LogLevel.Error }) { testLlamaBro(engine) }
        }
        engine.logListener!!(3, "after the refusal\n")
        assertTrue(lines.contains("after the refusal"))
        assertEquals(callsBefore, engine.calls.size)
    }

    @Test
    fun aThrowingBuildLeavesTheRegistryClean() {
        assertThrows(IllegalStateException::class.java) { LlamaBro.create(LlamaBroConfig.Default) { error("boom") } }
        var builds = 0
        val first =
            LlamaBro.create(LlamaBroConfig.Default) {
                builds++
                testLlamaBro()
            }
        assertSame(first, LlamaBro.create(LlamaBroConfig.Default) { testLlamaBro() })
        assertEquals(1, builds)
    }

    @Test
    fun concurrentCreateBuildsOnce() {
        val builds = AtomicInteger()
        // The configs are equal because they share one logger instance: LlamaBroConfig compares it by reference.
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(8)
        try {
            val results =
                (1..8).map {
                    pool.submit<LlamaBro> {
                        start.await()
                        LlamaBro.create(LlamaBroConfig { this.logger = logger }) {
                            builds.incrementAndGet()
                            testLlamaBro()
                        }
                    }
                }
            start.countDown()
            val objects = results.map { it.get(10, TimeUnit.SECONDS) }
            assertEquals(1, builds.get())
            assertTrue(objects.all { it === objects.first() })
        } finally {
            pool.shutdownNow()
        }
    }
}
