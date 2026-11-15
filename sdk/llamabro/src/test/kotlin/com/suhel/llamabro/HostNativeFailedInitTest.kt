package com.suhel.llamabro

import com.suhel.llamabro.internal.JniEngine
import com.suhel.llamabro.internal.LlamaBroRuntime
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * A native init that fails (no CPU backend in the directory) throws `InvalidRequest` carrying the native reason (D66).
 * The bridge reports that reason only in the error JSON and logs no line, so the exception is the one place it
 * appears; the test pins that. Runs in a JVM of its own (`forkEvery` in build.gradle.kts), like [HostNativeLogTest].
 */
class HostNativeFailedInitTest {
    @Test
    fun failedInitThrowsWithTheNativeReason() {
        val lib = System.getProperty("llamabro.host.lib")
        assumeTrue("host end-to-end runs from sdk/scripts/check.sh", lib != null)
        val runtime = LlamaBroRuntime(JniEngine, tempDir(), "$lib/does-not-exist", LlamaBroConfig.Default)
        try {
            runBlocking { LlamaBro(runtime).devices() }
            fail("init should have failed")
        } catch (e: LlamaBroException.InvalidRequest) {
            val message = e.message.orEmpty()
            assertTrue(message, "init failed" in message && "no CPU backend could be loaded" in message)
        }
    }
}
