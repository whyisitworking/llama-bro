package com.suhel.llamabro.rag.store

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Finds a library's rows nearest to a query vector. Version 1 scans every row (exact, and fast enough to about 100k
 * rows, docs/design/rag.md); an approximate index can replace it behind this interface.
 */
public interface VectorStore {
    /**
     * The [k] live rows most similar to [query], best first, as (row, cosine similarity); equal scores go to the lower
     * row. Fewer when the library holds fewer. [query] is a unit vector of the library's dimensions.
     */
    public suspend fun nearest(query: FloatArray, k: Int): List<Pair<Int, Float>>
}

/**
 * Every row's vector as float16 halves in memory, row after row, the same bytes as `vectors.f16`, and a flag per
 * tombstoned row. The vectors are unit length, so the cosine is the dot product, accumulated in float32.
 */
internal class FlatVectors(private val dimensions: Int) : VectorStore {
    private var halves = ShortArray(0)
    private var dead = BooleanArray(0)

    var rows: Int = 0
        private set

    fun isLive(row: Int): Boolean = !dead[row]

    /** Adds rows given as their halves, one row after another. */
    fun append(rowHalves: ShortArray) {
        val added = rowHalves.size / dimensions
        ensureCapacity(rows + added)
        rowHalves.copyInto(halves, rows * dimensions)
        rows += added
    }

    fun kill(first: Int, count: Int) {
        dead.fill(true, first, first + count)
    }

    fun row(row: Int): ShortArray = halves.copyOfRange(row * dimensions, (row + 1) * dimensions)

    private fun ensureCapacity(wanted: Int) {
        if (wanted <= dead.size) return
        val capacity = maxOf(wanted, dead.size * 2, MIN_CAPACITY)
        halves = halves.copyOf(capacity * dimensions)
        dead = dead.copyOf(capacity)
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
        val offset = row * dimensions
        var sum = 0f
        for (i in 0 until dimensions) sum += Float16.toFloat(halves[offset + i]) * query[i]
        return sum
    }

    private companion object {
        const val MIN_CAPACITY = 64
        const val CANCEL_CHECK_ROWS = 4096
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
