package com.suhel.llamabro.demo.models

import com.suhel.llamabro.DeviceKind
import org.junit.Assert.assertEquals
import org.junit.Test

class FitTest {
    private val gib = 1L shl 30
    private val budget = 6 * gib

    private fun gb(x: Double) = (x * 1_000_000_000).toLong()

    private fun quick(sizeGb: Double, chip: DeviceKind = DeviceKind.Npu): FitLabel {
        val bytes = gb(sizeGb)
        return Fit.label(Fit.roomFromSize(bytes, budget), Fit.tokensPerSecond(bytes, chip))
    }

    @Test fun labelsMatchTheDesign() {
        assertEquals(FitLabel.RunsGreat, quick(0.74))
        assertEquals(FitLabel.RunsWell, quick(2.9))
        assertEquals(FitLabel.SlowHere, quick(6.0))
        assertEquals(FitLabel.TooBig, quick(20.0))
    }

    @Test fun roomFromEstimateValues() {
        assertEquals(ContextRoom.None, Fit.roomFromEstimate(fits = false, contextTokens = 32768))
        assertEquals(ContextRoom.None, Fit.roomFromEstimate(fits = false, contextTokens = null))
        assertEquals(ContextRoom.Large, Fit.roomFromEstimate(fits = true, contextTokens = 16384))
        assertEquals(ContextRoom.Normal, Fit.roomFromEstimate(fits = true, contextTokens = 8192))
        assertEquals(ContextRoom.Normal, Fit.roomFromEstimate(fits = true, contextTokens = 4096))
        assertEquals(ContextRoom.Small, Fit.roomFromEstimate(fits = true, contextTokens = 2048))
        assertEquals(ContextRoom.Small, Fit.roomFromEstimate(fits = true, contextTokens = 4095))
    }

    @Test fun roomFromSizeBoundaries() {
        val file = 3 * gib
        assertEquals(ContextRoom.Large, Fit.roomFromSize(file, file + gib + gib / 2))
        assertEquals(ContextRoom.Normal, Fit.roomFromSize(file, file + gib + gib / 2 - 1))
        assertEquals(ContextRoom.Normal, Fit.roomFromSize(file, file + gib / 2))
        assertEquals(ContextRoom.Small, Fit.roomFromSize(file, file + gib / 2 - 1))
        assertEquals(ContextRoom.Small, Fit.roomFromSize(file, file + 1))
        assertEquals(ContextRoom.None, Fit.roomFromSize(file, file))
        assertEquals(ContextRoom.None, Fit.roomFromSize(file, file - 1))
    }

    @Test fun speedIsMemoryBoundPerChip() {
        val bytes = 2_740_000_000L
        assertEquals(15.3, Fit.tokensPerSecond(bytes, DeviceKind.Npu), 0.1)
        assertEquals(9.5, Fit.tokensPerSecond(bytes, DeviceKind.Gpu), 0.1)
        assertEquals(10.9, Fit.tokensPerSecond(bytes, DeviceKind.Cpu), 0.1)
    }

    @Test fun wordsPerSecond() {
        assertEquals(38, Fit.wordsPerSecond(50.7))
        assertEquals(0, Fit.wordsPerSecond(0.0))
    }

    @Test fun labelBoundaries() {
        assertEquals(FitLabel.RunsGreat, Fit.label(ContextRoom.Large, 20.0))
        assertEquals(FitLabel.RunsWell, Fit.label(ContextRoom.Large, 19.99))
        assertEquals(FitLabel.RunsWell, Fit.label(ContextRoom.Large, 8.0))
        assertEquals(FitLabel.SlowHere, Fit.label(ContextRoom.Large, 7.99))
        assertEquals(FitLabel.RunsWell, Fit.label(ContextRoom.Normal, 8.0))
        assertEquals(FitLabel.RunsWell, Fit.label(ContextRoom.Normal, 30.0))
        assertEquals(FitLabel.SlowHere, Fit.label(ContextRoom.Small, 50.0))
        assertEquals(FitLabel.TooBig, Fit.label(ContextRoom.None, 50.0))
    }
}
