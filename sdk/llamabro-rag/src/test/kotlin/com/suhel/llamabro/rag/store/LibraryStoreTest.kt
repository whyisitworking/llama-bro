package com.suhel.llamabro.rag.store

import com.suhel.llamabro.Normalization
import com.suhel.llamabro.Pooling
import com.suhel.llamabro.rag.Chunk
import com.suhel.llamabro.rag.EmbedderIdentity
import com.suhel.llamabro.rag.EmbeddingFormat
import com.suhel.llamabro.rag.IndexMismatch
import kotlinx.coroutines.runBlocking
import okio.FileSystem
import okio.IOException
import okio.Path
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.random.Random

class LibraryStoreTest {
    private val dims = 8
    private val identity =
        EmbedderIdentity(
            modelName = "bge-small-en-v1.5",
            fileBytes = 66_000_000,
            dimensions = dims,
            pooling = "cls",
            normalization = "euclidean",
            format = EmbeddingFormat.None,
        )
    private val dir: Path = "/library".toPath()
    private val fake = FakeFileSystem()
    private val random = Random(11)

    private fun unit(size: Int = dims): FloatArray {
        val v = FloatArray(size) { random.nextFloat() * 2 - 1 }
        val norm = sqrt(v.fold(0f) { s, x -> s + x * x })
        return FloatArray(size) { v[it] / norm }
    }

    private fun chunks(documentId: String, count: Int): List<Chunk> =
        List(
            count,
        ) { i -> Chunk(documentId, i, "$documentId text $i", i * 10, i * 10 + 9, listOf("H", "$documentId-$i")) }

    private fun store(fs: FileSystem = fake): LibraryStore =
        runBlocking { LibraryStore(fs, dir).apply { open(identity) } }

    private fun LibraryStore.add(documentId: String, rows: Int = 2) =
        runBlocking {
            commit(
                documentId,
                "Title $documentId",
                "file://$documentId",
                chunks(documentId, rows),
                List(rows) { unit() },
            )
        }

    private data class Snapshot(
        val documents: List<StoredDocument>,
        val rows: List<Pair<Chunk, Boolean>>,
        val nearest: List<Pair<Int, Float>>,
    )

    private val probe = Random(3).let { r -> FloatArray(dims) { r.nextFloat() - 0.5f } }

    private fun LibraryStore.snapshot(): Snapshot =
        Snapshot(
            documents,
            List(rowCount) { chunk(it) to isLive(it) },
            runBlocking { vectors.nearest(probe, rowCount + 1) },
        )

    @Test
    fun commitThenReopenReadsTheSame() {
        val store = store()
        store.add("a", rows = 3)
        store.add("b", rows = 2)
        val before = store.snapshot()

        val reopened = store()
        assertEquals(before, reopened.snapshot())
        assertEquals(listOf("a", "b"), reopened.documents.map { it.id })
        assertEquals(StoredDocument("b", "Title b", "file://b", firstRow = 3, rowCount = 2), reopened.documents[1])
        assertEquals(chunks("a", 3)[2], reopened.chunk(2))
        assertEquals(5L * dims * 2, fake.metadata(dir / "vectors.f16").size)
    }

    @Test
    fun anEmptyLibraryOpensAndReopens() {
        store()
        val reopened = store()
        assertEquals(0, reopened.rowCount)
        assertEquals(emptyList<Pair<Int, Float>>(), runBlocking { reopened.vectors.nearest(probe, 5) })
    }

