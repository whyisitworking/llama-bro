package com.suhel.llamabro.rag.search

import com.suhel.llamabro.rag.Chunk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Bm25Test {
    private fun chunk(text: String) = Chunk("d", 0, text, 0, text.length, emptyList())

    private fun index(vararg texts: String) = Bm25().apply { add(texts.map(::chunk)) }

    @Test
    fun exactNameBeatsParaphrase() {
        val bm25 = index("how to service the machine on a regular schedule", "Zephyrine-X9 maintenance notes")
        val hits = bm25.search("zephyrine x9", 5)
        assertEquals(listOf(1), hits.map { it.first })
    }

    @Test
    fun idfFavoursRareWords() {
        val bm25 = index("the cat sat on the mat", "the zebra ran", "the dog and the cat")
        val hits = bm25.search("the zebra", 3)
        assertEquals(1, hits.first().first)
        assertTrue(hits.first().second > hits[1].second)
    }

    @Test
    fun scoreMatchesTheFormula() {
        // one match in a one-row corpus: idf = ln(1 + 0.5 / 1.5), tf = 1, length = average, so the norm is k1
        val hit = index("alpha beta").search("alpha", 1).single()
        val idf = kotlin.math.ln(1 + 0.5 / 1.5)
        assertEquals(idf * 1 * 2.2 / (1 + 1.2), hit.second.toDouble(), 1e-6)
    }

    @Test
    fun tokensAreLowercaseRunsAndEachCjkCharacterAlone() {
        assertEquals(listOf("hello", "world", "42", "x9"), Bm25.tokens("Hello, WORLD! 42 x9"))
        assertEquals(listOf("sdk", "日", "本", "語", "go"), Bm25.tokens("SDK日本語 go"))
        assertEquals(emptyList<String>(), Bm25.tokens(" ... "))
    }

    @Test
    fun killedRowsDropOutAndRetainRenumbers() {
        val bm25 = index("apple one", "apple two", "apple three")
        bm25.kill(0, 1)
        assertEquals(listOf(1, 2), bm25.search("apple", 5).map { it.first }.sorted())
        bm25.retain(listOf(1, 2))
        assertEquals(listOf(0, 1), bm25.search("apple", 5).map { it.first }.sorted())
        assertEquals(listOf(1), bm25.search("three", 5).map { it.first })
        assertEquals(emptyList<Int>(), bm25.search("one", 5).map { it.first })
    }

    @Test
    fun noMatchGivesNothing() {
        assertEquals(emptyList<Pair<Int, Float>>(), index("alpha").search("omega", 3))
        assertEquals(emptyList<Pair<Int, Float>>(), Bm25().search("alpha", 3))
    }

    // Chunk-sized rows: a 256-token chunk has about 60 distinct terms, drawn Zipf-like from a vocabulary of ~30k.
    @Test
    fun fiftyThousandChunkSizedRowsFitInAboutFiftyMegabytes() {
        val random = java.util.Random(7)
        val vocabulary = 30_000

        fun term() = "term${(vocabulary * Math.pow(random.nextDouble(), 3.0)).toInt()}" // skewed to low ids
        val bm25 = Bm25()
        repeat(50) {
            bm25.add(
                List(1_000) {
                    val terms = HashSet<String>()
                    while (terms.size < 60) terms += term()
                    chunk(terms.joinToString(" "))
                },
            )
        }
        assertTrue("one String per term: ${bm25.vocabularySize}", bm25.vocabularySize <= vocabulary)
        val megabytes = bm25.estimatedBytes / 1_000_000
        println("estimated BM25 heap for 50k x 60 terms: $megabytes MB")
        assertTrue("$megabytes MB", megabytes < BUDGET_MB)
        bm25.kill(0, 49_990)
        bm25.retain((49_990 until 50_000).toList())
        assertTrue(bm25.vocabularySize <= 600)
        assertTrue(bm25.estimatedBytes < 1_000_000)
    }

    private companion object {
        const val BUDGET_MB = 80
    }
}
