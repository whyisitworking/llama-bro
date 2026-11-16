package com.suhel.llamabro.demo.journey

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.performTextInput
import com.suhel.llamabro.DeviceKind
import com.suhel.llamabro.demo.models.Catalog
import com.suhel.llamabro.demo.models.HubEstimate
import com.suhel.llamabro.demo.models.RepoFile
import com.suhel.llamabro.hub.DownloadState
import com.suhel.llamabro.hub.HfModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Finding, fetching and keeping models: Get models, the sheet, the downloads and the library. */
@OptIn(ExperimentalTestApi::class)
class ModelsJourneys : Journey() {
    private val swift = Catalog.picks[0].key
    private val writer = Catalog.picks[1].key
    private val deep = Catalog.picks[2].key

    private fun openLibrary() {
        tapDescription("Chats")
        tapDrawerModels()
        see("Get more models")
    }

    /** From the library: Get models, a suggestion by its name, its sheet's Get. */
    private fun getSuggested(name: String) {
        tap("Get more models")
        see("SUGGESTED FOR THIS PHONE")
        tap(name)
        tap("Get $name", substring = true)
    }

    @Test fun pauseResumeAndCancelADownload() {
        world.downloads.finish(swift) // Qwen3.5 0.8B is on the phone already: the library has something to show
        openLibrary()
        getSuggested("Gemma 3 4B")
        tapDescription("Back") // from Get models to the library, which holds the downloads' own controls
        see("DOWNLOADING")
        shot("1-downloading")

        world.downloads.advance(writer)
        tap("Pause")
        see("Paused")
        shot("2-paused")
        tap("Resume")
        see("Pause")
        world.downloads.finish(writer)
        gone("DOWNLOADING")
        see("Gemma 3 4B")
        shot("3-done")

        // another download, then ✕: the row goes and nothing of it stays on the phone
        getSuggested("Qwen3.5 4B")
        tapDescription("Back")
        see("DOWNLOADING")
        world.downloads.advance(deep)
        tapDescription("Cancel") // the ✕
        gone("DOWNLOADING")
        compose.waitUntil(WAIT) {
            world.hub.hub
                .progress(deep)
                .value == DownloadState.Absent
        } // the partial went
        assertFalse("nothing of it is on the phone", world.hub.onPhone(deep))
        shot("4-cancelled")
    }

    @Test fun searchAndGetAnyModel() {
        val phi = "bartowski/phi-4-mini-GGUF"
        val files =
            listOf(
                RepoFile("phi-4-mini-Q3_K_M.gguf", "Q3_K_M", 1_900_000_000),
                RepoFile("phi-4-mini-Q4_K_M.gguf", "Q4_K_M", 2_500_000_000),
                RepoFile("phi-4-mini-Q8_0.gguf", "Q8_0", 4_500_000_000),
            )
        // two uploads of one model: search groups them under the original (D68), one row
        world.hub.list(phi, downloads = 1_200_000, baseModel = "microsoft/phi-4-mini", tags = files.map { it.quant })
        world.hub.list("someone/phi-4-mini-GGUF", downloads = 300, baseModel = "microsoft/phi-4-mini")
        world.discovery.repoFiles[phi] = files
        for (f in files) {
            world.discovery.estimates[HfModel.parse("$phi:${f.quant}")] =
                HubEstimate(f.bytes, 0, true, 32_768, f.bytes + 300_000_000, DeviceKind.Npu)
        }

        openLibrary()
        tap("Get more models")
        compose.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag("models_search"))).performTextInput("phi")
        see("Phi 4 mini")
        compose.onAllNodesWithTag("model_row").assertCountEquals(1) // one row for the model, not one per file
        shot("1-results")
        tap("Phi 4 mini")
        tap("Advanced")
        see("Q8_0")
        tap("Q8_0")
        shot("2-advanced")
        tap("Get Q8_0", substring = true)

        tapDescription("Back")
        see("DOWNLOADING")
        world.downloads.finish(HfModel.parse("$phi:Q8_0"))
        gone("DOWNLOADING")
        see("phi-4-mini", substring = true)
        assertEquals(listOf(HfModel.parse("$phi:Q8_0")), world.downloads.enqueued)
        shot("3-library")
    }
}
