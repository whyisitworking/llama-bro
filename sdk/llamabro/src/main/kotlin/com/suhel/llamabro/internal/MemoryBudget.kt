package com.suhel.llamabro.internal

/**
 * What a model may use in total for `ContextSize.Auto` (D33), from Android's view of memory at load time. The
 * native side fits the context to it with upstream's own measurement, over every device, since a phone has one RAM.
 * Measured on the OnePlus 13 (F66): that projection is 110–425 MiB above what loading and filling the context really
 * cost, so it already covers itself; the reserve covers what grows during chats and isn't projected (context
 * checkpoints, 8 × about 50 MiB on Qwen3.5), plus slack. The prompt cache (`ramCacheMib`) and the side files the
 * projection leaves out (the image encoder, LoRA adapters) are subtracted on their own. An MTP head's draft context
 * isn't projected either; the reserve covers it.
 */
internal object MemoryBudget {
    const val RESERVE_MIB: Long = 512
    private const val KIB = 1024L
    private const val MIB = KIB * KIB

    /** [available] and [threshold] in bytes. At least 1 MiB (native then says why it doesn't fit). */
    fun budgetMib(
        available: Long,
        threshold: Long,
        ramCacheMib: Int,
        sideFileBytes: Long,
        reserveMib: Long = RESERVE_MIB,
    ): Long = maxOf(1L, (available - threshold - sideFileBytes) / MIB - ramCacheMib - reserveMib)

    /**
     * Memory at load time: [available] is the kernel's `MemAvailable`, which is what AOSP returns as
     * `ActivityManager.MemoryInfo.availMem` (`Process.getFreeMemory`). Some OEM builds report less there: on the
     * OnePlus 13 `availMem` leaves out the GPU and NPU drivers' page pools (`KReclaimable`), which the kernel hands
     * back under pressure (F66), so the SDK reads `/proc/meminfo` itself. [threshold] is Android's low-memory level.
     */
    class Snapshot(val available: Long, val threshold: Long)

    /** `MemAvailable` from `/proc/meminfo` text, in bytes; null if absent. */
    fun memAvailable(meminfo: String): Long? =
        Regex(
            """^MemAvailable:\s+(\d+)\s+kB""",
            RegexOption.MULTILINE,
        ).find(meminfo)?.groupValues?.get(1)?.toLongOrNull()?.times(KIB)
}
