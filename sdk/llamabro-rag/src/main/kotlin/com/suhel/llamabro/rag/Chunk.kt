package com.suhel.llamabro.rag

/**
 * A passage of a [Document], the unit that is embedded and found.
 * @property documentId the [Document.id].
 * @property index the passage's position in its document, from 0.
 * @property text the passage, equal to `document.text.substring(start, end)`.
 * @property start where it begins in [Document.text].
 * @property end where it ends (exclusive).
 * @property headings the Markdown headings above it, outermost first, so a hit can say "Handbook > Leave"; empty
 * before the first heading and in plain text.
 */
public data class Chunk(
    val documentId: String,
    val index: Int,
    val text: String,
    val start: Int,
    val end: Int,
    val headings: List<String>,
)
