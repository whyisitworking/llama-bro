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
    // one String per distinct term; everything else refers to a term by its id
    private val ids = HashMap<String, Int>()
    private val terms = mutableListOf<String>()
    private val postings = mutableListOf<Postings>()

    // a row's distinct term ids and their frequencies, null once the row is dead
    private val rowTerms = mutableListOf<IntArray?>()
    private val rowFrequencies = mutableListOf<IntArray?>()
    private var lengths = IntArray(INITIAL)
    private var live = 0
    private var totalLength = 0L

    /** Distinct terms held (dead rows' terms stay until [retain]). */
    val vocabularySize: Int get() = terms.size

    /**
     * A deterministic heap estimate in bytes: 4 per int held (arrays at their capacity), 16 per array for its header
     * and length, 2 per vocabulary character and 64 per term for its String and map entry.
     */
    val estimatedBytes: Long
        get() {
            val rows = rowTerms.sumOf { (it?.size ?: 0) * 2L * Int.SIZE_BYTES + 2 * ARRAY_HEADER }
            val lists = postings.sumOf { it.rows.size * 2L * Int.SIZE_BYTES + 2 * ARRAY_HEADER }
            val vocabulary = terms.sumOf { it.length * 2L + TERM_OVERHEAD }
            return rows + lists + vocabulary + lengths.size.toLong() * Int.SIZE_BYTES
        }

    fun add(chunks: List<Chunk>) {
        for (chunk in chunks) index(tokens(chunk.text).groupingBy { it }.eachCount())
    }

    private fun index(frequencies: Map<String, Int>) {
        val row = rowTerms.size
        val ofRow = IntArray(frequencies.size)
        val tfs = IntArray(frequencies.size)
        var length = 0
        var i = 0
        for ((term, tf) in frequencies) {
            val id =
                ids.getOrPut(term) {
                    terms.size.also {
                        terms += term
                        postings += Postings()
                    }
                }
            postings[id].add(row, tf)
            ofRow[i] = id
            tfs[i++] = tf
            length += tf
        }
        rowTerms += ofRow
        rowFrequencies += tfs
        if (row == lengths.size) lengths = lengths.copyOf(row * 2)
        lengths[row] = length
        live++
        totalLength += length
    }

    /** Drops [count] rows from [first]. */
    fun kill(first: Int, count: Int) {
        for (row in first until first + count) {
            val ofRow = rowTerms[row] ?: continue
            for (id in ofRow) postings[id].df--
            rowTerms[row] = null
            rowFrequencies[row] = null
            live--
            totalLength -= lengths[row]
        }
    }

    /** Keeps only the live rows [kept] (ascending), renumbered from 0; terms only dead rows used are dropped. */
    fun retain(kept: List<Int>) {
        val oldTerms = terms.toList()
        val rows =
            kept.map { row ->
                val ofRow = checkNotNull(rowTerms[row]) { "row $row is dead" }
                val tfs = checkNotNull(rowFrequencies[row])
                ofRow.indices.associate { oldTerms[ofRow[it]] to tfs[it] }
            }
        ids.clear()
        terms.clear()
        postings.clear()
        rowTerms.clear()
        rowFrequencies.clear()
        lengths = IntArray(INITIAL)
        live = 0
        totalLength = 0
        rows.forEach { index(it) }
    }

    /** The [k] best live rows for [query], best first, as (row, score); rows sharing no token with it don't appear. */
    fun search(query: String, k: Int): List<Pair<Int, Float>> {
        if (live == 0 || k <= 0) return emptyList()
        val average = totalLength.toDouble() / live
        val scores = HashMap<Int, Double>()
        for (term in tokens(query).distinct()) {
            val list = ids[term]?.let { postings[it] } ?: continue
            val idf = ln(1 + (live - list.df + HALF) / (list.df + HALF))
            for (i in 0 until list.size) {
                val row = list.rows[i]
                if (rowTerms[row] == null) continue
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
        private const val TERM_OVERHEAD = 64L
        private const val ARRAY_HEADER = 16L

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
