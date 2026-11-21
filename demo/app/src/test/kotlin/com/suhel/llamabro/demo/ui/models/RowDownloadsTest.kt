package com.suhel.llamabro.demo.ui.models

import com.suhel.llamabro.demo.models.Catalog
import com.suhel.llamabro.demo.models.DownloadStatus
import com.suhel.llamabro.hub.HfModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** A row's ring and ✕ (docs/design/models-page.md §3): they act on the repo's download; the ✕ asks past 300 MB. */
@OptIn(ExperimentalCoroutinesApi::class)
class RowDownloadsTest {
    private val library = FakeModelLibrary()
    private val deep = Catalog.suggested[2].key
    private val suggestion = ModelEntry.of(Catalog.suggested[2])
    private val problems = mutableListOf<String>()

    private fun kotlinx.coroutines.test.TestScope.downloads() =
        RowDownloads(library, backgroundScope, { library.queue.value }) { problems += it }

    @Test fun theRingPausesADownloadAndResumesAPausedOrStoppedOne() =
        runTest {
            library.queue.value = listOf(deep)
            val downloads = downloads()
            library.status(deep).value = DownloadStatus.Downloading(100, 1_000, 10, 90)
            downloads.toggle(suggestion) // the suggestion's row acts on its repo's download
            assertEquals(listOf(deep), library.paused)
            library.status(deep).value = DownloadStatus.Partial(100, 1_000)
            downloads.toggle(suggestion)
            assertEquals(listOf(deep), library.got.map { it.first })
        }

    @Test fun aRowWithNoDownloadDoesNothing() =
        runTest {
            val downloads = downloads()
            downloads.toggle(suggestion)
            downloads.cancel(suggestion)
            assertEquals(emptyList<Any>(), library.paused + library.cancelled)
        }

    @Test fun theCrossCancelsAtOnceUnderThreeHundredMegabytes() =
        runTest {
            library.queue.value = listOf(deep)
            library.status(deep).value = DownloadStatus.Downloading(299_000_000, 3_400_000_000, 10, 90)
            val downloads = downloads()
            downloads.cancel(ModelEntry.downloading(deep, null))
            assertEquals(listOf(deep), library.cancelled)
            assertNull(downloads.question.value)
        }

    @Test fun theCrossAsksFirstPastThreeHundredMegabytes() =
        runTest {
            library.queue.value = listOf(deep)
            library.status(deep).value = DownloadStatus.Partial(310_000_000, 3_400_000_000)
            val downloads = downloads()
            downloads.cancel(ModelEntry.downloading(deep, null))
            assertEquals(emptyList<Any>(), library.cancelled)
            assertEquals("0.3 of 3.4 GB has arrived. Cancelling deletes it.", downloads.question.value?.text)
            downloads.keep()
            assertNull(downloads.question.value)
            downloads.cancel(ModelEntry.downloading(deep, null))
            downloads.confirm()
            assertEquals(listOf(deep), library.cancelled)
            assertNull(downloads.question.value)
        }

    /** Past 300 MB the ✕ asks whether or not a total is known; with none it says what arrived. */
    @Test fun theCrossAsksPastThreeHundredMegabytesWithNoTotalKnown() =
        runTest {
            val foo = HfModel.parse("someone/Foo-7B-GGUF:Q4_K_M")
            library.queue.value = listOf(foo)
            library.status(foo).value = DownloadStatus.Partial(420_000_000, 0)
            val downloads = downloads()
            downloads.cancel(ModelEntry.downloading(foo, null))
            assertEquals(emptyList<Any>(), library.cancelled)
            assertEquals("420 MB has arrived. Cancelling deletes it.", downloads.question.value?.text)
        }

    @Test fun aCancelThatCantStopTheWorkSaysSo() =
        runTest {
            library.cancels = false
            val downloads = downloads()
            downloads.cancel(deep)
            runCurrent()
            assertEquals(listOf("Couldn't cancel the download of Qwen3.5 4B. Try again."), problems)
        }
}
