package com.suhel.llamabro.demo.models

import com.suhel.llamabro.DeviceKind
import com.suhel.llamabro.LlamaBro
import kotlinx.coroutines.CancellationException

/** What the phone can do, read from the SDK once: the memory a model may use and the chip `Auto` would pick. */
data class DeviceFacts(val budgetBytes: Long, val chip: DeviceKind) {
    companion object {
        /** Used when the SDK can't say (a fake in a test, an unreadable /proc/meminfo): a mid-range phone's room. */
        val Fallback = DeviceFacts(4L shl 30, DeviceKind.Cpu)

        suspend fun read(llamaBro: LlamaBro): DeviceFacts =
            try {
                val kinds = llamaBro.devices().map { it.kind }.toSet()
                val chip =
                    listOf(DeviceKind.Npu, DeviceKind.Gpu, DeviceKind.Cpu).firstOrNull { it in kinds } ?: DeviceKind.Cpu
                DeviceFacts(llamaBro.memoryBudgetBytes() ?: Fallback.budgetBytes, chip)
            } catch (e: CancellationException) {
                throw e
            } catch (ignored: Exception) {
                Fallback
            }
    }
}
