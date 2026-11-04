package com.suhel.llamabro

import com.suhel.llamabro.internal.LlamaBroRuntime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class RuntimeTest {
    @Test
    fun loggerReceivesNativeLogsAtOrAboveLevel() {
        val engine = FakeEngine()
        val lines = mutableListOf<Pair<LogLevel, String>>()
        LlamaBroRuntime(engine, tempDir(), "/b", LlamaBroConfig(LogLevel.Warn) { level, msg -> lines += level to msg })
        val log = engine.logListener!!
        log(1, "info line\n")
        log(2, "warn line\n")
        assertEquals(listOf(LogLevel.Warn to "warn line"), lines)
    }

    @Test
    fun configMismatchThrowsIllegalState() {
        val engine = FakeEngine()
        engine.initResult = { config ->
            if (engine.initConfigs.size > 1 && config != engine.initConfigs.first()) {
                """{"error":"runtime already initialized with a different config"}"""
            } else {
                null
            }
        }
        LlamaBroRuntime(engine, tempDir(), "/b", LlamaBroConfig(LogLevel.Warn))
        assertThrows(IllegalStateException::class.java) {
            LlamaBroRuntime(engine, tempDir(), "/b", LlamaBroConfig(LogLevel.Error))
        }
    }

    @Test
    fun devicesParsed() {
        val devices = testLlamaBro().devices
        assertEquals(listOf(DeviceInfo("CPU", "cpu", "cpu", 1, 2)), devices)
    }
}
