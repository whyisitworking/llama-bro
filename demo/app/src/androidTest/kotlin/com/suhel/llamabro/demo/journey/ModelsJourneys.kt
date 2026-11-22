package com.suhel.llamabro.demo.journey

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.suhel.llamabro.demo.models.Catalog
import com.suhel.llamabro.demo.models.RepoFile
import com.suhel.llamabro.hub.DownloadState
import com.suhel.llamabro.hub.HfModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** Finding, fetching and keeping models on the Models page: the sections, search, the card and the downloads. */
@OptIn(ExperimentalTestApi::class)
class ModelsJourneys : Journey() {
    private val swift = Catalog.picks[0].key
    private val writer = Catalog.picks[1].key
    private val deep = Catalog.picks[2].key

    private fun openModels() {
        tapDescription("Chats")
        tapDrawerModels()
        see("Suggested for this phone")
    }

    /** On the Models page: a suggestion by its name, its card's Get. The card closes and the page stays. */
    private fun getSuggested(name: String) = getFromModels(name)

    /** A row's ✕; past 300 MB arrived it asks first, and the answer is to cancel. */
    private fun cancelFromTheRow() {
        tapDescription("Cancel")
        compose.waitForIdle()
        if (compose.onAllNodesWithText("Cancel download").fetchSemanticsNodes().isNotEmpty()) tap("Cancel download")
    }

    @Test fun pauseResumeAndCancelADownload() {
        world.downloads.finish(swift) // Qwen3.5 0.8B is on the phone already: the screen has something to show
        openModels()
        getSuggested("Gemma 3 4B")
        see("Downloading")
        shot("1-downloading")

        world.downloads.advance(writer)
        tapDescription("Pause") // the ring's control
        see("Paused · ", substring = true) // the ring's line: "Paused · Q4_K_M"
        shot("2-paused")
        tapDescription("Resume")
        seeDescription("Pause")
        world.downloads.finish(writer)
        gone("Downloading")
        see("Gemma 3 4B")
        shot("3-done")

        // another download, then ✕: the row goes and nothing of it stays on the phone
        getSuggested("Qwen3.5 4B")
        see("Downloading")
        world.downloads.advance(deep)
        cancelFromTheRow()
        gone("Downloading")
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

        openModels()
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

        // the search is still on screen, the model now among the person's own, its row saying what the repository says
        seeTag("download_row")
        world.downloads.finish(HfModel.parse("$phi:Q8_0"))
        seeTag("library_model")
        assertEquals(listOf(HfModel.parse("$phi:Q8_0")), world.downloads.enqueued)
        shot("3-on-phone")
    }

    @Test fun chatWithAModelOnThePhone() {
        // the default model is Qwen3.5 0.8B, so a new chat would start on it: "Start a chat" starts one on Qwen3.5 4B
        onPhone(swift, deep)
        runBlocking { app.container.settings.pinDefault(swift.toString()) }
        openModels()
        compose.onNode(hasTestTag("library_model") and hasAnyDescendant(hasText("Qwen3.5 4B"))).performClick()
        tap("Start a chat")
        seeTag("composer")
        val named = hasText("Qwen3.5 4B", substring = true)
        compose.waitUntilAtLeastOneExists(hasTestTag("model_title") and (named or hasAnyDescendant(named)), WAIT)
        shot("1-a-new-chat-on-it")
        // the chat is made with its first message: opening one and leaving it keeps no empty chat
        assertEquals(
            emptyList<Any>(),
            runBlocking {
                app.container.chats
                    .chats()
                    .first()
            },
        )
    }
}
