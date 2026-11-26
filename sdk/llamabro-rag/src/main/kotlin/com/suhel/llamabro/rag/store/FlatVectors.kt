package com.suhel.llamabro.rag.store

import com.suhel.llamabro.rag.VectorStore
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okio.BufferedSource

/**
 * Every row's vector as float16 halves in memory, in segments of [SEGMENT_ROWS] rows, and a flag per tombstoned row.
 * Segments, not one array, so neither loading nor appending ever copies the index: memory stays at its size and one
 * part-filled segment. The vectors are unit length, so the cosine is the dot product, accumulated in float32 from each
 * half as it is read.
 */
internal class FlatVectors(private val dimensions: Int) : VectorStore {
    private val segments = mutableListOf<ShortArray>()
    private var dead = BooleanArray(0)

    var rows: Int = 0
        private set

    /** Halves held, filled or not: what the index costs in memory, two bytes each. */
    val capacity: Long get() = segments.size.toLong() * SEGMENT_ROWS * dimensions

    /** Segments ever allocated, so a test can bound what loading and compaction allocate. */
    var segmentsAllocated: Int = 0
        private set

    fun isLive(row: Int): Boolean = !dead[row]

    /** Adds rows given as their halves, one row after another. */
    fun append(rowHalves: ShortArray) {
        for (start in rowHalves.indices step dimensions) {
            rowHalves.copyInto(nextRow(), (rows % SEGMENT_ROWS) * dimensions, start, start + dimensions)
            rows++
        }
    }

    /** Adds [count] rows read from [source], little-endian halves as in `vectors.f16`, straight into the segments. */
    fun read(source: BufferedSource, count: Int) {
        repeat(count) {
            val segment = nextRow()
            val offset = (rows % SEGMENT_ROWS) * dimensions
            for (i in 0 until dimensions) segment[offset + i] = source.readShortLe()
            rows++
        }
    }

    /** The segment the next row goes in, added when the last is full, with the dead flags grown to cover the row. */
    private fun nextRow(): ShortArray {
        if (rows / SEGMENT_ROWS == segments.size) {
            segments += ShortArray(SEGMENT_ROWS * dimensions)
            segmentsAllocated++
        }
        if (rows >= dead.size) dead = dead.copyOf(maxOf(dead.size * 2, SEGMENT_ROWS))
        return segments[rows / SEGMENT_ROWS]
    }

    fun kill(first: Int, count: Int) {
        dead.fill(true, first, first + count)
    }

    /**
     * Keeps only [kept] rows (ascending), renumbered from 0, by moving each down in place; the segments past the
     * last kept row are dropped.
     */
    fun retain(kept: List<Int>) {
        var previous = -1
        kept.forEachIndexed { to, from ->
            check(from > previous) { "rows to keep must ascend" }
            previous = from
            if (to != from) {
                val source = segments[from / SEGMENT_ROWS]
                source.copyInto(
                    segments[to / SEGMENT_ROWS],
                    (to % SEGMENT_ROWS) * dimensions,
                    (from % SEGMENT_ROWS) * dimensions,
                    (from % SEGMENT_ROWS + 1) * dimensions,
                )
            }
        }
        rows = kept.size
        val segmentsNeeded = (rows + SEGMENT_ROWS - 1) / SEGMENT_ROWS
        while (segments.size > segmentsNeeded) segments.removeAt(segments.lastIndex)
        dead = BooleanArray(maxOf(rows, SEGMENT_ROWS))
    }

    override suspend fun nearest(query: FloatArray, k: Int): List<Pair<Int, Float>> {
        require(query.size == dimensions) { "the query has ${query.size} dimensions, the library $dimensions" }
        if (k <= 0) return emptyList()
        val best = TopK(k)
        for (row in 0 until rows) {
            if (row % CANCEL_CHECK_ROWS == 0) currentCoroutineContext().ensureActive()
            if (dead[row]) continue
            best.offer(row, dot(row, query))
        }
        return best.sorted()
    }

    private fun dot(row: Int, query: FloatArray): Float {
        val segment = segments[row / SEGMENT_ROWS]
        val offset = (row % SEGMENT_ROWS) * dimensions
        var sum = 0f
        for (i in 0 until dimensions) sum += Float16.toFloat(segment[offset + i]) * query[i]
        return sum
    }

    companion object {
        /** 1,024 rows: 1.5 MB a segment at 768 dimensions. */
        const val SEGMENT_ROWS = 1024
        private const val CANCEL_CHECK_ROWS = 4096
    }
}

/**
 * The best [k] (row, score) pairs seen, in a binary min-heap whose root is the worst kept: the lower score, or on a tie
 * the higher row. Rows arrive in increasing order, so a later row with an equal score never displaces a kept one.
 */
private class TopK(private val k: Int) {
    private val rows = IntArray(k)
    private val scores = FloatArray(k)
    private var size = 0

    private fun worse(a: Int, b: Int): Boolean = scores[a] < scores[b] || (scores[a] == scores[b] && rows[a] > rows[b])

    fun offer(row: Int, score: Float) {
        if (size < k) {
            rows[size] = row
            scores[size] = score
            siftUp(size++)
        } else if (score > scores[0]) {
            rows[0] = row
            scores[0] = score
            siftDown(0)
        }
    }

    private fun siftUp(start: Int) {
        var i = start
        while (i > 0) {
            val parent = (i - 1) / 2
            if (!worse(i, parent)) return
            swap(i, parent)
            i = parent
        }
    }

    private fun siftDown(start: Int) {
        var i = start
        while (true) {
            val left = 2 * i + 1
            val right = left + 1
            var worst = i
            if (left < size && worse(left, worst)) worst = left
            if (right < size && worse(right, worst)) worst = right
            if (worst == i) return
            swap(i, worst)
            i = worst
        }
    }

    private fun swap(a: Int, b: Int) {
        rows[a] = rows[b].also { rows[b] = rows[a] }
        scores[a] = scores[b].also { scores[b] = scores[a] }
    }

    fun sorted(): List<Pair<Int, Float>> =
        List(size) { rows[it] to scores[it] }
            .sortedWith(compareByDescending<Pair<Int, Float>> { it.second }.thenBy { it.first })
}
