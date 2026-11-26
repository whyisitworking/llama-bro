package com.suhel.llamabro.rag

/**
 * Splits a [Document] into passages for embedding: at Markdown headings first (each passage keeps its heading path),
 * then blank-line paragraphs, then sentences, then characters, so a passage never runs past [maxTokens]. Small pieces
 * are packed together up to the limit, and each passage after the first repeats the tail of the one before it.
 *
 * @param maxTokens the most tokens a passage may hold; keep it under the embedder's context.
 * @param overlap the share of [maxTokens] a passage repeats from the previous one in its section, from 0 up to but not
 * including 1.
 * @param counter how tokens are counted. The default [TokenCounter.Estimate] needs no model; [TokenCounter.Model]
 * counts with the embedder's own tokenizer, and the chunker asks it only where the estimate cannot decide.
 */
public class Chunker(
    private val maxTokens: Int = 256,
    private val overlap: Float = 0.15f,
    private val counter: TokenCounter = TokenCounter.Estimate,
) {
    init {
        require(maxTokens >= 1) { "maxTokens must be at least 1, was $maxTokens" }
        require(overlap >= 0f && overlap < 1f) { "overlap must be from 0 up to 1, was $overlap" }
    }

    /** The passages of [document], in order; none for a blank text. `start` and `end` are offsets into its text. */
    public suspend fun chunks(document: Document): List<Chunk> {
        val text = document.text
        val splitter = Splitter(text, maxTokens, counter)
        val chunks = ArrayList<Chunk>()
        for (section in markdownSections(text)) {
            val pieces = splitter.pieces(section.start, section.end, 0)
            for (range in pack(splitter, pieces, (overlap * maxTokens).toInt())) {
                val start = (range.start until range.end).firstOrNull { !text[it].isWhitespace() } ?: continue
                val end = (range.end downTo start + 1).first { !text[it - 1].isWhitespace() }
                chunks += Chunk(document.id, chunks.size, text.substring(start, end), start, end, section.headings)
            }
        }
        return chunks
    }

    // Greedy: each passage takes pieces while their token sum fits, after the tail of the previous one (whole pieces,
    // at most `budget` tokens, and always leaving room for the next piece) so every passage starts later than the last.
    private suspend fun pack(splitter: Splitter, pieces: List<Piece>, budget: Int): List<Span> {
        val out = ArrayList<Span>()
        var next = 0
        var previousFirst = -1
        while (next < pieces.size) {
            var first = overlapStart(pieces, next, previousFirst, budget)
            var sum = (first until next).sumOf { pieces[it].tokens }
            var end = next
            while (end < pieces.size &&
                (end == next || sum + pieces[end].tokens <= maxTokens)
            ) {
                sum += pieces[end++].tokens
            }
            // estimated pieces are counted for real once, as a whole, when the chunk comes near the limit
            while (end - first > 1 && needsCount(pieces, first, end) &&
                splitter.count(pieces[first].start, pieces[end - 1].end) > maxTokens
            ) {
                if (end - 1 > next) end-- else first++
            }
            out += Span(pieces[first].start, pieces[end - 1].end)
            previousFirst = first
            next = end
        }
        return out
    }

    // the tokens may be twice an estimate, so a chunk whose estimates sum to over half the limit may not fit
    private fun needsCount(pieces: List<Piece>, first: Int, end: Int): Boolean =
        (first until end).any { !pieces[it].exact } && (first until end).sumOf { pieces[it].tokens } * 2 > maxTokens

    private fun overlapStart(pieces: List<Piece>, next: Int, previousFirst: Int, budget: Int): Int {
        var first = next
        var sum = 0
        while (first - 1 > previousFirst) {
            val tail = sum + pieces[first - 1].tokens
            if (tail > budget || tail + pieces[next].tokens > maxTokens) break
            sum = tail
            first--
        }
        return first
    }
}
