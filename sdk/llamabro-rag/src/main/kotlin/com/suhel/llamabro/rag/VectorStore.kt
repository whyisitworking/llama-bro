package com.suhel.llamabro.rag

/**
 * Finds a library's rows nearest to a query vector. Version 1 scans every row (exact, and fast enough to about 100k
 * rows, docs/design/rag.md); an approximate index can replace it behind this interface. Internal: its rows are the
 * store's own numbering, which compaction changes, and a public plug point is designed when another store is (D167).
 */
internal interface VectorStore {
    /**
     * The [k] live rows most similar to [query], best first, as (row, cosine similarity); equal scores go to the lower
     * row. Fewer when the library holds fewer. [query] is a unit vector of the library's dimensions.
     */
    suspend fun nearest(query: FloatArray, k: Int): List<Pair<Int, Float>>
}
