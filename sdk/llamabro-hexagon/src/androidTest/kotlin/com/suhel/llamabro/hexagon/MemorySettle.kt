package com.suhel.llamabro.hexagon

import java.io.File

/** The kernel's MemAvailable in MiB, the figure ContextSize.Auto budgets from; null if unreadable. */
internal fun memAvailableMiB(): Long? =
    Regex("""^MemAvailable:\s+(\d+)\s+kB""", RegexOption.MULTILINE)
        .find(File("/proc/meminfo").readText())
        ?.groupValues
        ?.get(1)
        ?.toLongOrNull()
        ?.div(1024)

/**
 * Waits until MemAvailable stops rising (three 1 s reads within 50 MiB), at most 60 s. The kernel hands a closed
 * model's memory back gradually, and a load right after a large one would otherwise budget from a phone still
 * reclaiming it (F90).
 */
internal fun settleMemory(): Long? {
    var last = memAvailableMiB() ?: return null
    var steady = 0
    repeat(60) {
        Thread.sleep(1_000)
        val now = memAvailableMiB() ?: return last
        steady = if (now - last < 50) steady + 1 else 0
        last = now
        if (steady >= 3) return now
    }
    return last
}
