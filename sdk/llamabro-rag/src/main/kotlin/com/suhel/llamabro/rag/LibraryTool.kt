package com.suhel.llamabro.rag

import com.suhel.llamabro.LlamaBroException
import com.suhel.llamabro.Model
import com.suhel.llamabro.conversation.LbToolError
import com.suhel.llamabro.typed.TypedTool
import com.suhel.llamabro.typed.tool
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import okio.IOException
import kotlin.coroutines.cancellation.CancellationException

/** The arguments of the library's search tool: what the model wants to find. */
@Serializable
public data class SearchArgs(val query: String)

private const val NO_PASSAGES = "No passages in the library match this query."
private const val SEPARATOR = " › "

private const val DEFAULT_DESCRIPTION =
    "Searches the person's library of documents and returns the passages that best match the query, each numbered " +
        "[1], [2] and so on. Answer from them and cite the passages you used by number, as [n]."

/**
 * Decisions:
 * [D171](https://github.com/whyisitworking/llama-bro/blob/main/docs/decisions.md#d171),
 * [D172](https://github.com/whyisitworking/llama-bro/blob/main/docs/decisions.md#d172).
 *
 * A tool for a conversation (`Conversation.Builder.tool`): the model calls it with a `query`, it runs [search] with
 * [limit] and [reranker], and the model gets the passages as [asContext] writes them, so it can cite `[n]`. One tool
 * numbers its passages on across calls: after a first call showed [1] to [6], a second call's first passage is [7], so
 * a `[n]` in the reply names one passage. [onHits] gets each call's passages as the model is shown them, in order,
 * each [Hit.rank] set to its `[n]`, before the model reads them: an app keeps them to show the reply's sources. Make a
 * tool per turn for numbering that starts at [1] each turn. No match says so plainly, so the model doesn't invent; a
 * call that finds none passes an empty list to [onHits]. A closed or unreadable library, or a search model that
 * failed, is an `LbToolError` with a short message (no paths, no exception text) that the model can pass to the person;
 * the cause reaches the app. Cancellation propagates. [description] is what the model reads to choose the tool.
 */
public fun Library.searchTool(
    name: String = "search_library",
    limit: Int = 6,
    reranker: Model? = null,
    description: String = DEFAULT_DESCRIPTION,
    onHits: suspend (List<Hit>) -> Unit = {},
): TypedTool<SearchArgs, String> {
    val numbering = Numbering()
    return tool(name, description, SearchArgs.serializer(), String.serializer()) { args ->
        val shown = numbering.number(searchForTool(args.query, limit, reranker))
        onHits(shown)
        shown.asContext(first = shown.firstOrNull()?.rank ?: 1)
    }
}

// [search], its failures as the short errors the model may see (D171)
private suspend fun Library.searchForTool(query: String, limit: Int, reranker: Model?): List<Hit> =
    try {
        search(query, limit, reranker)
    } catch (e: CancellationException) {
        throw e // before the next catch: on the JVM it is an IllegalStateException
    } catch (e: IllegalStateException) {
        throw LbToolError("the library is not available", e)
    } catch (e: IOException) {
        throw LbToolError("the library could not be read", e)
    } catch (e: LlamaBroException) {
        throw LbToolError("the search failed", e)
    }

// the passages one tool has shown so far: parallel calls take their numbers one after another
private class Numbering {
    private val lock = Mutex()
    private var shown = 0

    suspend fun number(hits: List<Hit>): List<Hit> =
        lock.withLock {
            val first = shown + 1
            shown += hits.size
            hits.mapIndexed { i, hit -> hit.copy(rank = first + i) }
        }
}

/**
 * The passages as text for a prompt, one block each: `[n] <title> › <headings>` (just `[n] <title>` without headings),
 * then the passage on the next line, blocks separated by a blank line. `n` is the position in this list, from 1, so a
 * reply can cite `[n]`. An empty list gives `No passages in the library match this query.`
 */
public fun List<Hit>.asContext(): String = asContext(first = 1)

// numbered from [first]: the search tool's numbers go on across its calls (D172)
private fun List<Hit>.asContext(first: Int): String =
    if (isEmpty()) {
        NO_PASSAGES
    } else {
        mapIndexed { i, hit ->
            val where = (listOf(hit.title) + hit.headings).joinToString(SEPARATOR)
            "[${first + i}] $where\n${hit.text}"
        }.joinToString("\n\n")
    }
