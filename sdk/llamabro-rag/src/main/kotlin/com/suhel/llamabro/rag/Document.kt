package com.suhel.llamabro.rag

/**
 * A document to index.
 * @property id names the document in the library; adding the same id again replaces it.
 * @property title shown with a hit, e.g. in a source chip.
 * @property text the whole text. A [Chunk]'s offsets point into it, so keep it unchanged while the library holds it.
 * @property source where it came from (a file name, a URL), for display; the library never reads it.
 */
public data class Document(val id: String, val title: String, val text: String, val source: String? = null)
