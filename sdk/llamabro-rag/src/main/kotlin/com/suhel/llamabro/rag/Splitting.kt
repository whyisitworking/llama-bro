package com.suhel.llamabro.rag

/** A range of the text, [end] exclusive. */
internal class Span(val start: Int, val end: Int)

/**
 * A run of a document's text and its token count, where it sits ([end] is exclusive). A count that is not [exact] is
 * the estimate, which the [Chunker] checks before it sends the chunk that holds the piece.
 */
internal class Piece(val start: Int, val end: Int, val tokens: Int, val exact: Boolean = true)

/** A Markdown section: from its heading line to the next one, and the heading path above and including it. */
internal class Section(val start: Int, val end: Int, val headings: List<String>)

private val HeadingLine = Regex("""^ {0,3}(#{1,6})[ \t]+(.*?)(?:[ \t]+#+)?[ \t]*$""")
private val FenceLine = Regex("""^ {0,3}(```|~~~)""")

/** Splits [text] at ATX headings (`#` to `######`), leaving fenced code alone; text before the first is a section. */
internal fun markdownSections(text: String): List<Section> {
    val starts = ArrayList<Triple<Int, Int, String>>() // offset, level, title
    var fence: String? = null
    var offset = 0
    for (line in text.split('\n')) {
        val marker = FenceLine.find(line)?.groupValues?.get(1)
        val heading = if (fence == null) HeadingLine.matchEntire(line.trimEnd('\r')) else null
        when {
            marker != null && fence == null -> fence = marker
            marker != null && marker == fence -> fence = null
            heading != null -> starts += Triple(offset, heading.groupValues[1].length, heading.groupValues[2])
        }
        offset += line.length + 1
    }
    val bounds = (listOf(0) + starts.map { it.first } + text.length).distinct()
    val path = ArrayList<Pair<Int, String>>()
    return bounds.zipWithNext().map { (start, end) ->
        starts.firstOrNull { it.first == start }?.let { (_, level, title) ->
            while (path.isNotEmpty() && path.last().first >= level) path.removeAt(path.lastIndex)
            path += level to title
        }
        Section(start, end, path.map { it.second })
    }
}

/**
 * Cuts text into pieces that each fit [max] tokens, along the coarsest boundary that does the job: paragraphs, then
 * sentences, then characters. Every piece keeps the whitespace after it, so the pieces tile the range. What the model
 * embeds is [prefix] and the text, so [prefixTokens] of the limit are spoken for. Each range is counted at most once.
 */
internal class Splitter(
    private val text: String,
    private val prefix: String,
    private val prefixTokens: Int,
    private val maxTokens: Int,
    private val counter: TokenCounter,
) {
    private val max = maxTokens - prefixTokens
    private val counted = HashMap<Long, Int>()

    /** Whether the counter is the estimate, which is the count by definition: nothing to check against a model. */
    val estimating: Boolean get() = counter is TokenCounter.Estimate

    /** Splits an estimated piece that turned out too big, counting every part. */
    suspend fun resplit(piece: Piece): List<Piece> = pieces(piece.start, piece.end, 1, exact = true)

    suspend fun pieces(start: Int, end: Int, level: Int, exact: Boolean = false): List<Piece> {
        val whole = measure(start, end, exact)
        if (whole.tokens <= max) return listOf(whole)
        val parts =
            when (level) {
                0 -> paragraphs(start, end)
                1 -> sentences(start, end)
                else -> return characters(start, end, exact)
            }
        if (parts.size <= 1) return pieces(start, end, level + 1, exact)
        return parts.flatMap { pieces(it.start, it.end, level + 1, exact) }
    }

    /** The tokens the model embeds for the text from [start] to [end]: the prefix and the text, counted once. */
    suspend fun count(start: Int, end: Int): Int =
        counted.getOrPut(start.toLong() shl Int.SIZE_BITS or end.toLong()) {
            counter.count(prefix + text.substring(start, end))
        }

    // The estimate (three characters a token for Latin, Greek and Cyrillic, one for other scripts) only guides where to
    // split: a range far under the limit by it is taken as a piece without a request, and the chunker counts every
    // chunk it builds before it keeps it. Otherwise, or when [exact], the counter is asked. Text of more than eight
    // limits cannot fit.
    private suspend fun measure(start: Int, end: Int, exact: Boolean = false): Piece {
        val chars = end - start
        val estimate = (chars + 2) / 3
        return when {
            counter is TokenCounter.Estimate -> {
                Piece(start, end, estimate)
            }

            chars > max * TOO_LONG_CHARS_PER_TOKEN -> {
                Piece(start, end, max + 1)
            }

            else -> {
                val guess = if ((start until end).all { text[it].code < WIDE_SCRIPTS }) estimate else chars
                if (!exact && guess * 2 <= max) {
                    Piece(start, end, guess, exact = false)
                } else {
                    Piece(start, end, maxOf(0, count(start, end) - prefixTokens))
                }
            }
        }
    }

    private fun paragraphs(start: Int, end: Int): List<Span> {
        val parts = ArrayList<Span>()
        var from = start
        for (gap in BlankLine.findAll(text.substring(start, end))) {
            val cut = start + gap.range.last + 1
            parts += Span(from, cut)
            from = cut
        }
        if (from < end) parts += Span(from, end)
        return parts
    }

    // `. ! ?` end a sentence before a space or the end; CJK stops end one wherever they stand, as there are no spaces
    private fun sentences(start: Int, end: Int): List<Span> {
        val parts = ArrayList<Span>()
        var from = start
        var i = start
        while (i < end) {
            val c = text[i++]
            val stop = c in CJK_STOPS || (c in STOPS && (i == end || text[i].isWhitespace()))
            if (!stop) continue
            while (i < end && text[i].isWhitespace()) i++
            parts += Span(from, i)
            from = i
        }
        if (from < end) parts += Span(from, end)
        return parts
    }

    // as many characters as fit, never between the halves of a surrogate pair
    private suspend fun characters(start: Int, end: Int, exact: Boolean): List<Piece> {
        val pieces = ArrayList<Piece>()
        var from = start
        while (from < end) {
            var to = minOf(end, from + max * CHARS_PER_TOKEN)
            var piece = measure(from, to, exact)
            while (piece.tokens > max && to - from > 1) {
                to = from + maxOf(1, (to - from) * max / piece.tokens)
                piece = measure(from, to, exact)
            }
            if (to < end && text[to - 1].isHighSurrogate() && text[to].isLowSurrogate()) {
                to = if (to - from > 1) to - 1 else to + 1
                piece = measure(from, to, exact)
            }
            pieces += piece
            from = to
        }
        return pieces
    }

    private companion object {
        val BlankLine = Regex("""\n[ \t\r]*\n\s*""")
        const val STOPS = ".!?"
        const val CJK_STOPS = "。！？"
        const val WIDE_SCRIPTS = 0x0800
        const val TOO_LONG_CHARS_PER_TOKEN = 8
        const val CHARS_PER_TOKEN = 3
    }
}
