package com.suhel.llamabro.rag

import com.suhel.llamabro.Model
import com.suhel.llamabro.rag.search.reciprocalRankFusion
import com.suhel.llamabro.rerank

/**
 * A passage a search found, with where it came from.
 * @property documentId the [Document.id].
 * @property title the document's title.
 * @property text the passage, equal to `document.text.substring(start, end)`.
 * @property start where it begins in the document's text.
 * @property end where it ends (exclusive).
 * @property headings the Markdown headings above it, outermost first (see [Chunk.headings]).
 * @property score the reranker's relevance score when one was used, else the fused rank score (a sum of
 * `1 / (60 + rank)` over the dense and keyword lists, so a passage both lists put first scores about 0.033 and one
 * only a list found, at most 0.0164). Higher is better; scores of different searches aren't comparable.
 * @property rank 1 for the best hit, in the order returned. In the hits [searchTool] passes to its `onHits` it is the
 * passage's `[n]` instead, which goes on across the tool's calls (D172), so it isn't the hit's rank in that one search.
 */
public data class Hit(
    val documentId: String,
    val title: String,
    val text: String,
    val start: Int,
    val end: Int,
    val headings: List<String>,
    val score: Float,
    val rank: Int,
)

private const val CANDIDATES = 50
private const val RERANKED = 20

/**
 * Decision:
 * [D170](https://github.com/whyisitworking/llama-bro/blob/main/docs/decisions.md#d170); the defaults were kept by
 * [F191](https://github.com/whyisitworking/llama-bro/blob/main/docs/research/findings.md#f191).
 *
 * The [limit] passages that best answer [query]: the dense top 50 (the query embedded with the format's query prefix
 * against every vector) and the keyword (BM25) top 50, merged by reciprocal rank fusion (docs/design/rag.md). With
 * [reranker], a model loaded with `ModelTask.Rerank`, the fused top 20 (fewer if there are fewer) are reranked and
 * the best [limit] kept; [Hit.score] is then the reranker's. An empty library, a blank [query] or a [limit] below 1
 * gives an empty list without embedding anything.
 *
 * The query is embedded outside the library's lock. The lock is held for the whole matching step (both scans, the
 * fusion and reading the passages), which is in-memory and short, so the answer comes from one consistent state of the
 * library; the reranker runs after the lock is released. A document removed in between can still appear, from the
 * state the matching saw.
 */
public suspend fun Library.search(query: String, limit: Int = 6, reranker: Model? = null): List<Hit> {
    if (query.isBlank() || limit < 1 || reading { it.documents.isEmpty() }) return emptyList()
    val vector = embedQuery(query)
    val wanted = if (reranker == null) limit else RERANKED
    val fused =
        reading { store ->
            val titles = store.documents.associate { it.id to it.title }
            val dense = store.vectors.nearest(vector, CANDIDATES).map { it.first }
            val keywords = store.bm25.search(query, CANDIDATES).map { it.first }
            reciprocalRankFusion(listOf(dense, keywords)).take(wanted).map { (row, score) ->
                val chunk = store.chunk(row)
                chunk.toHit(titles.getValue(chunk.documentId), score)
            }
        }
    if (reranker == null) return fused.mapIndexed { i, hit -> hit.copy(rank = i + 1) }
    if (fused.isEmpty()) return emptyList()
    val ranked = reranker.rerank(query, fused.map { it.text }, topN = limit)
    return ranked.take(limit).mapIndexed { i, r -> fused[r.index].copy(score = r.score, rank = i + 1) }
}

private fun Chunk.toHit(title: String, score: Float) =
    Hit(documentId, title, text, start, end, headings, score, rank = 0)
