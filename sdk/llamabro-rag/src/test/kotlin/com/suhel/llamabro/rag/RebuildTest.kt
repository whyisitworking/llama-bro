package com.suhel.llamabro.rag

import com.suhel.llamabro.Model
import com.suhel.llamabro.ModelSource
import com.suhel.llamabro.ModelTask
import com.suhel.llamabro.rag.IngestProgress.Chunked
import com.suhel.llamabro.rag.IngestProgress.Embedded
import com.suhel.llamabro.rag.IngestProgress.Saved
import com.suhel.llamabro.rag.store.LibraryStore
import com.suhel.llamabro.testing.FakeLlamaBro
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** [Library.contents], read without an embedder, and [Library.rebuild], for a library another embedder built. */
class RebuildTest {
    private val dir = "/data/library".toPath()
    private val fs = FakeFileSystem()
    private val fake = FakeLlamaBro()
    private val chunker = Chunker(maxTokens = 8, overlap = 0f)
    private val nomic = EmbeddingFormat("search_query: ", "search_document: ")

    private fun unit(i: Int, dims: Int = 4): FloatArray = FloatArray(dims) { if (it == i % dims) 1f else 0f }

    private suspend fun embedder(): Model =
        fake.llamaBro.loadModel(ModelSource.file("/e.gguf")) { task = ModelTask.Embedding() }

    private suspend fun open(model: Model, format: EmbeddingFormat = EmbeddingFormat.None, dims: Int = 4): Library {
        fake.embeddings(unit(0, dims))
        return Library.open(fs, dir, model, format, chunker)
    }

    // two paragraphs and one: three passages under the 8-token chunker
    private suspend fun Library.addTwo() {
        fake.embeddings(unit(1), unit(2))
        add(Document("a", "Handbook", "Para 00 text.\n\nPara 01 text.", "a.md")).toList()
        fake.embeddings(unit(3))
        add(Document("b", "Notes", "Para 02 text.")).toList()
    }

    @Test
    fun contentsListsTheDocumentsWithoutAnEmbedder() =
        runBlocking {
            assertNull(Library.contents(fs, dir))
            val library = open(embedder())
            library.addTwo()
            val built = library.identity
            library.close()

            val contents = Library.contents(fs, dir) ?: error("no contents")
            assertEquals(built, contents.builtBy)
            assertEquals(
                listOf(DocumentInfo("a", "Handbook", "a.md", 2), DocumentInfo("b", "Notes", null, 1)),
                contents.documents,
            )
        }

    @Test
    fun rebuildReembedsEveryPassageWithTheNewEmbedder() =
        runBlocking {
            val model = embedder()
            open(model).apply { addTwo() }.close()
            try {
                open(model, nomic)
                fail("another format opened the library")
            } catch (_: IndexMismatch) {
                // the case rebuild is for
            }

            fake.embeddings(unit(0, dims = 3)) // the new library's probe
            fake.embeddings(unit(0, 3), unit(1, 3)) // a's two passages
            fake.embeddings(unit(2, 3)) // b's
            val progress = Library.rebuild(fs, dir, model, nomic, chunker).toList()

            assertEquals(listOf(Chunked(3), Embedded(2, 3), Embedded(3, 3), Saved), progress)
            val batch = fake.embedRequests[fake.embedRequests.size - 2]
            assertTrue(batch, batch.contains("\"search_document: Para 00 text.\""))
            val contents = Library.contents(fs, dir) ?: error("no contents")
            assertEquals(3, contents.builtBy.dimensions)
            assertEquals(nomic, contents.builtBy.format)
            assertEquals(
                listOf(DocumentInfo("a", "Handbook", "a.md", 2), DocumentInfo("b", "Notes", null, 1)),
                contents.documents,
            )
            // the folder beside it is gone, and the library opens with the new embedder and finds by it
            assertEquals(listOf(dir), fs.list(dir.parent!!))
            val rebuilt = open(model, nomic, dims = 3)
            fake.embeddings(unit(2, 3))
            assertEquals("Para 02 text.", rebuilt.search("02", limit = 1).single().text)
        }

    @Test
    fun aRebuildCommittedButNotMovedIsFinishedByOpen() =
        runBlocking {
            val model = embedder()
            val old = open(model).apply { addTwo() }.also { it.close() }.identity
            fake.embeddings(unit(0, 3))
            fake.embeddings(unit(0, 3), unit(1, 3))
            fake.embeddings(unit(2, 3))
            Library.rebuild(fs, dir, model, nomic, chunker).toList()
            // a crash after the new library was written whole, before it took the old one's place
            fs.atomicMove(dir, dir.parent!! / "library.rebuilt")
            LibraryStore(fs, dir).open(old)

            val reopened = open(model, nomic, dims = 3)
            assertEquals(listOf("a", "b"), reopened.documents().map { it.id })
            assertEquals(listOf(dir), fs.list(dir.parent!!))
        }

    @Test
    fun anUnfinishedRebuildIsDropped() =
        runBlocking {
            val model = embedder()
            open(model).apply { addTwo() }.close()
            val partial = dir.parent!! / "library.rebuild"
            fs.createDirectories(partial)
            fs.write(partial / "manifest.json") { writeUtf8("{") }

            assertEquals(listOf("a", "b"), open(model).documents().map { it.id })
            assertFalse(fs.exists(partial))
        }
}
