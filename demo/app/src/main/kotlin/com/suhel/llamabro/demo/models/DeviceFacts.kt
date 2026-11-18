package com.suhel.llamabro.demo.models

import com.suhel.llamabro.DeviceKind
import com.suhel.llamabro.LlamaBro
import kotlinx.coroutines.CancellationException

/**
 * What the phone can do, read from the SDK once: the memory a model may use ([budgetBytes], half the phone's total RAM,
 * D101), the chip a model is expected to run on ([chip]: `Auto`'s pick, or the one chosen in Settings › Memory, see
 * [preferring]), and every kind of chip the app can use here ([kinds], D72).
 */
data class DeviceFacts(
    val budgetBytes: Long,
    val chip: DeviceKind,
    val kinds: Set<DeviceKind> = setOf(chip, DeviceKind.Cpu),
) {
    companion object {
        /**
         * The share of the phone's total RAM a model may use (D101): a fixed rule rather than free memory, which moves
         * with whatever else runs, so a model's label doesn't change between two looks.
         */
        const val MODEL_SHARE_OF_RAM = 0.5

        /**
         * Used when the SDK can't say (a fake in a test, a device list with no memory): half of an 8 GB mid-range
         * phone, and every kind, so no choice is marked missing on a guess.
         */
        val Fallback = DeviceFacts(4L shl 30, DeviceKind.Cpu, kinds = DeviceKind.entries.toSet())

        suspend fun read(llamaBro: LlamaBro): DeviceFacts =
            try {
                val devices = llamaBro.devices()
                val kinds = devices.map { it.kind }.toSet()
                val chip =
                    listOf(DeviceKind.Npu, DeviceKind.Gpu, DeviceKind.Cpu).firstOrNull { it in kinds } ?: DeviceKind.Cpu
                val total = totalRam(devices.map { it.kind to it.memoryTotal })
                DeviceFacts(total?.let(::budgetFor) ?: Fallback.budgetBytes, chip, kinds + DeviceKind.Cpu)
            } catch (e: CancellationException) {
                throw e
            } catch (ignored: Exception) {
                Fallback
            }

        /**
         * The phone's total RAM from the SDK's devices (kind to total bytes): the CPU device's, which is the system's
         * RAM, else the largest any device reports; null when none reports any.
         */
        fun totalRam(devices: List<Pair<DeviceKind, Long>>): Long? =
            devices.filter { it.first == DeviceKind.Cpu }.maxOfOrNull { it.second }?.takeIf { it > 0 }
                ?: devices.maxOfOrNull { it.second }?.takeIf { it > 0 }

        /** The memory a model may use on a phone with [totalBytes] of RAM (D101). */
        fun budgetFor(totalBytes: Long): Long = (totalBytes * MODEL_SHARE_OF_RAM).toLong()
    }

    /** These facts for a model loaded preferring [kind] (D72): that chip when this phone has it, else [chip]. */
    fun preferring(kind: DeviceKind?): DeviceFacts = copy(chip = kind?.takeIf { it in kinds } ?: chip)
}
