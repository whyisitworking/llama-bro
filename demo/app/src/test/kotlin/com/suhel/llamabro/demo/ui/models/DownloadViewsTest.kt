package com.suhel.llamabro.demo.ui.models

import com.suhel.llamabro.demo.designsystem.components.DownloadStage
import com.suhel.llamabro.demo.designsystem.components.DownloadView
import com.suhel.llamabro.demo.models.DownloadFailure
import com.suhel.llamabro.demo.models.DownloadStatus
import com.suhel.llamabro.hub.HfModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** A download as the ring panel shows it: the quant always, the record's total while the hub has none. */
class DownloadViewsTest {
    private val key = HfModel.parse("unsloth/Qwen3.5-4B-GGUF:Q4_K_M")

    @Test fun aDownloadSaysHowMuchHasArrivedAndHowLongIsLeft() {
        val view = DownloadViews.of(key, DownloadStatus.Downloading(900_000_000, 3_400_000_000, 9_800_000, 240), null)
        assertEquals(
            DownloadView(
                DownloadStage.Downloading,
                "Q4_K_M",
                900_000_000f / 3_400_000_000f,
                "0.9 of 3.4 GB · 4 min left",
            ),
            view,
        )
    }

    @Test fun theRecordsTotalStandsInWhileTheHubHasNone() {
        // after a restart the hub knows a partial with no total yet; the row still says "of 3.4 GB"
        val view = DownloadViews.of(key, DownloadStatus.Partial(0, 0), recordBytes = 3_400_000_000)
        assertEquals(DownloadView(DownloadStage.Paused, "Q4_K_M", 0f, "0.0 of 3.4 GB"), view)
    }

    @Test fun theChosenSizeWinsOverTheHubsTotal() {
        // one order everywhere (record, then the row's size, then the hub's), so the line never switches between two
        val view =
            DownloadViews.of(
                key,
                DownloadStatus.Downloading(1_000_000_000, 3_600_000_000, null, null),
                3_400_000_000,
            )
        assertEquals("1.0 of 3.4 GB", view?.detail)
        assertEquals(3_400_000_000, DownloadViews.totalOf(DownloadStatus.Partial(0, 3_600_000_000), 3_400_000_000))
        assertEquals(3_600_000_000, DownloadViews.totalOf(DownloadStatus.Partial(0, 3_600_000_000), null))
    }

    @Test fun aWaitingDownloadNamesTheOneAhead() {
        assertEquals("after Gemma 3 4B", DownloadViews.of(key, DownloadStatus.Absent, null, "Gemma 3 4B")?.detail)
        assertEquals("Waiting to start", DownloadViews.of(key, DownloadStatus.Absent, null)?.detail)
    }

    @Test fun aStoppedDownloadSaysWhy() {
        val failed = DownloadStatus.Failed(DownloadFailure(DownloadFailure.Kind.Unauthorized, retryable = false))
        val view = DownloadViews.of(key, failed, 3_400_000_000)
        assertEquals(DownloadStage.Stopped, view?.stage)
        assertEquals("This model needs a Hugging Face token.", view?.detail)
    }

    @Test fun aModelOnThePhoneHasNoPanel() {
        assertNull(DownloadViews.of(key, DownloadStatus.Complete, 3_400_000_000))
    }

    @Test fun underAGigabyteTheLineIsInMegabytes() {
        val view = DownloadViews.of(key, DownloadStatus.Downloading(310_000_000, 740_000_000, null, null), null)
        assertEquals("310 of 740 MB", view?.detail)
    }
}
