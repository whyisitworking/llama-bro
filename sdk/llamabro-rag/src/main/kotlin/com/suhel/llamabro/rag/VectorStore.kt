package com.suhel.llamabro.rag

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
