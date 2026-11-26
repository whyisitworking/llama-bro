package com.suhel.llamabro.rag.search

import com.suhel.llamabro.rag.Chunk
import kotlin.math.ln

/**
 * Keyword search over a library's rows, in memory: BM25 with k1 = 1.2 and b = 0.75 and the Lucene idf, over the
 * tokens of [tokens]. Rows are numbered as the library's are: [add] appends the next rows, [kill] drops a document's
 * rows, and [retain] renumbers after a compaction. Dead rows count for nothing, neither in a score nor in the corpus
 * statistics.
 *
 * Not thread-safe: the library's mutex guards it with the rest of the store.
 */
internal class Bm25 {
    private val postings = HashMap<String, Postings>()

    // a row's term frequencies, null once the row is dead
    private val rows = mutableListOf<Map<String, Int>?>()
    private val lengths = mutableListOf<Int>()
    private var live = 0
    private var totalLength = 0L

    fun add(chunks: List<Chunk>) {
        for (chunk in chunks) index(tokens(chunk.text).groupingBy { it }.eachCount())
    }

    private fun index(frequencies: Map<String, Int>) {
        val row = rows.size
        rows += frequencies
        val length = frequencies.values.sum()
        lengths += length
        live++
        totalLength += length
        for ((term, tf) in frequencies) postings.getOrPut(term) { Postings() }.add(row, tf)
    }

    /** Drops [count] rows from [first]. */
    fun kill(first: Int, count: Int) {
        for (row in first until first + count) {
            val frequencies = rows[row] ?: continue
            for (term in frequencies.keys) postings.getValue(term).df--
            rows[row] = null
            live--
            totalLength -= lengths[row]
        }
    }

    /** Keeps only the live rows [kept] (ascending), renumbered from 0. */
    fun retain(kept: List<Int>) {
        val frequencies = kept.map { checkNotNull(rows[it]) { "row $it is dead" } }
        postings.clear()
        rows.clear()
        lengths.clear()
        live = 0
        totalLength = 0
        frequencies.forEach { index(it) }
    }

    /** The [k] best live rows for [query], best first, as (row, score); rows sharing no token with it don't appear. */
    fun search(query: String, k: Int): List<Pair<Int, Float>> {
        if (live == 0 || k <= 0) return emptyList()
        val average = totalLength.toDouble() / live
        val scores = HashMap<Int, Double>()
        for (term in tokens(query).distinct()) {
            val list = postings[term] ?: continue
            val idf = ln(1 + (live - list.df + HALF) / (list.df + HALF))
            for (i in 0 until list.size) {
                val row = list.rows[i]
                if (rows[row] == null) continue
                val tf = list.frequencies[i]
                val norm = K1 * (1 - B + B * lengths[row] / average)
                scores[row] = (scores[row] ?: 0.0) + idf * tf * (K1 + 1) / (tf + norm)
            }
        }
        return scores.entries
            .sortedWith(compareByDescending<Map.Entry<Int, Double>> { it.value }.thenBy { it.key })
            .take(k)
            .map { it.key to it.value.toFloat() }
    }

    private class Postings {
        var rows = IntArray(INITIAL)
        var frequencies = IntArray(INITIAL)
        var size = 0
        var df = 0

        fun add(row: Int, tf: Int) {
            if (size == rows.size) {
                rows = rows.copyOf(size * 2)
                frequencies = frequencies.copyOf(size * 2)
            }
            rows[size] = row
            frequencies[size++] = tf
            df++
        }
    }

    companion object {
        private const val K1 = 1.2
        private const val B = 0.75
        private const val HALF = 0.5
        private const val INITIAL = 4

        /**
         * Lowercase runs of letters and digits; each Han character and kana is a token of its own (those scripts have
         * no spaces). No stemming, no stop words.
         */
        fun tokens(text: String): List<String> {
            val result = mutableListOf<String>()
            val word = StringBuilder()

            fun flush() {
                if (word.isNotEmpty()) result += word.toString().also { word.clear() }
            }
            for (c in text) {
                when {
                    isCjk(c) -> {
                        flush()
                        result += c.toString()
                    }

                    c.isLetterOrDigit() -> {
                        word.append(c.lowercaseChar())
                    }

                    else -> {
                        flush()
                    }
                }
            }
            flush()
            return result
        }

        private fun isCjk(c: Char): Boolean = c in '一'..'鿿' || c in '㐀'..'䶿' || c in '぀'..'ヿ'
    }
}
