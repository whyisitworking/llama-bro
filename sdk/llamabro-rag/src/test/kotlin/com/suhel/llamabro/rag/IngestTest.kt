package com.suhel.llamabro.rag

import com.suhel.llamabro.Model
import com.suhel.llamabro.ModelSource
import com.suhel.llamabro.ModelTask
import com.suhel.llamabro.rag.IngestProgress.Chunked
import com.suhel.llamabro.rag.IngestProgress.Embedded
import com.suhel.llamabro.rag.IngestProgress.Saved
import com.suhel.llamabro.rag.store.CrashingFileSystem
import com.suhel.llamabro.rag.store.LibraryStore
import com.suhel.llamabro.testing.FakeLlamaBro
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okio.FileSystem
import okio.IOException
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class IngestTest {
    private val dims = 4
    private val dir = "/library".toPath()
    private val fs = FakeFileSystem()
    private val fake = FakeLlamaBro()

    // a unit vector per row, distinct for each i
    private fun unit(i: Int): FloatArray = FloatArray(dims) { if (it == i % dims) 1f else 0f }

    private fun vectors(count: Int, from: Int = 0) = Array(count) { unit(from + it) }

    // paragraphs of 15 characters with their gap, five estimated tokens: two never share an 8-token chunk
    private fun document(id: String, paragraphs: Int, title: String = "T$id") =
        Document(id, title, (0 until paragraphs).joinToString("\n\n") { "Para %02d text.".format(it) })

    private val smallChunks = Chunker(maxTokens = 8, overlap = 0f)

    private suspend fun embedder(): Model =
        fake.llamaBro.loadModel(ModelSource.file("/e.gguf")) { task = ModelTask.Embedding() }

    private suspend fun open(
        model: Model,
        format: EmbeddingFormat = EmbeddingFormat.None,
        chunker: Chunker = smallChunks,
        files: FileSystem = fs,
    ): Library {
        fake.embeddings(unit(0)) // open embeds one text to learn the vectors' length
        return Library.open(files, dir, model, format, chunker)
    }

    private suspend fun storeOf(library: Library): LibraryStore = LibraryStore(fs, dir).apply { open(library.identity) }

    @Test
    fun addEmbedsAndSaves() =
        runBlocking {
            val model = embedder()
            val library = open(model)
            val doc = document("a", 3, title = "Handbook").copy(source = "file://a")
            fake.embeddings(*vectors(3, from = 1))
            assertEquals(listOf(Chunked(3), Embedded(3, 3), Saved), library.add(doc).toList())
            assertEquals(listOf(DocumentInfo("a", "Handbook", "file://a", 3)), library.documents())
            library.close()

            assertEquals(listOf(DocumentInfo("a", "Handbook", "file://a", 3)), open(model).documents())
            val store = storeOf(library)
            List(3) { store.chunk(it) }.forEachIndexed { i, chunk ->
                assertEquals(i, chunk.index)
                assertEquals("Para %02d text.".format(i), chunk.text)
                assertEquals(chunk.text, doc.text.substring(chunk.start, chunk.end))
                assertEquals(listOf(i to 1f), store.vectors.nearest(unit(i + 1), 1))
            }
        }

    @Test
    fun progressCountsBatches() =
        runBlocking {
            val library = open(embedder())
            repeat(3) { fake.embeddings(*vectors(if (it < 2) 16 else 8)) }
            val progress = library.add(document("a", 40)).toList()
            assertEquals(listOf(Chunked(40), Embedded(16, 40), Embedded(32, 40), Embedded(40, 40), Saved), progress)
            // the probe, then three batches of at most 16
            assertEquals(4, fake.embedRequests.size)
            assertEquals(listOf(DocumentInfo("a", "Ta", null, 40)), library.documents())
        }

    @Test
    fun documentPrefixIsPrepended() =
        runBlocking {
            val counted = mutableListOf<String>()
            // the caller's chunker has no prefix: the library gives it the format's, so the prefix is counted
            val chunker =
                Chunker(maxTokens = 8, overlap = 0f, counter = { text ->
                    counted += text
                    (text.length + 2) / 3
                })
            val library = open(embedder(), EmbeddingFormat("q: ", "d: "), chunker)
            fake.embeddings(*vectors(2))
            library.add(document("a", 2)).toList()

            val batch = fake.embedRequests.last()
            assertTrue(batch, batch.contains("\"d: Para 00 text.\"") && batch.contains("\"d: Para 01 text.\""))
            assertTrue("$counted", counted.isNotEmpty() && counted.all { it.startsWith("d: ") })
            val store = storeOf(library)
            assertEquals(listOf("Para 00 text.", "Para 01 text."), List(2) { store.chunk(it).text })
            assertTrue(fs.read(dir / "chunks.jsonl") { readUtf8() }.lines().none { "d: " in it })
        }

    @Test
    fun inputTooLongSplitsAndRetries() =
        runBlocking {
            // 13 + 2 estimated tokens: two chunks under a 14-token chunker
            val text = "First sentence here. Second one here.\n\nTail."
            val library = open(embedder(), chunker = Chunker(maxTokens = 14, overlap = 0f))
            fake.inputTooLong(tokens = 20, limit = 14) // the batch; the limit is the chunker's already
            fake.inputTooLong(tokens = 15, limit = 14) // the first chunk alone
            fake.embeddings(unit(1)) // its first half
            fake.embeddings(unit(2)) // its second half
            fake.embeddings(unit(3)) // "Tail."
            val progress = library.add(Document("a", "A", text)).toList()

            assertEquals(listOf(Chunked(2), Embedded(3, 3), Saved), progress)
            val store = storeOf(library)
            val chunks = List(store.rowCount) { store.chunk(it) }
            assertEquals(listOf("First sentence here.", "Second one here.", "Tail."), chunks.map { it.text })
            assertEquals(listOf(0, 1, 2), chunks.map { it.index })
            chunks.forEach { assertEquals(it.text, text.substring(it.start, it.end)) }
            assertEquals(listOf(1 to 1f), store.vectors.nearest(unit(2), 1))
        }

    @Test
    fun inputTooLongLowersTheLimitForTheRest() =
        runBlocking {
            val text = "First sentence here. Second one here.\n\nTail."
            val library = open(embedder(), chunker = Chunker(maxTokens = 14, overlap = 0f))
            // the batch is 8 tokens: every chunk left is cut again to fit it, and sent once
            fake.inputTooLong(tokens = 13, limit = 8)
            fake.embeddings(*vectors(3, from = 1))
            val progress = library.add(Document("a", "A", text)).toList()

            assertEquals(listOf(Chunked(2), Embedded(3, 3), Saved), progress)
            val store = storeOf(library)
            val chunks = List(store.rowCount) { store.chunk(it) }
            assertEquals(listOf("First sentence here.", "Second one here.", "Tail."), chunks.map { it.text })
            chunks.forEach { assertEquals(it.text, text.substring(it.start, it.end)) }
            assertEquals(3, fake.embedRequests.size)
        }

    @Test
    fun cancelMidDocumentCommitsNothing() =
        runBlocking {
            val model = embedder()
            val library = open(model)
            fake.embeddings(*vectors(2))
            library.add(document("a", 2)).toList()

            // stop after the first batch: neither the new "b" nor the second "a" is committed
            for (id in listOf("b", "a")) {
                fake.embeddings(*vectors(16))
                val seen = library.add(document(id, 40)).take(2).toList()
                assertEquals(listOf(Chunked(40), Embedded(16, 40)), seen)
            }
            assertEquals(listOf(DocumentInfo("a", "Ta", null, 2)), library.documents())
            library.close()
            val store = storeOf(library)
            assertEquals(2, store.rowCount)
            assertEquals(listOf(DocumentInfo("a", "Ta", null, 2)), open(model).documents())
        }

    @Test
    fun addingAnExistingIdReplacesIt() =
        runBlocking {
            val model = embedder()
            val library = open(model)
            fake.embeddings(*vectors(3))
            library.add(document("a", 3)).toList()
            fake.embeddings(*vectors(2))
            library.add(document("b", 2)).toList()
            fake.embeddings(*vectors(2, from = 1))
            library.add(document("a", 2, title = "Second")).toList()

            val expected = listOf(DocumentInfo("b", "Tb", null, 2), DocumentInfo("a", "Second", null, 2))
            assertEquals(expected, library.documents())
            assertEquals(expected, open(model).documents())
            val store = storeOf(library)
            assertEquals(4, (0 until store.rowCount).count { store.isLive(it) })
        }

    @Test
    fun removeDropsIt() =
        runBlocking {
            val model = embedder()
            val library = open(model)
            fake.embeddings(*vectors(2))
            library.add(document("a", 2)).toList()
            fake.embeddings(*vectors(2))
            library.add(document("b", 2)).toList()
            library.remove("a")
            library.remove("missing")

            assertEquals(listOf(DocumentInfo("b", "Tb", null, 2)), library.documents())
            assertEquals(listOf(DocumentInfo("b", "Tb", null, 2)), open(model).documents())
        }

    @Test
    fun aFailedWriteFailsItsDocumentAndTheLibraryCarriesOn() =
        runBlocking {
            val crashing = CrashingFileSystem(fs)
            val library = open(embedder(), files = crashing)
            fake.embeddings(*vectors(2))
            library.add(document("a", 2)).toList()

            crashing.crashAt = "manifest.json"
            fake.embeddings(*vectors(2))
            try {
                library.add(document("b", 2)).toList()
                fail("the commit should have failed")
            } catch (expected: IOException) {
            }
            crashing.recover()
            // the store is opened again on the next call; "b" was never committed
            assertEquals(listOf(DocumentInfo("a", "Ta", null, 2)), library.documents())
            fake.embeddings(*vectors(2))
            library.add(document("c", 2)).toList()
            assertEquals(listOf("a", "c"), library.documents().map { it.id })
        }

    @Test
    fun compactionFailureAfterCommitStillSaves() =
        runBlocking {
            val crashing = CrashingFileSystem(fs)
            val model = embedder()
            val library = open(model, files = crashing)
            for (id in listOf("a", "b")) {
                fake.embeddings(*vectors(2))
                library.add(document(id, 2)).toList()
            }

            // replacing "a" tombstones two of six rows, past a quarter: the commit is followed by a compaction
            crashing.crashAt = "manifest.next"
            fake.embeddings(*vectors(2, from = 1))
            val progress = library.add(document("a", 2, title = "Second")).toList()
            assertEquals(Saved, progress.last())
            assertTrue(crashing.crashed)
            crashing.recover()

            val expected = listOf(DocumentInfo("b", "Tb", null, 2), DocumentInfo("a", "Second", null, 2))
            assertEquals(expected, library.documents())
            assertEquals(expected, open(model).documents())
            // the compaction didn't happen: the old rows are still on disk
            assertEquals(6, storeOf(library).rowCount)

            // the next write, still past a quarter, compacts
            fake.embeddings(unit(3))
            library.add(document("c", 1)).toList()
            val store = storeOf(library)
            assertEquals(5, store.rowCount)
            assertTrue((0 until store.rowCount).all { store.isLive(it) })
            assertEquals(listOf("b", "a", "c"), library.documents().map { it.id })
        }

    @Test
    fun anotherEmbedderIsIndexMismatch() =
        runBlocking {
            val model = embedder()
            open(model).close()
            try {
                open(model, EmbeddingFormat("q: ", "d: "))
                fail("opened a library built with another format")
            } catch (expected: IndexMismatch) {
                assertEquals(EmbeddingFormat.None, expected.built.format)
            }
        }
}
