package com.suhel.llamabro.rag.search

/**
 * Reciprocal rank fusion: every list gives a row `1 / (k + rank)` for its 1-based rank, and a row's score is the sum
 * over the lists that hold it. Best first; equal scores go to the lower row. Only ranks count, so the lists' own scores
 * (cosines and BM25 sums, which share no scale) never need to be compared.
 */
internal fun reciprocalRankFusion(lists: List<List<Int>>, k: Int = RRF_K): List<Pair<Int, Float>> {
    val scores = HashMap<Int, Float>()
    for (list in lists) {
        list.forEachIndexed { i, row -> scores[row] = (scores[row] ?: 0f) + 1f / (k + i + 1) }
    }
    return scores.entries
        .sortedWith(compareByDescending<Map.Entry<Int, Float>> { it.value }.thenBy { it.key })
        .map { it.key to it.value }
}

internal const val RRF_K = 60
