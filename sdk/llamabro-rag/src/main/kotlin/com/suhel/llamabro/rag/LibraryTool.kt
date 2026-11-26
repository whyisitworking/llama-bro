package com.suhel.llamabro.rag

import com.suhel.llamabro.LlamaBroException
import com.suhel.llamabro.Model
import com.suhel.llamabro.conversation.LbToolError
import com.suhel.llamabro.typed.TypedTool
import com.suhel.llamabro.typed.tool
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import okio.IOException

/** The arguments of the library's search tool: what the model wants to find. */
@Serializable
public data class SearchArgs(val query: String)

private const val NO_PASSAGES = "No passages in the library match this query."
private const val SEPARATOR = " › "

private const val DEFAULT_DESCRIPTION =
    "Searches the person's library of documents and returns the passages that best match the query, each numbered " +
        "[1], [2] and so on. Answer from them and cite the passages you used by number, as [n]."

/**
 * A tool for a conversation (`Conversation.Builder.tool`): the model calls it with a `query`, it runs [search] with
 * [limit] and [reranker], and the model gets the passages as [asContext] writes them, so it can cite `[n]`. No match
 * says so plainly, so the model doesn't invent. A closed or unreadable library, or a search model that failed, is an
 * `LbToolError` with a short message (no paths, no exception text) that the model can pass to the person; the cause
 * reaches the app. Cancellation propagates. [description] is what the model reads to choose the tool.
 */
public fun Library.searchTool(
    name: String = "search_library",
    limit: Int = 6,
    reranker: Model? = null,
    description: String = DEFAULT_DESCRIPTION,
): TypedTool<SearchArgs, String> =
    tool(name, description, SearchArgs.serializer(), String.serializer()) { args ->
        val hits =
            try {
                search(args.query, limit, reranker)
            } catch (e: IllegalStateException) {
                throw LbToolError("the library is not available", e)
            } catch (e: IOException) {
                throw LbToolError("the library could not be read", e)
            } catch (e: LlamaBroException) {
                throw LbToolError("the search failed", e)
            }
        hits.asContext()
    }

/**
 * The passages as text for a prompt, one block each: `[n] <title> › <headings>` (just `[n] <title>` without headings),
 * then the passage on the next line, blocks separated by a blank line. `n` is the position in this list, from 1, so a
 * reply can cite `[n]`. An empty list gives `No passages in the library match this query.`
 */
public fun List<Hit>.asContext(): String =
    if (isEmpty()) {
        NO_PASSAGES
    } else {
        mapIndexed { i, hit ->
            val where = (listOf(hit.title) + hit.headings).joinToString(SEPARATOR)
            "[${i + 1}] $where\n${hit.text}"
        }.joinToString("\n\n")
    }