    @Test
    fun crashBetweenWritesKeepsLastGoodIndex() {
        for (target in listOf("vectors.f16", "chunks.jsonl", "manifest.json.tmp", "manifest.json")) {
            val fs = FakeFileSystem()
            val crashing = CrashingFileSystem(fs)
            val store = runBlocking { LibraryStore(crashing, dir).apply { open(identity) } }
            store.add("a", rows = 3)
            val before = store.snapshot()

            crashing.crashAt = target
            try {
                store.add("b", rows = 4)
                fail("the commit should have crashed at $target")
            } catch (expected: IOException) {
                assertTrue(crashing.crashed)
            }
            // which state the files hold is known only by reading them again
            try {
                store.add("d")
                fail("a store whose write failed wrote again")
            } catch (expected: IllegalStateException) {
            }

            val reopened = store(fs)
            assertEquals("after a crash at $target", before, reopened.snapshot())
            assertFalse(fs.list(dir).any { it.name.endsWith(".tmp") })

            // the next commit cuts off what the crash left half-written
            reopened.add("c", rows = 2)
            val after = store(fs)
            assertEquals(listOf("a", "c"), after.documents.map { it.id })
            assertEquals(chunks("c", 2), listOf(after.chunk(3), after.chunk(4)))
            assertEquals(5L * dims * 2, fs.metadata(dir / "vectors.f16").size)
            assertEquals(5, fs.read(dir / "chunks.jsonl") { readUtf8() }.lines().count { it.isNotEmpty() })
        }
    }

    @Test
    fun crashDuringRemoveKeepsTheDocument() {
        for (target in listOf("deleted.tmp", "deleted", "manifest.json.tmp", "manifest.json")) {
            val fs = FakeFileSystem()
            val crashing = CrashingFileSystem(fs)
            val store = runBlocking { LibraryStore(crashing, dir).apply { open(identity) } }
            // four of eighteen rows stay under a quarter: no compaction
            ('a'..'i').forEach { store.add(it.toString()) }
            store.runRemove("a")
            val before = store.snapshot()

            crashing.crashAt = target
            try {
                store.runRemove("b")
                fail("the remove should have crashed at $target")
            } catch (expected: IOException) {
                assertTrue(crashing.crashed)
            }
            assertEquals("after a crash at $target", before, store(fs).snapshot())
        }
    }

    @Test
    fun crashDuringCompactionKeepsAnIndex() {
        // before the new manifest is in place the old index stands; after it, opening finishes the compaction
        val targets = listOf("vectors.f16.tmp", "chunks.jsonl.tmp", "manifest.next.tmp", "manifest.next")
        val rollsForward = listOf("vectors.f16", "chunks.jsonl", "manifest.json")
        for (target in targets + rollsForward) {
            val fs = FakeFileSystem()
            val crashing = CrashingFileSystem(fs)
            val store = runBlocking { LibraryStore(crashing, dir).apply { open(identity) } }
            listOf("a", "b", "c", "d").forEach { store.add(it) }
            store.runRemove("a")
            val removedA = store.snapshot()
            val liveChunks = removedA.rows.filter { it.second }.map { it.first }

            crashing.crashAt = target
            try {
                store.runRemove("b") // passes a quarter: compacts
                fail("the compaction should have crashed at $target")
            } catch (expected: IOException) {
                assertTrue(crashing.crashed)
            }

            val reopened = store(fs)
            assertFalse(fs.list(dir).any { it.name.endsWith(".tmp") || it.name.startsWith("manifest.next") })
            if (target in rollsForward) {
                assertEquals("after a crash at $target", listOf("c", "d"), reopened.documents.map { it.id })
                assertEquals(4, reopened.rowCount)
                assertEquals(liveChunks.drop(2), List(4) { reopened.chunk(it) })
            } else {
                assertEquals("after a crash at $target", removedA, reopened.snapshot())
            }
            assertEquals(reopened.snapshot(), store(fs).snapshot())
        }
    }

    @Test
    fun strayTempFilesAreDeletedOnOpen() {
        store().add("a")
        fake.write(dir / "vectors.f16.tmp") { writeUtf8("junk") }
        fake.write(dir / "manifest.json.tmp") { writeUtf8("junk") }
        val reopened = store()
        assertEquals(listOf("a"), reopened.documents.map { it.id })
        assertFalse(fake.list(dir).any { it.name.endsWith(".tmp") })
    }

