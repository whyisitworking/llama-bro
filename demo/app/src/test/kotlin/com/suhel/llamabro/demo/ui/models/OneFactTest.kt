package com.suhel.llamabro.demo.ui.models

import com.suhel.llamabro.demo.designsystem.components.DownloadStage
import com.suhel.llamabro.demo.models.Catalog
import com.suhel.llamabro.demo.models.DownloadFailure
import com.suhel.llamabro.demo.models.DownloadRecord
import com.suhel.llamabro.demo.models.DownloadStatus
import com.suhel.llamabro.demo.models.Guess
import com.suhel.llamabro.demo.models.SearchModel
import com.suhel.llamabro.demo.models.keepArrived
import com.suhel.llamabro.hub.HfModel
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * One fact, one function: the maker, the progress string, the bytes a stopped download
 * kept and a size guessed from a name, each the same wherever a row, the card or a dialog says it.
 */
class OneFactTest {
    private val deep = Catalog.suggested[2]

    /** The catalog's maker wins on every row of its repo; another Qwen upload reads the same through the map. */
    @Test fun everyRowOfAModelNamesOneMaker() {
        val hubRow =
            SearchModel(
                "qwen2.5-coder-7b",
                "Qwen2.5-Coder-7B",
                "someone/Qwen2.5-Coder-7B-GGUF",
                "someone",
                1,
                false,
                null,
            )
        assertEquals("Alibaba Qwen", ModelEntry.of(hubRow.copy(maker = "Qwen")).maker)
        val download = ModelEntry.downloading(deep.key, DownloadRecord(deep.key, deep.realName, "Qwen", 1))
        assertEquals(deep.maker, download.maker)
        assertEquals(deep.maker, ModelEntry.of(deep).maker)
    }

    /** A parameter count in the name stands in for the header's; not an active or effective count. */
    @Test fun aNamesParameterCountIsTheGuess() {
        assertEquals(7_000_000_000L, Guess.namedParameters("Qwen2.5 Coder 7B"))
        assertEquals(500_000_000L, Guess.namedParameters("Tiny 0.5B"))
        assertEquals(30_000_000_000L, Guess.namedParameters("Qwen3 30B A3B"))
        assertEquals(null, Guess.namedParameters("Phi 4 mini"))
    }

    /** A stopped download keeps its bytes: the ring frozen at them and the line says so. */
    @Test fun aStoppedDownloadSaysWhatItKept() {
        val failure = DownloadFailure(DownloadFailure.Kind.Network, retryable = true)
        val kept =
            keepArrived(
                DownloadStatus.Downloading(310_000_000, 739_000_000, null, null),
                DownloadStatus.Failed(failure),
            )
        assertEquals(DownloadStatus.Failed(failure, 310_000_000, 739_000_000), kept)
        val view = DownloadViews.of(HfModel.parse("${deep.key.repo}:Q4_K_M"), kept, recordBytes = null)!!
        assertEquals(DownloadStage.Stopped, view.stage)
        assertEquals("The connection dropped · 310 of 739 MB kept", view.detail)
        assertEquals(310f / 739f, view.fraction, 0.001f)
    }

    /** The cancel dialog says the panel's string. */
    @Test fun theCancelQuestionSaysTheProgressStringOfThePanel() {
        val status = DownloadStatus.Downloading(900_000_000, 3_400_000_000, null, null)
        val panel = DownloadViews.of(deep.key, status, 3_400_000_000)!!.detail
        val question = DownloadViews.cancelQuestion(status, 3_400_000_000)!!
        assertEquals("0.9 of 3.4 GB", panel)
        assertEquals("$panel has arrived. Cancelling deletes it.", question)
    }

    /** The remove dialog's title never breaks a name from its size. */
    @Test fun aTitleKeepsTheSizeWithTheName() {
        assertEquals("Qwen3.5 4B", keepSizeWithName("Qwen3.5 4B"))
        assertEquals("Gemma", keepSizeWithName("Gemma"))
    }
}
