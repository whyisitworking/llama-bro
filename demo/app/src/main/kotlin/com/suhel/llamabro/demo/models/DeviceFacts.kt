package com.suhel.llamabro.demo.models

import com.suhel.llamabro.DeviceKind
import com.suhel.llamabro.LlamaBro
import kotlinx.coroutines.CancellationException

/**
 * What the phone can do, read from the SDK once: the memory a model may use, the chip a model is expected to run on
 * ([chip]: `Auto`'s pick, or the one chosen in Settings › Memory, see [preferring]), and every kind of chip the app can
 * use here ([kinds], D72).
 */
data class DeviceFacts(
    val budgetBytes: Long,
    val chip: DeviceKind,
    val kinds: Set<DeviceKind> = setOf(chip, DeviceKind.Cpu),
) {
    companion object {
        /**
         * Used when the SDK can't say (a fake in a test, an unreadable /proc/meminfo): a mid-range phone's room, and
         * every kind, so no choice is marked missing on a guess.
         */
        val Fallback = DeviceFacts(4L shl 30, DeviceKind.Cpu, kinds = DeviceKind.entries.toSet())

        suspend fun read(llamaBro: LlamaBro): DeviceFacts =
            try {
                val kinds = llamaBro.devices().map { it.kind }.toSet()
                val chip =
                    listOf(DeviceKind.Npu, DeviceKind.Gpu, DeviceKind.Cpu).firstOrNull { it in kinds } ?: DeviceKind.Cpu
                DeviceFacts(llamaBro.memoryBudgetBytes() ?: Fallback.budgetBytes, chip, kinds + DeviceKind.Cpu)
            } catch (e: CancellationException) {
                throw e
            } catch (ignored: Exception) {
                Fallback
            }
    }

    /** These facts for a model loaded preferring [kind] (D72): that chip when this phone has it, else [chip]. */
    fun preferring(kind: DeviceKind?): DeviceFacts = copy(chip = kind?.takeIf { it in kinds } ?: chip)
}
