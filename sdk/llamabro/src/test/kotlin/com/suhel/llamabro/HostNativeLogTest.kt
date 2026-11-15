package com.suhel.llamabro

import com.suhel.llamabro.internal.Engine
import com.suhel.llamabro.internal.JniEngine
import com.suhel.llamabro.internal.LlamaBroRuntime
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.Collections

/**
 * Native init's own log lines reach the app's logger (D66, F144). The native runtime initialises once per process,
 * so this runs in a JVM of its own (`forkEvery` in build.gradle.kts), where nothing has initialised it first.
 */
class HostNativeLogTest {
    @Test
    fun initLinesReachTheLogger() {
        val lib = System.getProperty("llamabro.host.lib")
        assumeTrue("host end-to-end runs from sdk/scripts/check.sh", lib != null)
        val lines = Collections.synchronizedList(mutableListOf<Pair<LogLevel, String>>())
        val config =
            LlamaBroConfig {
                logLevel = LogLevel.Debug
                logger = Logger { level, message -> lines += level to message }
            }
        // lines delivered by the time init returns: only a logger attached before init can have any
        var linesWhenInitReturned = -1
        val engine =
            object : Engine by JniEngine {
                override fun init(configJson: String): String? =
                    JniEngine.init(configJson).also { linesWhenInitReturned = lines.size }
            }
        runBlocking { LlamaBro(LlamaBroRuntime(engine, tempDir(), lib, config)).devices() }
        assertTrue("no native line during init", linesWhenInitReturned > 0)
    }
}
