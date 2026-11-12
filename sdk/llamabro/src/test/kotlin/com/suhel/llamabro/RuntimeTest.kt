package com.suhel.llamabro

import com.suhel.llamabro.internal.LlamaBroRuntime
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeTest {
    @Test
    fun loggerReceivesNativeLogsAtOrAboveLevel() {
        val engine = FakeEngine()
        val lines = mutableListOf<Pair<LogLevel, String>>()
        val runtime =
            LlamaBroRuntime(
                engine,
                tempDir(),
                "/b",
                LlamaBroConfig {
                    logLevel = LogLevel.Warn
                    logger =
                        Logger { level, msg -> lines += level to msg }
                },
            )
        runBlocking { runtime.devices() }
        val log = engine.logListener!!
        log(1, "info line\n")
        log(2, "warn line\n")
        assertEquals(listOf(LogLevel.Warn to "warn line"), lines)
    }

    /** Native init costs about 13 ms on the phone (F48): getInstance must not run it on the caller's thread. */
    @Test
    fun initRunsOnFirstUseNotOnCreation() =
        runBlocking {
            val engine = FakeEngine()
            val llama = testLlamaBro(engine)
            assertEquals(0, engine.initConfigs.size)
            llama.devices()
            llama.loadModel(ModelSource.file("/m.gguf"))
            assertEquals(1, engine.initConfigs.size)
        }

    @Test
    fun configMismatchThrowsInvalidRequestOnFirstUse() {
        val engine = FakeEngine()
        engine.initResult = { config ->
            if (engine.initConfigs.size > 1 && config != engine.initConfigs.first()) {
                """{"status":2,"error":"runtime already initialized with a different config"}"""
            } else {
                null
            }
        }
        runBlocking { LlamaBroRuntime(engine, tempDir(), "/b", LlamaBroConfig { logLevel = LogLevel.Warn }).devices() }
        val second = LlamaBroRuntime(engine, tempDir(), "/b", LlamaBroConfig { logLevel = LogLevel.Error })
        val err =
            runCatching {
                runBlocking {
                    LlamaBro(
                        second,
                    ).loadModel(ModelSource.file("/m.gguf"))
                }
            }.exceptionOrNull()
        assertTrue("$err", err is LlamaBroException.InvalidRequest && "different LlamaBroConfig" in err.message!!)
    }

    @Test
    fun devicesParsed() {
        val devices = runBlocking { testLlamaBro().devices() }
        assertEquals(listOf(DeviceInfo("CPU", "cpu", DeviceKind.Cpu, 1, 2)), devices)
    }
}
