package com.suhel.llamabro.rag

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChunkerTest {
    /** One token per character: the worst a tokenizer does, as on CJK. */
    private val perChar = TokenCounter { it.length }

    /** Two characters a token: harsher than any Latin-script tokenizer. */
    private val latin = TokenCounter { (it.length + 1) / 2 }

    private fun chunk(text: String, max: Int, overlap: Float = 0f, counter: TokenCounter = latin): List<Chunk> =
        runBlocking { Chunker(max, overlap, counter).chunks(Document("d", "Doc", text)) }

    @Test
    fun headingsBecomeThePath() {
        val text = "# Handbook\nIntro line.\n\n## Leave\nTwenty days.\n\n### Sick\nSee a doctor.\n\n## Pay\nMonthly.\n"
        val chunks = chunk(text, max = 200)
        assertEquals(
            listOf(
                listOf("Handbook"),
                listOf("Handbook", "Leave"),
                listOf("Handbook", "Leave", "Sick"),
                listOf("Handbook", "Pay"),
            ),
            chunks.map { it.headings },
        )
        assertTrue(chunks[1].text.contains("Twenty days."))
    }

    @Test
    fun hashesInsideAFenceAreNotHeadings() {
        val text = "# Top\n```\n# not a heading\n```\nafter\n"
        assertEquals(listOf(listOf("Top")), chunk(text, max = 200).map { it.headings })
    }

    @Test
    fun paragraphsKeptWhole() {
        val a = "First paragraph, a few words long."
        val b = "Second paragraph, a few words more."
        val c = "Third one."
        val chunks = chunk("$a\n\n$b\n\n$c", max = 20)
        assertEquals(listOf(a, b, c), chunks.map { it.text })
    }

    @Test
    fun smallParagraphsPackTogether() {
        val chunks = chunk("aaa\n\nbbb\n\nccc", max = 100)
        assertEquals(listOf("aaa\n\nbbb\n\nccc"), chunks.map { it.text })
    }

    @Test
    fun overlapRepeatsTheTail() {
        val paragraphs = (1..6).map { "para$it xxxx" } // 11 characters each
        val chunks = chunk(paragraphs.joinToString("\n\n"), max = 15, overlap = 0.4f)
        assertTrue(chunks.size >= 3)
        chunks.zipWithNext { a, b ->
            assertTrue("chunk ${b.index} starts before ${a.index} ends", b.start < a.end)
            assertTrue("next chunk starts after the previous one", b.start > a.start)
            assertTrue(a.text.endsWith(b.text.substring(0, a.end - b.start)))
        }
        assertEquals(paragraphs.last(), chunks.last().text.substringAfterLast("\n\n"))
    }

    @Test
    fun noOverlapMeansDisjointChunks() {
        val chunks = chunk((1..6).joinToString("\n\n") { "para$it xxxx" }, max = 15, overlap = 0f)
        chunks.zipWithNext { a, b -> assertTrue(b.start >= a.end) }
    }

    @Test
    fun offsetsPointAtTheText() {
        val text =
            "# A\nOne sentence. Two sentences! Three?\n\n## B\n" + "word ".repeat(60) + "\n\n  indented\n\n" +
                "z".repeat(90)
        val chunks = chunk(text, max = 20, overlap = 0.15f)
        assertTrue(chunks.size > 4)
        chunks.forEachIndexed { i, c ->
            assertEquals(i, c.index)
            assertEquals("d", c.documentId)
            assertEquals(text.substring(c.start, c.end), c.text)
            assertTrue(c.text.isNotBlank() && c.text == c.text.trim())
        }
    }

    @Test
    fun wallOfTextSplitsBySentenceThenCharacters() {
        val sentences = (1..5).map { "Sentence number $it is here." } // 28 characters each
        val wall = sentences.joinToString(" ")
        val bySentence = chunk(wall, max = 30)
        assertTrue(bySentence.size > 1)
        assertTrue(bySentence.all { it.text.endsWith(".") && it.text.startsWith("Sentence") })
        assertTrue(bySentence.all { runBlocking { latin.count(it.text) } <= 30 })

        val noPunctuation = "x".repeat(130)
        val byCharacters = chunk(noPunctuation, max = 50, counter = perChar)
        assertEquals(listOf(50, 50, 30), byCharacters.map { it.text.length })
        assertEquals(noPunctuation, byCharacters.joinToString("") { it.text })
    }

    @Test
    fun cjkWithoutSpacesStaysUnderLimit() {
        val text = "これは日本語の長い文章です".repeat(40) // no spaces, no stops: 480 characters
        val withStops = "今日は晴れです。明日は雨です！".repeat(30)
        for (t in listOf(text, withStops)) {
            val chunks = chunk(t, max = 64, overlap = 0.15f, counter = perChar)
            assertTrue(chunks.size > 5)
            assertTrue(chunks.all { it.text.length <= 64 })
            assertEquals(t.first(), chunks.first().text.first())
            assertEquals(t.last(), chunks.last().text.last())
        }
        // with the default estimate (3 characters a token) the same text stays under it too
        val estimated = runBlocking { Chunker(maxTokens = 64, overlap = 0f).chunks(Document("d", "t", text)) }
        assertTrue(estimated.all { TokenCounter.Estimate.let { e -> runBlocking { e.count(it.text) } } <= 64 })
    }

    @Test
    fun surrogatePairsAreNeverSplit() {
        val emoji = "😀".repeat(30) // 60 chars, 30 code points
        val chunks = chunk(emoji, max = 7, counter = perChar)
        assertTrue(chunks.all { !it.text.first().isLowSurrogate() && !it.text.last().isHighSurrogate() })
    }

    @Test
    fun aCounterIsAskedOnlyWhereTheEstimateIsNear() {
        var calls = 0
        val counting =
            TokenCounter {
                calls++
                it.length / 4
            }
        val text = (1..200).joinToString("\n\n") { "A short paragraph of plain latin words, number $it." }
        val chunks = chunk(text, max = 256, counter = counting)
        assertTrue(chunks.size > 3)
        assertTrue("$calls requests for ${chunks.size} chunks", calls <= chunks.size * 3)
    }

    @Test
    fun emptyDocumentHasNoChunks() {
        assertEquals(emptyList<Chunk>(), chunk("", max = 10))
        assertEquals(emptyList<Chunk>(), chunk("  \n\n \t\n", max = 10))
    }

    @Test
    fun anOverlapOfOneIsRefused() {
        val e = runCatching { Chunker(overlap = 1f) }.exceptionOrNull()
        assertTrue(e is IllegalArgumentException)
    }

    @Test
    fun eachRangeIsCountedAtMostOnce() {
        val seen = mutableListOf<String>()
        val recording =
            TokenCounter {
                seen += it
                it.length
            }
        // unique, wide text in one paragraph and in several, so every level of the split meets the same ranges again
        val one = (1..120).joinToString("") { "文章番号${it}です。" }
        val many = (1..30).joinToString("\n\n") { n -> (1..8).joinToString("") { "段落${n}の${it}番目。" } }
        val chunks = chunk("$one\n\n$many", max = 64, overlap = 0.15f, counter = recording)
        assertTrue(chunks.size > 10)
        assertEquals("a text was counted twice", seen.distinct().size, seen.size)
    }

    @Test
    fun aLongDocumentStaysUnderNRequests() {
        var requests = 0
        val counting =
            TokenCounter {
                requests++
                it.length
            }
        val text = (1..400).joinToString("\n\n") { n -> (1..12).joinToString("") { "段落${n}の${it}番目です。" } }
        assertTrue(text.length > 50_000)
        val chunks = chunk(text, max = 256, overlap = 0.15f, counter = counting)
        assertTrue("$requests requests for ${chunks.size} chunks", requests <= chunks.size * 2)
    }

    @Test
    fun prefixIsCountedWithEachChunk() {
        val prefix = "search_document: " // 17 tokens at one a character
        val seen = mutableListOf<String>()
        val recording =
            TokenCounter {
                seen += it
                it.length
            }
        val text = "x".repeat(200) + "\n\n" + "日本語".repeat(40) + "\n\n" + "tail"
        val chunks = runBlocking { Chunker(40, 0f, recording, prefix).chunks(Document("d", "t", text)) }
        assertTrue(chunks.size > 4)
        assertTrue(chunks.all { prefix.length + it.text.length <= 40 })
        assertTrue(seen.contains(prefix))
        assertEquals(text.replace("\n\n", ""), chunks.joinToString("") { it.text }.replace("\n\n", ""))
        val e = runCatching { runBlocking { Chunker(10, 0f, recording, prefix).chunks(Document("d", "t", "a")) } }
        assertTrue(e.exceptionOrNull() is IllegalArgumentException)
    }

    @Test
    fun denseDigitsAreSplitToFit() {
        // digits, hex and base64 run to a token a character: three times the Latin estimate
        val dense = (1..60).joinToString("\n\n") { n -> "$n: " + "0123456789abcdef".repeat(4) }
        val prefix = "doc: "
        val chunks = runBlocking { Chunker(60, 0.15f, perChar, prefix).chunks(Document("d", "t", dense)) }
        assertTrue(chunks.size > 30)
        assertTrue(chunks.all { prefix.length + it.text.length <= 60 })
    }

    @Test
    fun byteFallbackCharactersAreSplitToFit() {
        val fourEach = TokenCounter { text -> text.sumOf { if (it.code >= 0x0800) 4 else 1 as Int } }
        val text = (1..40).joinToString("\n\n") { n -> "段$n" + "漢字かな".repeat(6) }
        val chunks = chunk(text, max = 50, overlap = 0.15f, counter = fourEach)
        assertTrue(chunks.size > 10)
        assertTrue(chunks.all { runBlocking { fourEach.count(it.text) } <= 50 })
    }
}
