package com.suhel.llamabro.rag

import com.suhel.llamabro.LlamaBroException
import com.suhel.llamabro.Model
import com.suhel.llamabro.Normalization
import com.suhel.llamabro.embed

/** How far [Library.add] has got with a document. */
public sealed interface IngestProgress {
    /** The document is cut into [chunks] passages; none are embedded yet. */
    public data class Chunked(val chunks: Int) : IngestProgress

    /**
     * [done] of [of] passages are embedded, after each batch. [of] can grow past [Chunked.chunks]: a passage too long
     * for the embedder's batch is cut again.
     */
    public data class Embedded(val done: Int, val of: Int) : IngestProgress

    /** The document is in the library, replacing any with its id. */
    public data object Saved : IngestProgress
}

/**
 * Embeds one document's chunks with [embedder], [BATCH] at a time, each sent as [Chunker.prefix] and its text (unit
 * vectors, so a dot product is the cosine). A batch the embedder refuses as longer than its batch
 * ([LlamaBroException.InputTooLong]) is handled in two ways:
 * - if the embedder's `limit` is under the chunker's, every chunk not yet embedded is cut again by a chunker of that
 *   limit (which counts the prefix in it, so the text gets the limit less the prefix), and the batch is sent again;
 * - otherwise the limit was right and the count was off (an estimate on CJK, say): each chunk of the batch is sent
 *   alone, and one still refused is cut in two, at the sentence end nearest its middle or at the middle, until it fits.
 *
 * Every cut keeps the document's offsets exact, and a chunk's heading path; chunks are numbered again at the end.
 */
internal class Ingest(private val embedder: Model, private var chunker: Chunker) {
    private val prefix = chunker.prefix

    /** The chunks, cut as they had to be and numbered in order, with their vectors. */
    suspend fun embed(
        chunks: List<Chunk>,
        progress: suspend (done: Int, of: Int) -> Unit,
    ): List<Pair<Chunk, FloatArray>> {
        val queue = ArrayDeque(chunks)
        val done = ArrayList<Pair<Chunk, FloatArray>>(chunks.size)
        while (queue.isNotEmpty()) {
            val batch = List(minOf(BATCH, queue.size)) { queue.removeFirst() }
            try {
                done += batch.zip(send(batch))
            } catch (e: LlamaBroException.InputTooLong) {
                if (e.limit < chunker.maxTokens) {
                    chunker = chunker.copy(maxTokens = e.limit)
                    val rest = (batch + queue).flatMap { cut(it) }
                    queue.clear()
                    queue += rest
                    continue
                }
                for (chunk in batch) done += alone(chunk)
            }
            progress(done.size, done.size + queue.size)
        }
        return done.mapIndexed { i, (chunk, vector) -> chunk.copy(index = i) to vector }
    }

    private suspend fun send(batch: List<Chunk>): List<FloatArray> {
        val vectors = embedder.embed(batch.map { prefix + it.text }, Normalization.Euclidean)
        check(vectors.size == batch.size) { "${batch.size} texts embedded into ${vectors.size} vectors" }
        return vectors
    }

    private suspend fun alone(chunk: Chunk): List<Pair<Chunk, FloatArray>> =
        try {
            listOf(chunk to send(listOf(chunk)).single())
        } catch (e: LlamaBroException.InputTooLong) {
            val halves = halve(chunk) ?: throw e
            halves.flatMap { alone(it) }
        }

    // the chunker over the chunk's own text; a chunk lies inside one section, so its heading path is the chunk's
    private suspend fun cut(chunk: Chunk): List<Chunk> =
        chunker.chunks(Document(chunk.documentId, "", chunk.text)).map {
            Chunk(chunk.documentId, 0, it.text, chunk.start + it.start, chunk.start + it.end, chunk.headings)
        }

    private companion object {
        const val BATCH = 16
    }
}

/**
 * [chunk] in two: at the sentence end nearest its middle when one lies in its middle half, otherwise at the middle
 * (never between a surrogate pair). Each half is trimmed; null when it can't be cut into two non-blank halves.
 */
internal fun halve(chunk: Chunk): List<Chunk>? {
    val text = chunk.text
    val middle = text.length / 2
    val sentence =
        (text.length / 4..text.length * 3 / 4)
            .filter { it in 1 until text.length && endsSentence(text, it) }
            .minByOrNull { kotlin.math.abs(it - middle) }
    var cut = sentence ?: middle
    if (cut in 1 until text.length && text[cut - 1].isHighSurrogate() && text[cut].isLowSurrogate()) cut++
    val halves = listOfNotNull(part(chunk, 0, cut), part(chunk, cut, text.length))
    return halves.takeIf { it.size == 2 }
}

// a cut at [at] follows `. ! ?` and whitespace, or a CJK stop
private fun endsSentence(text: String, at: Int): Boolean {
    val before = text[at - 1]
    return before in CJK_STOPS || (text[at].isWhitespace().not() && before.isWhitespace() && endsWithStop(text, at))
}

private fun endsWithStop(text: String, at: Int): Boolean {
    var i = at - 1
    while (i > 0 && text[i].isWhitespace()) i--
    return text[i] in STOPS
}

// [chunk]'s text from [from] to [to], trimmed, with its offsets; null when blank
private fun part(chunk: Chunk, from: Int, to: Int): Chunk? {
    val text = chunk.text
    val start = (from until to).firstOrNull { !text[it].isWhitespace() } ?: return null
    val end = (to downTo start + 1).first { !text[it - 1].isWhitespace() }
    return Chunk(
        chunk.documentId,
        0,
        text.substring(start, end),
        chunk.start + start,
        chunk.start + end,
        chunk.headings,
    )
}

private const val STOPS = ".!?"
private const val CJK_STOPS = "。！？"
