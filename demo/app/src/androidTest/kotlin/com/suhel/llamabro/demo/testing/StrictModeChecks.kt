package com.suhel.llamabro.demo.testing

import android.os.StrictMode
import android.os.strictmode.Violation
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor

/**
 * Runs [block] on the main thread under a StrictMode policy that collects disk and network violations, and fails
 * with their descriptions if there were any. For a single call you suspect; [TestDemoApp] already kills the process
 * on any violation during every test.
 */
fun <T> assertNoMainThreadIo(block: () -> T): T {
    val violations = mutableListOf<String>()
    var result: Result<T>? = null
    InstrumentationRegistry.getInstrumentation().runOnMainSync {
        val previous = StrictMode.getThreadPolicy()
        StrictMode.setThreadPolicy(
            StrictMode.ThreadPolicy
                .Builder()
                .detectDiskReads()
                .detectDiskWrites()
                .detectNetwork()
                .penaltyListener(Executor { it.run() }) { violations += it.stackTraceToString().lineSequence().first() }
                .build(),
        )
        try {
            result = runCatching(block)
        } finally {
            StrictMode.setThreadPolicy(previous)
        }
    }
    assertTrue("main-thread I/O: $violations", violations.isEmpty())
    return result!!.getOrThrow()
}

/**
 * The StrictMode violations of the current test that are the app's to fix. A violation is left out only when its stack
 * has no frame of ours and has a frame of one of [FOREIGN], the three sources found by reading every stack of
 * the first device run (2026-11-10): the system server (a Binder call into OnePlus's code), OnePlus's UI-first manager
 * (inside `ViewRootImpl` and the keyboard bridge, in our process), and Google's accessibility test framework loading its
 * strings. A stack from anywhere else, or with one of our frames, fails the test.
 */
object StrictViolations {
    private val FOREIGN =
        listOf("com.android.server.", "com.oplus.", "com.google.android.apps.common.testing.accessibility.")
    private val own = CopyOnWriteArrayList<String>()

    fun isForeign(frames: List<String>): Boolean =
        frames.none { it.startsWith("com.suhel.llamabro") } &&
            frames.any { frame -> FOREIGN.any { frame.startsWith(it) } }

    fun record(violation: Violation) {
        if (!isForeign(violation.stackTrace.map { it.className })) {
            own +=
                violation
                    .stackTraceToString()
                    .lineSequence()
                    .take(70)
                    .joinToString("\n")
        }
    }

    fun count() = own.size

    fun reset() = own.clear()

    fun assertNone() = assertTrue("main-thread I/O by the app:\n" + own.joinToString("\n--\n"), own.isEmpty())
}
