package com.suhel.llamabro.demo.benchmark

import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlin.concurrent.thread

/**
 * The Java heap the app holds, read from `dumpsys meminfo`. Macrobenchmark's own heap metric is the size of the heap
 * ART has set aside, and the collector sets aside several times what is live (it climbs by 30 to 50 MB during a
 * stream and falls back after a collection); the budget is on what the app holds, the "Heap Alloc" column.
 */
object HeapSampler {
    private const val OUTPUT_FILE = "heap-alloc.json"
    private const val POLL_MILLIS = 500L

    // the row's numbers: Pss, Private Dirty, Private Clean, SwapPss, Rss, Heap Size, Heap Alloc, Heap Free
    private const val ALLOC_COLUMN = 6

    private val peaksKb = mutableListOf<Long>()

    /** Runs [block] while sampling; keeps the highest Heap Alloc seen during it as one run's peak. */
    fun MacrobenchmarkScope.sampling(block: () -> Unit) {
        var peak = 0L
        val sampler =
            thread {
                try {
                    while (true) {
                        peak = maxOf(peak, heapAllocKb())
                        Thread.sleep(POLL_MILLIS)
                    }
                } catch (_: InterruptedException) {
                    // the block is over
                }
            }
        try {
            block()
        } finally {
            sampler.interrupt()
            sampler.join()
        }
        peaksKb += peak
        save()
    }

    private fun MacrobenchmarkScope.heapAllocKb(): Long {
        val row =
            device
                .executeShellCommand("dumpsys meminfo $packageName")
                .lineSequence()
                .firstOrNull { it.trimStart().startsWith("Dalvik Heap") }
                ?: return 0
        val numbers =
            row
                .trim()
                .removePrefix("Dalvik Heap")
                .trim()
                .split(Regex("\\s+"))
                .mapNotNull { it.toLongOrNull() }
        return numbers.getOrElse(ALLOC_COLUMN) { 0 }
    }

    /** Beside the benchmark's own results, where the build collects them from the phone. */
    private fun save() {
        val dir = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir") ?: return
        val peaks = peaksKb.joinToString(prefix = "[", postfix = "]")
        File(dir, OUTPUT_FILE).writeText("""{"javaHeapAllocatedPeakKb": $peaks}""")
    }
}