    @Test
    fun otherEmbedderIsIndexMismatch() {
        store().add("a")
        val others =
            listOf(
                identity.copy(modelName = "nomic-embed-text-v1.5"),
                identity.copy(fileBytes = 34_000_000),
                identity.copy(dimensions = 384),
                identity.copy(pooling = "mean"),
                identity.copy(normalization = "none"),
                identity.copy(format = EmbeddingFormat("search_query: ", "search_document: ")),
            )
        for (other in others) {
            assertNotEquals(identity, other)
            try {
                runBlocking { LibraryStore(fake, dir).open(other) }
                fail("$other opened an index built by $identity")
            } catch (mismatch: IndexMismatch) {
                assertEquals(identity, mismatch.built)
                assertEquals(other, mismatch.wanted)
                assertTrue(mismatch.message.orEmpty(), mismatch.message.orEmpty().isNotBlank())
            }
        }
        // the refusal changed nothing
        assertEquals(listOf("a"), store().documents.map { it.id })
    }

    @Test
    fun removeTombstonesRows() {
        val store = store()
        listOf("a", "b", "c", "d").forEach { store.add(it) }
        store.runRemove("b")

        // two of eight rows is a quarter, not past it: no compaction yet
        assertEquals(8, store.rowCount)
        assertEquals(listOf("a", "c", "d"), store.documents.map { it.id })
        assertEquals(listOf(true, true, false, false, true, true, true, true), List(8) { store.isLive(it) })
        val found = runBlocking { store.vectors.nearest(probe, 10) }.map { it.first }
        assertEquals(listOf(0, 1, 4, 5, 6, 7), found.sorted())
        assertEquals(store.snapshot(), store().snapshot())

        store.runRemove("nothing")
        assertEquals(store.snapshot(), store().snapshot())
    }

    @Test
    fun compactsPastAQuarter() {
        val store = store()
        listOf("a", "b", "c", "d").forEach { store.add(it) }
        val kept = (4 until 8).map { store.chunk(it) }
        val keptNearest = runBlocking { store.vectors.nearest(probe, 8) }.filter { it.first >= 4 }
        store.runRemove("a")
        store.runRemove("b")

        assertEquals(4, store.rowCount)
        assertEquals(List(4) { true }, List(4) { store.isLive(it) })
        assertEquals(kept, List(4) { store.chunk(it) })
        assertEquals(
            listOf(StoredDocument("c", "Title c", "file://c", 0, 2), StoredDocument("d", "Title d", "file://d", 2, 2)),
            store.documents,
        )
        assertEquals(keptNearest.map { it.first - 4 to it.second }, runBlocking { store.vectors.nearest(probe, 8) })
        assertEquals(4L * dims * 2, fake.metadata(dir / "vectors.f16").size)
        assertEquals(store.snapshot(), store().snapshot())

        store.add("e")
        assertEquals(listOf("c", "d", "e"), store().documents.map { it.id })
    }

    @Test
    fun openAndCompactionNeverHoldTheIndexTwice() {
        val segment = FlatVectors.SEGMENT_ROWS
        val store = store()
        // 2.5 segments in five documents
        repeat(5) { d -> store.add("d$d", rows = segment / 2) }
        val reopened = store()
        val loaded = reopened.vectors as FlatVectors
        // open reads straight into three segments: no array of the whole index beside them
        assertEquals(3, loaded.segmentsAllocated)
        assertEquals(3L * segment * dims, loaded.capacity)

        val before = reopened.snapshot()
        reopened.runRemove("d0")
        reopened.runRemove("d1") // two of five documents: past a quarter, compacts
        assertEquals(3 * segment / 2, reopened.rowCount)
        // compaction moved rows down in place, allocating nothing, and let the emptied segment go
        assertEquals(3, loaded.segmentsAllocated)
        assertEquals(2L * segment * dims, loaded.capacity)
        val keptRows = before.rows.drop(segment)
        assertEquals(keptRows.map { it.first }, List(reopened.rowCount) { reopened.chunk(it) })
        val keptNearest = before.nearest.filter { it.first >= segment }.map { it.first - segment to it.second }
        assertEquals(keptNearest, runBlocking { reopened.vectors.nearest(probe, reopened.rowCount + 1) })
        assertEquals(reopened.snapshot(), store().snapshot())
    }

