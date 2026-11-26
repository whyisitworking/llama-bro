package com.suhel.llamabro.demo.ui.library

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.suhel.llamabro.demo.library.EmbedderDoesNotFit
import com.suhel.llamabro.demo.library.EmbedderFit
import com.suhel.llamabro.demo.library.ImportRefused
import com.suhel.llamabro.demo.library.Mismatch
import com.suhel.llamabro.demo.ui.keepAlive
import com.suhel.llamabro.demo.ui.shutDown
import com.suhel.llamabro.rag.DocumentInfo
import com.suhel.llamabro.rag.IngestProgress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** The Library page's state over a fake library, and what its buttons do. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class LibraryViewModelTest {
    private val main = UnconfinedTestDispatcher()
    private val background = CoroutineScope(SupervisorJob() + main)
    private val viewModels = mutableListOf<ViewModel>()

    @Before fun setUp() = Dispatchers.setMain(main)

    @After fun tearDown() {
        background.cancel()
        shutDown(main, viewModels)
    }

    private class FakeLibrary : LibraryControl {
        override val documents = MutableStateFlow<List<DocumentInfo>?>(emptyList())
        override val mismatch = MutableStateFlow<Mismatch?>(null)
        override val embedder = MutableStateFlow<EmbedderDownload>(EmbedderDownload.OnPhone)
        var fit: EmbedderFit = EmbedderFit.Fits
        var progress = Channel<IngestProgress>(Channel.UNLIMITED)
        var importing: Flow<IngestProgress> = progress.receiveAsFlow()
        val imported = mutableListOf<Uri>()
        val removed = mutableListOf<String>()
        var rebuilds = 0
        var gets = 0

        override suspend fun fit(): EmbedderFit = fit

        override fun getEmbedder() {
            gets++
        }

        override fun import(uri: Uri): Flow<IngestProgress> {
            imported += uri
            return importing
        }

        override suspend fun remove(id: String) {
            removed += id
            documents.value = documents.value.orEmpty().filter { it.id != id }
        }

        override fun rebuild(): Flow<IngestProgress> {
            rebuilds++
            return progress.receiveAsFlow()
        }
    }

    private val library = FakeLibrary()
    private val notes = Uri.parse("content://files/notes.md")

    private fun viewModel() = LibraryViewModel(library, EMBEDDER, EMBEDDER_BYTES, background).also(viewModels::add)

    private fun LibraryViewModel.page(): LibraryPage = page.keepAlive(background).value ?: error("no page yet")

    @Test fun emptyLibraryCanImport() {
        val page = viewModel().page()
        assertEquals(EmbedderLine.Ready(EMBEDDER), page.embedder)
        assertTrue(page.documents.isEmpty())
        assertTrue(page.canImport)
    }

    @Test fun importShowsProgressThenListsTheDocument() {
        val vm = viewModel()
        vm.import(notes)
        assertEquals(listOf(notes), library.imported)
        assertEquals(WorkLine("Adding a document", null), vm.page().work)
        assertFalse("one import at a time", vm.page().canImport)

        runBlocking { library.progress.send(IngestProgress.Chunked(4)) }
        assertEquals(WorkLine("Adding a document: 0 of 4 passages", 0f), vm.page().work)
        runBlocking { library.progress.send(IngestProgress.Embedded(3, 4)) }
        assertEquals(WorkLine("Adding a document: 3 of 4 passages", 0.75f), vm.page().work)

        library.documents.value = listOf(DocumentInfo("n", "Trip notes", "Trip notes.md", 4))
        runBlocking { library.progress.send(IngestProgress.Saved) }
        library.progress.close()
        val page = vm.page()
        assertNull(page.work)
        assertEquals(listOf(DocumentRow("n", "Trip notes", "4 passages · Trip notes.md")), page.documents)
        assertTrue(page.canImport)
    }

    @Test fun removeDropsIt() {
        library.documents.value =
            listOf(DocumentInfo("a", "Handbook", "a.md", 1), DocumentInfo("b", "Notes", null, 12))
        val vm = viewModel()
        assertEquals(listOf("1 passage · a.md", "12 passages"), vm.page().documents.map { it.detail })
        vm.remove("a")
        assertEquals(listOf("a"), library.removed)
        assertEquals(listOf("b"), vm.page().documents.map { it.id })
    }

    @Test fun mismatchOffersRebuild() {
        library.documents.value = listOf(DocumentInfo("a", "Handbook", "a.md", 2))
        library.mismatch.value = Mismatch(built = "bge-small-en-v1.5-f16", wanted = EMBEDDER)
        val vm = viewModel()
        val mismatch = vm.page().mismatch ?: error("no mismatch line")
        assertEquals("Rebuild with BGE Small", mismatch.action)
        assertEquals(
            "This library was indexed by bge-small-en-v1.5-f16, so BGE Small can't search it. Rebuilding " +
                "indexes its 1 document again; nothing is deleted.",
            mismatch.text,
        )
        assertFalse("nothing goes into a library that can't be searched", vm.page().canImport)

        vm.rebuild()
        assertEquals(1, library.rebuilds)
        runBlocking { library.progress.send(IngestProgress.Chunked(2)) }
        assertEquals(WorkLine("Rebuilding the library: 0 of 2 passages", 0f), vm.page().work)
        library.mismatch.value = null
        runBlocking { library.progress.send(IngestProgress.Saved) }
        assertNull(vm.page().work)
        assertNull(vm.page().mismatch)
    }

    @Test fun doesNotFitSaysSo() {
        library.fit = EmbedderFit.DoesNotFit("Gemma 3 4B", needBytes = 600_000_000, budgetBytes = 250_000_000)
        val page = viewModel().page()
        assertEquals(
            EmbedderLine.DoesNotFit(
                "BGE Small doesn't fit beside Gemma 3 4B: it needs 600 MB, and 250 MB is free.",
            ),
            page.embedder,
        )
        assertFalse(page.canImport)
    }

    @Test fun anImportThatFindsNoRoomSaysSo() {
        library.importing = flow { throw EmbedderDoesNotFit(EmbedderFit.DoesNotFit(null, 600_000_000, null)) }
        val vm = viewModel()
        vm.import(notes)
        assertEquals(
            EmbedderLine.DoesNotFit("BGE Small doesn't fit in the memory this phone has free: it needs 600 MB."),
            vm.page().embedder,
        )
        assertNull(vm.page().work)
    }

    @Test fun refusedImportSaysWhy() {
        library.importing =
            flow { throw ImportRefused("Lease.pdf is a PDF: only text and Markdown files can be added for now.") }
        val vm = viewModel()
        vm.import(notes)
        val page = vm.page()
        assertEquals("Lease.pdf is a PDF: only text and Markdown files can be added for now.", page.notice)
        assertNull(page.work)
        assertTrue(page.canImport)
        vm.dismissNotice()
        assertNull(vm.page().notice)
    }

    @Test fun aFailedImportSaysWhy() {
        library.importing = flow { throw java.io.IOException("No space left on device") }
        val vm = viewModel()
        vm.import(notes)
        assertEquals("Couldn't add the document: No space left on device.", vm.page().notice)
    }

    @Test fun missingEmbedderOffersToGetIt() {
        library.embedder.value = EmbedderDownload.Absent
        val vm = viewModel()
        assertEquals(EmbedderLine.Missing(EMBEDDER, "36 MB"), vm.page().embedder)
        assertFalse(vm.page().canImport)
        vm.getEmbedder()
        assertEquals(1, library.gets)

        library.embedder.value = EmbedderDownload.Downloading(0.5f)
        assertEquals(EmbedderLine.Downloading(EMBEDDER, 0.5f), vm.page().embedder)
        library.embedder.value = EmbedderDownload.OnPhone
        assertEquals(EmbedderLine.Ready(EMBEDDER), vm.page().embedder)
    }

    private companion object {
        const val EMBEDDER = "BGE Small"
        const val EMBEDDER_BYTES = 36_685_152L
    }
}
