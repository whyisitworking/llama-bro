package com.suhel.llamabro

import com.suhel.llamabro.internal.LlamaBroRuntime
import com.suhel.llamabro.internal.MemoryBudget
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The budget `ContextSize.Auto` would use, for fit labels without a plan per model (F97). */
class MemoryBudgetApiTest {
    private val gib = 1024L * 1024 * 1024
    private val mib = 1024L * 1024

    @Test
    fun memoryBudgetIsNullWithoutASnapshot() =
        runTest {
            // FakeLlamaBro's engine has no memory snapshot, as here
            assertNull(testLlamaBro().memoryBudgetBytes())
        }

    @Test
    fun memoryBudgetFollowsTheReserve() =
        runTest {
            val llama =
                LlamaBro(
                    LlamaBroRuntime(
                        FakeEngine(),
                        tempDir(),
                        null,
                        LlamaBroConfig.Default,
                        memorySnapshot = { MemoryBudget.Snapshot(8 * gib, gib / 2) },
                    ),
                )
            // available less the low-memory threshold, the 1024 MiB prompt cache and the 512 MiB reserve
            val defaults = 8 * gib - gib / 2 - 1024 * mib - 512 * mib
            assertEquals(defaults, llama.memoryBudgetBytes())
            assertEquals(defaults + 512 * mib, llama.memoryBudgetBytes { memoryReserveMib = 0 })
        }
}
