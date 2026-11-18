package com.suhel.llamabro.demo.models

import com.suhel.llamabro.DeviceKind
import com.suhel.llamabro.testing.FakeLlamaBro
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The memory a model may use: half the phone's total RAM, read from the SDK's CPU device (D101). */
class DeviceFactsTest {
    private val gib = 1L shl 30

    @Test fun theBudgetIsHalfTheTotalRam() {
        assertEquals(6 * gib, DeviceFacts.budgetFor(12 * gib))
        assertEquals(4 * gib, DeviceFacts.budgetFor(8 * gib))
    }

    @Test fun totalRamIsTheCpuDevicesWhenItReportsOne() {
        val devices = listOf(DeviceKind.Npu to 16 * gib, DeviceKind.Cpu to 12 * gib, DeviceKind.Gpu to 6 * gib)
        assertEquals(12 * gib, DeviceFacts.totalRam(devices))
    }

    @Test fun totalRamFallsBackToTheLargestDevice() {
        assertEquals(6 * gib, DeviceFacts.totalRam(listOf(DeviceKind.Cpu to 0L, DeviceKind.Gpu to 6 * gib)))
        assertNull(DeviceFacts.totalRam(listOf(DeviceKind.Cpu to 0L)))
        assertNull(DeviceFacts.totalRam(emptyList()))
    }

    @Test fun aDeviceListWithoutMemoryGivesTheFallbackBudget() {
        // the fake engine's one CPU device reports no memory
        val facts = runBlocking { DeviceFacts.read(FakeLlamaBro().llamaBro) }
        assertEquals(DeviceFacts.Fallback.budgetBytes, facts.budgetBytes)
        assertEquals(DeviceKind.Cpu, facts.chip)
    }
}
