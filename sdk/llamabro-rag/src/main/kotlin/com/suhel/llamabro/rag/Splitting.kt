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
 * sentences, then characters. Every piece keeps the whitespace after it, so the pieces tile the range.
 */
internal class Splitter(private val text: String, private val max: Int, private val counter: TokenCounter) {
    suspend fun pieces(start: Int, end: Int, level: Int): List<Piece> {
        val whole = measure(start, end)
        if (whole.tokens <= max) return listOf(whole)
        val parts =
            when (level) {
                0 -> paragraphs(start, end)
                1 -> sentences(start, end)
                else -> return characters(start, end)
            }
        if (parts.size <= 1) return pieces(start, end, level + 1)
        return parts.flatMap { pieces(it.start, it.end, level + 1) }
    }

    /** The tokens of the text from [start] to [end], by the counter. */
    suspend fun count(start: Int, end: Int): Int = counter.count(text.substring(start, end))

    // The estimate decides when it is far from the limit and the text is Latin, Greek or Cyrillic, where a token is
    // never shorter than a character in three; otherwise the counter is asked. Text of more than eight limits cannot
    // fit.
    private suspend fun measure(start: Int, end: Int): Piece {
        val estimate = (end - start + 2) / 3
        return when {
            counter is TokenCounter.Estimate -> {
                Piece(start, end, estimate)
            }

            end - start > max * TOO_LONG_CHARS_PER_TOKEN -> {
                Piece(start, end, max + 1)
            }

            estimate * 2 <= max && (start until end).all { text[it].code < WIDE_SCRIPTS } -> {
                Piece(start, end, estimate, exact = false)
            }

            else -> {
                Piece(start, end, count(start, end))
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
    private suspend fun characters(start: Int, end: Int): List<Piece> {
        val pieces = ArrayList<Piece>()
        var from = start
        while (from < end) {
            var to = minOf(end, from + max * CHARS_PER_TOKEN)
            var piece = measure(from, to)
            while (piece.tokens > max && to - from > 1) {
                to = from + maxOf(1, (to - from) * max / piece.tokens)
                piece = measure(from, to)
            }
            if (to < end && text[to - 1].isHighSurrogate() && text[to].isLowSurrogate()) {
                to = if (to - from > 1) to - 1 else to + 1
                piece = measure(from, to)
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