    @Test
    fun recommitReplacesTheDocument() {
        val store = store()
        store.add("a")
        store.add("b")
        store.add("a", rows = 3)
        assertEquals(listOf("b", "a"), store.documents.map { it.id })
        assertEquals(
            chunks("a", 3),
            store.documents.last().let { d ->
                List(d.rowCount) { store.chunk(d.firstRow + it) }
            },
        )
        assertEquals(store.snapshot(), store().snapshot())
    }

    @Test
    fun commitChecksItsInput() {
        val store = store()
        for (bad in listOf(listOf(unit()), listOf(unit(), unit(dims + 1)))) {
            try {
                runBlocking { store.commit("a", "A", null, chunks("a", 2), bad) }
                fail("committed $bad")
            } catch (expected: IllegalArgumentException) {
            }
        }
        assertEquals(0, store.rowCount)
    }

    @Test
    fun nearestIsExactCosine() {
        val store = store()
        val all = mutableListOf<FloatArray>()
        repeat(10) { d ->
            val vectors = List(5) { unit() }
            all += vectors
            runBlocking { store.commit("d$d", "D$d", null, chunks("d$d", 5), vectors) }
        }
        val query = unit()
        val found = runBlocking { store.vectors.nearest(query, 7) }

        val halves = all.map { v -> FloatArray(dims) { Float16.toFloat(Float16.fromFloat(v[it])) } }
        val expected =
            halves
                .mapIndexed { row, v -> row to v.indices.fold(0f) { s, i -> s + v[i] * query[i] } }
                .sortedWith(compareByDescending<Pair<Int, Float>> { it.second }.thenBy { it.first })
                .take(7)
        assertEquals(expected, found)
        for ((row, score) in found) {
            val exact = all[row].indices.fold(0.0) { s, i -> s + all[row][i].toDouble() * query[i] }
            assertTrue(abs(exact - score) < 2e-3)
        }
    }

    @Test
    fun nearestBreaksTiesByLowerRowAndSkipsTombstones() {
        val store = store()
        val same = unit()
        runBlocking {
            store.commit("a", "A", null, chunks("a", 2), listOf(same, same))
            store.commit("b", "B", null, chunks("b", 2), listOf(same, same))
            store.commit("c", "C", null, chunks("c", 4), List(4) { unit() })
            store.remove("a")
        }
        val found = runBlocking { store.vectors.nearest(same, 2) }
        assertEquals(listOf(2, 3), found.map { it.first })
        assertEquals(emptyList<Pair<Int, Float>>(), runBlocking { store.vectors.nearest(same, 0) })
    }

    @Test
    fun identityReadsTheEmbedder() {
        val built =
            embedderIdentity(
                name = "bge-small-en-v1.5",
                description = "bert 33M F16",
                sizeBytes = 66_000_000,
                dimensions = 384,
                pooling = Pooling.ModelDefault,
                modelPooling = "2",
                normalization = Normalization.Euclidean,
                format = EmbeddingFormat.None,
            )
        assertEquals(
            EmbedderIdentity("bge-small-en-v1.5", 66_000_000, 384, "cls", "euclidean", EmbeddingFormat.None),
            built,
        )
        val chosen =
            embedderIdentity(
                null,
                "bert 33M F16",
                1,
                2,
                Pooling.Mean,
                "2",
                Normalization.PNorm(3),
                EmbeddingFormat.None,
            )
        assertEquals(EmbedderIdentity("bert 33M F16", 1, 2, "mean", "p3", EmbeddingFormat.None), chosen)
    }

    private fun LibraryStore.runRemove(documentId: String) = runBlocking { remove(documentId) }
}
