package com.suhel.llamabro.rag

import com.suhel.llamabro.Model
import com.suhel.llamabro.ModelSource
import com.suhel.llamabro.ModelTask
import com.suhel.llamabro.testing.FakeLlamaBro
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchTest {
    private val dir = "/library".toPath()
    private val fs = FakeFileSystem()
    private val fake = FakeLlamaBro()

    private fun unit(i: Int): FloatArray = FloatArray(DIMS) { if (it == i % DIMS) 1f else 0f }

    // a unit vector whose cosine with unit(0) falls as i grows
    private fun tilted(i: Int): FloatArray {
        val norm = kotlin.math.sqrt(1f + (i * 0.1f) * (i * 0.1f))
        return floatArrayOf(1f / norm, i * 0.1f / norm, 0f, 0f)
    }

    // one chunk per paragraph: two of these never share an 8-token chunk
    private val chunker = Chunker(maxTokens = 8, overlap = 0f)

    private suspend fun embedder(): Model =
        fake.llamaBro.loadModel(ModelSource.file("/e.gguf")) { task = ModelTask.Embedding() }

    private suspend fun reranker(): Model =
        fake.llamaBro.loadModel(ModelSource.file("/r.gguf")) { task = ModelTask.Rerank }

    private suspend fun open(model: Model, format: EmbeddingFormat = EmbeddingFormat.None): Library {
        fake.embeddings(unit(0)) // open embeds one text to learn the vectors' length
        return Library.open(fs, dir, model, format, chunker)
    }

    private suspend fun Library.addDocument(id: String, paragraphs: List<String>, vararg vectors: FloatArray) {
        vectors.toList().chunked(BATCH).forEach { fake.embeddings(*it.toTypedArray()) }
        val progress = add(Document(id, "Title $id", paragraphs.joinToString("\n\n"))).toList()
        assertEquals(paragraphs.size, (progress.first() as IngestProgress.Chunked).chunks)
    }

    @Test
    fun hybridFindsANumberDenseMisses() =
        runBlocking {
            val library = open(embedder())
            // the query's vector is unit(0): dense ranks the rows 0, 1, 2; only row 2 holds the number
            library.addDocument(
                "a",
                listOf("Leave policy text.", "Travel policy text.", "Door code 4417."),
                unit(0),
                unit(1),
                unit(2),
            )
            fake.embeddings(unit(0))
            val hits = library.search("code 4417", limit = 1)
            assertEquals(listOf("Door code 4417."), hits.map { it.text })
            assertEquals(1, hits.single().rank)
            assertEquals(1f / 61 + 1f / 63, hits.single().score, 1e-6f)
        }

    @Test
    fun hitsCarryTheirSource() =
        runBlocking {
            val library = open(embedder())
            val text = "# Handbook\n\n## Leave\n\nTwenty days a year."
            fake.embeddings(unit(0), unit(1), unit(2))
            library.add(Document("hb", "Handbook", text)).toList()
            fake.embeddings(unit(1))
            val hit = library.search("twenty", limit = 1).single()
            assertEquals("hb", hit.documentId)
            assertEquals("Handbook", hit.title)
            assertEquals(text.substring(hit.start, hit.end), hit.text)
            assertTrue(hit.text.contains("Twenty"))
            assertEquals(listOf("Handbook", "Leave"), hit.headings)
        }

    @Test
    fun rerankerReordersTopTwenty() =
        runBlocking {
            val library = open(embedder())
            val words = List(25) { "Word${"abcdefghijklmnopqrstuvwxy"[it]} line of text." }
            library.addDocument("a", words, *Array(25) { tilted(it) })
            fake.embeddings(unit(0))
            // dense order is row 0, 1, 2, ...; the reranker scores the fused top 20 and prefers the last of them
            fake.ranking(*FloatArray(20) { it / 20f })
            val hits = library.search("anything", limit = 3, reranker = reranker())
            assertEquals(listOf(words[19], words[18], words[17]), hits.map { it.text })
            assertEquals(listOf(1, 2, 3), hits.map { it.rank })
            assertEquals(listOf(0.95f, 0.9f, 0.85f), hits.map { it.score })
        }

    @Test
    fun rerankerGetsFewerWhenThereAreFewer() =
        runBlocking {
            val library = open(embedder())
            library.addDocument("a", listOf("Alpha line of text.", "Beta line of text."), unit(0), unit(1))
            fake.embeddings(unit(0))
            fake.ranking(0.1f, 0.9f)
            val hits = library.search("zzz", limit = 6, reranker = reranker())
            assertEquals(listOf("Beta line of text.", "Alpha line of text."), hits.map { it.text })
        }

    @Test
    fun queryPrefixIsUsed() =
        runBlocking {
            val format = EmbeddingFormat("search_query: ", "search_document: ")
            val library = open(embedder(), EmbeddingFormat(format.queryPrefix, ""))
            library.addDocument("a", listOf("Alpha line."), unit(0))
            fake.embeddings(unit(0))
            library.search("find me")
            assertTrue(fake.embedRequests.last().contains("search_query: find me"))
        }

    @Test
    fun unrelatedQueryReturnsLowScores() =
        runBlocking {
            val library = open(embedder())
            library.addDocument("a", listOf("Leave policy text.", "Travel policy text."), unit(0), unit(1))
            fake.embeddings(unit(0))
            val related = library.search("leave policy").first().score
            fake.embeddings(unit(2))
            val unrelated = library.search("quantum chromodynamics")
            // no keyword shares a token: only the dense list speaks, so no hit can reach what both lists give
            assertTrue(unrelated.all { it.score <= 1f / 61 + 1e-6f })
            assertTrue("$related", related > 1f / 61)
        }

    @Test
    fun limitIsRespected() =
        runBlocking {
            val library = open(embedder())
            library.addDocument("a", List(5) { "Item $it line." }, *Array(5) { unit(it) })
            fake.embeddings(unit(0))
            assertEquals(2, library.search("item", limit = 2).size)
            fake.embeddings(unit(0))
            assertEquals(5, library.search("item", limit = 50).size)
        }

    @Test
    fun tombstonedRowsNeverReturned() =
        runBlocking {
            val library = open(embedder())
            library.addDocument("a", listOf("Shared word alpha."), unit(0))
            library.addDocument("b", listOf("Shared word beta."), unit(1))
            library.addDocument("c", listOf("Shared word gamma."), unit(2))
            library.remove("a")
            fake.embeddings(unit(0))
            assertEquals(listOf("b", "c"), library.search("shared word").map { it.documentId }.sorted())
            // a replaced document's old rows are gone too
            library.addDocument("b", listOf("Shared word delta."), unit(3))
            fake.embeddings(unit(1))
            val texts = library.search("shared word").map { it.text }
            assertTrue(texts.toString(), "Shared word beta." !in texts && "Shared word delta." in texts)
        }

    @Test
    fun searchSurvivesCompactionAndReopen() =
        runBlocking {
            val model = embedder()
            val library = open(model)
            library.addDocument("a", listOf("Old apple text."), unit(0))
            library.addDocument("b", listOf("Keep banana text."), unit(1))
            library.remove("a") // a past-a-quarter tombstone compacts and renumbers
            fake.embeddings(unit(1))
            assertEquals(listOf("Keep banana text."), library.search("banana").map { it.text })
            library.close()
            val reopened = open(model)
            fake.embeddings(unit(1))
            assertEquals(listOf("Keep banana text."), reopened.search("banana apple").map { it.text })
        }

    @Test
    fun emptyLibraryAndBlankQueryEmbedNothing() =
        runBlocking {
            val library = open(embedder())
            assertEquals(emptyList<Hit>(), library.search("anything"))
            library.addDocument("a", listOf("Alpha line."), unit(0))
            assertEquals(emptyList<Hit>(), library.search("   "))
            assertEquals(emptyList<Hit>(), library.search("alpha", limit = 0))
            library.remove("a")
            assertEquals(emptyList<Hit>(), library.search("alpha"))
            // nothing was queued for those searches, and none consumed an answer
            fake.embeddings(unit(0))
            library.addDocument("b", listOf("Beta line."), unit(0))
        }

    private companion object {
        const val DIMS = 4
        const val BATCH = 16 // the ingest embeds this many passages a call
    }
}
