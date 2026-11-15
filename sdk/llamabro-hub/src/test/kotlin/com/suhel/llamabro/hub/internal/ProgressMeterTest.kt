package com.suhel.llamabro.hub.internal

import com.suhel.llamabro.DownloadPhase
import com.suhel.llamabro.DownloadProgress
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.milliseconds

class ProgressMeterTest {
    @Test fun throttlesOrdinaryUpdatesButDeliversPhaseChangesAndLast() =
        runTest {
            var now = 0L
            val reports = mutableListOf<DownloadProgress>()
            val meter = ProgressMeter(500.milliseconds, reports::add) { now }
            meter.update(DownloadPhase.Resolving, 0, 0, null, 0, 0)
            meter.update(DownloadPhase.Downloading, 0, 1000, "model.gguf", 1, 1)
            repeat(10) { step ->
                now += 100_000_000L
                meter.update(DownloadPhase.Downloading, (step + 1) * 100L, 1000, "model.gguf", 1, 1)
            }
            meter.update(DownloadPhase.Complete, 1000, 1000, null, 0, 1)
            assertTrue(reports.size <= 5)
            assertEquals(DownloadPhase.Resolving, reports.first().phase)
            assertEquals(DownloadPhase.Complete, reports.last().phase)
            assertEquals(1000L, reports.last().bytesDone)
        }

    @Test fun speedAndEtaAppearAfterTwoOneSecondWindows() =
        runTest {
            var now = 0L
            val reports = mutableListOf<DownloadProgress>()
            val meter = ProgressMeter(100.milliseconds, reports::add) { now }
            meter.update(DownloadPhase.Downloading, 200, 1200, "model.gguf", 1, 1)
            assertNull(reports.last().bytesPerSecond)
            now += 1_000_000_000L
            meter.update(DownloadPhase.Downloading, 300, 1200, "model.gguf", 1, 1)
            assertNull(reports.last().bytesPerSecond)
            now += 1_000_000_000L
            meter.update(DownloadPhase.Downloading, 400, 1200, "model.gguf", 1, 1)
            assertEquals(100L, reports.last().bytesPerSecond)
            assertEquals(8L, reports.last().etaSeconds)
        }
}
