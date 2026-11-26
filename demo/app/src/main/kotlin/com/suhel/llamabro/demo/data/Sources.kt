package com.suhel.llamabro.demo.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * A passage of the person's library a reply used (docs/design/rag.md): its [number] as the reply cites it (`[n]`), the
 * document it came from ([documentId], [title]), where it sits in that document's text ([start] to [end], exclusive),
 * the headings above it, and the passage itself, kept so the source still opens after the document is removed.
 */
@Serializable
data class Source(
    val number: Int,
    val documentId: String,
    val title: String,
    val start: Int,
    val end: Int,
    val headings: List<String> = emptyList(),
    val text: String,
)

/** A reply's sources as one column, JSON; none is null, so a reply that used no library stores nothing. */
internal object SourcesColumn {
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(Source.serializer())

    fun encode(sources: List<Source>): String? =
        sources
            .takeIf {
                it.isNotEmpty()
            }?.let { json.encodeToString(serializer, it) }

    /** What [encode] wrote; a column that can't be read gives none rather than failing the chat. */
    fun decode(stored: String?): List<Source> =
        stored?.let { runCatching { json.decodeFromString(serializer, it) }.getOrNull() }.orEmpty()
}
