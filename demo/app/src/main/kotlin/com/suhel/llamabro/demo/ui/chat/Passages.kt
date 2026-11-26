package com.suhel.llamabro.demo.ui.chat

import androidx.compose.runtime.Immutable
import com.suhel.llamabro.demo.data.Source

/**
 * A reply's source as its sheet shows it: the document's [title], the headings above the passage ([path], null with
 * none), and the [passage] with the document's text [before] and [after] it, which are empty when the document is gone
 * or no longer holds the passage where it was. The sheet highlights [passage].
 */
@Immutable
data class PassageView(
    val number: Int,
    val title: String,
    val path: String?,
    val before: String,
    val passage: String,
    val after: String,
)

/** How a reply's sources are shown: the chips under it and the passage's sheet. Pure, so it is tested on the JVM. */
object Passages {
    /** About a short paragraph of the document on each side of the passage. */
    private const val CONTEXT_CHARS = 400

    private val CITATION = Regex("""\[(\d{1,3})]""")

    /**
     * The chips under a reply: the sources it cites as `[n]`, in number order, each once; a number with no source is
     * left out. A reply that cites none shows every source it read, so what it was given is never hidden.
     */
    fun cited(text: String, sources: List<Source>): List<Source> {
        val numbers = CITATION.findAll(text).mapNotNull { it.groupValues[1].toIntOrNull() }.toSet()
        return sources.filter { it.number in numbers }.ifEmpty { sources }
    }

    /**
     * [source] with its surroundings from [documentText], the document as it is now: only when the text still holds the
     * passage at its place, since a document imported again may have moved it. Cut at a space or line break, so no
     * word is split.
     */
    fun view(source: Source, documentText: String?): PassageView {
        val path = source.headings.takeIf { it.isNotEmpty() }?.joinToString(" › ")
        val text = documentText?.takeIf { holds(it, source) }
        val before = text?.let { leading(it.substring(0, source.start)) }.orEmpty()
        val after = text?.let { trailing(it.substring(source.end)) }.orEmpty()
        return PassageView(source.number, source.title, path, before, source.text, after)
    }

    private fun holds(text: String, source: Source) =
        source.start in 0..source.end && source.end <= text.length &&
            text.regionMatches(source.start, source.text, 0, source.text.length) &&
            source.end - source.start == source.text.length

    // the end of [text], at most CONTEXT_CHARS, starting after a space or line break; "…" where it was cut
    private fun leading(text: String): String {
        if (text.length <= CONTEXT_CHARS) return text
        val cut = text.length - CONTEXT_CHARS
        val start = text.indexOfFirst(cut) { it.isWhitespace() }.let { if (it < 0) cut else it + 1 }
        return "…" + text.substring(start)
    }

    // the start of [text], at most CONTEXT_CHARS, ending before a space or line break; "…" where it was cut
    private fun trailing(text: String): String {
        if (text.length <= CONTEXT_CHARS) return text
        val end = text.lastIndexOfBefore(CONTEXT_CHARS) { it.isWhitespace() }.let { if (it <= 0) CONTEXT_CHARS else it }
        return text.substring(0, end) + "…"
    }

    private inline fun String.indexOfFirst(from: Int, predicate: (Char) -> Boolean): Int {
        for (i in from until length) if (predicate(this[i])) return i
        return -1
    }

    private inline fun String.lastIndexOfBefore(end: Int, predicate: (Char) -> Boolean): Int {
        for (i in end downTo 0) if (predicate(this[i])) return i
        return -1
    }
}
