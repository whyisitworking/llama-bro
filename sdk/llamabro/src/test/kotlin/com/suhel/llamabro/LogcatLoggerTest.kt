package com.suhel.llamabro

import com.suhel.llamabro.spi.LogcatLogger
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Host tests do not mock android.util.Log, so a line that reaches it throws: that is how they tell dropped from kept.
 * This relies on `unitTests.isReturnDefaultValues` staying false (the default; no build file sets it).
 */
@OptIn(InternalLlamaBroApi::class)
class LogcatLoggerTest {
    @Test
    fun linesBelowTheMinimumLevelAreDropped() {
        val logger = LogcatLogger("test", minLevel = LogLevel.Warn)
        logger.log(LogLevel.Debug, "dropped")
        logger.log(LogLevel.Info, "dropped")
    }

    @Test
    fun linesAtOrAboveTheMinimumLevelReachLogcat() {
        val logger = LogcatLogger("test", minLevel = LogLevel.Warn)
        for (level in listOf(LogLevel.Warn, LogLevel.Error)) {
            try {
                logger.log(level, "kept")
                fail("$level should have reached android.util.Log")
            } catch (e: RuntimeException) {
                assertTrue(e.message.orEmpty().contains("not mocked"))
            }
        }
    }
}
