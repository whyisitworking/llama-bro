package com.suhel.llamabro.rag.search

import org.junit.Assert.assertEquals
import org.junit.Test

class FusionTest {
    @Test
    fun rrfMergesTwoLists() {
        // dense: rows 5, 7, 9; keywords: rows 7, 3
        val fused = reciprocalRankFusion(listOf(listOf(5, 7, 9), listOf(7, 3)))
        assertEquals(listOf(7, 5, 3, 9), fused.map { it.first })
        val scores = fused.associate { it.first to it.second }
        assertEquals(1f / 62 + 1f / 61, scores.getValue(7), 1e-6f)
        assertEquals(1f / 61, scores.getValue(5), 1e-6f)
        assertEquals(1f / 62, scores.getValue(3), 1e-6f)
        assertEquals(1f / 63, scores.getValue(9), 1e-6f)
    }

    @Test
    fun equalScoresGoToTheLowerRow() {
        assertEquals(listOf(2, 8), reciprocalRankFusion(listOf(listOf(8), listOf(2))).map { it.first })
    }

    @Test
    fun emptyListsGiveNothing() {
        assertEquals(emptyList<Pair<Int, Float>>(), reciprocalRankFusion(listOf(emptyList(), emptyList())))
    }
}
