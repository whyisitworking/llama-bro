package com.suhel.llamabro.demo.chat

import com.suhel.llamabro.ModelInfo
import com.suhel.llamabro.conversation.LbToolError
import com.suhel.llamabro.demo.data.ChatRepository
import com.suhel.llamabro.demo.data.Source
import com.suhel.llamabro.rag.Hit
import com.suhel.llamabro.rag.SearchArgs
import com.suhel.llamabro.rag.asContext
import com.suhel.llamabro.typed.TypedTool
import com.suhel.llamabro.typed.tool
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.serialization.builtins.serializer
import okio.IOException

/** The person's library as a chat uses it (docs/design/rag.md). */
interface ChatLibrary {
    /** Whether the library holds at least one document: a chat offers "Use my library" only then. */
    val hasDocuments: Flow<Boolean>

    /** The passages that best answer [query], with the library's defaults (F191: six, no reranker). */
    suspend fun search(query: String): List<Hit>

    /** The whole text of [documentId], for a passage's surroundings; null once the document is gone. */
    suspend fun documentText(documentId: String): String?
}

/**
 * Whether a model calls tools, by its chat template's caps as its load reports them: llama-server parses tool calls
 * only from a template that writes them (`supports_tool_calls`), so a model without it gets the passages in its turn.
 */
fun callsTools(templateCaps: Map<String, Boolean>): Boolean = templateCaps["supports_tool_calls"] == true

/**
 * The library's search tool for a chat (D171): `Library.searchTool`'s name, arguments, passages and failure messages,
 * run through [library] (the embedder is leased for each search, not held for the whole turn) so the passages the
 * model read are handed to [found] and saved with the reply as its sources.
 */
internal fun libraryTool(library: ChatLibrary, found: (List<Hit>) -> Unit): TypedTool<SearchArgs, String> =
    tool(SEARCH_TOOL, SEARCH_DESCRIPTION, SearchArgs.serializer(), String.serializer()) { args ->
        val hits =
            try {
                library.search(args.query)
            } catch (e: CancellationException) {
                throw e // before the next catches: a cancelled turn stops the search, and isn't an error (D171)
            } catch (e: IOException) {
                throw LbToolError("the library could not be read", e)
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception,
            ) {
                // the embedder didn't fit or failed, the library was built by another: the model is told only this
                throw LbToolError("the search failed", e)
            }
        found(hits)
        hits.asContext()
    }

/** What a turn asks with from the library: the search [tool]'s library, or the [passages] for its system prompt. */
internal class TurnLibrary(val tool: ChatLibrary?, val passages: String?) {
    companion object {
        val None = TurnLibrary(null, null)
    }
}

/**
 * A chat's use of the library: its "Use my library" ([offered], [set]; a new chat's choice is [pending] until its first
 * message creates it), and what each turn gets ([forTurn]), with the passages it found kept as the reply's sources
 * ([found]). One per chat.
 */
internal class ChatLibraryUse(
    private val chats: ChatRepository,
    private val library: ChatLibrary?,
    private val callsTools: (ModelInfo) -> Boolean,
) {
    // user input: a new chat's "Use my library", until its first message creates the chat with it
    val pending = MutableStateFlow(false)

    /** The passages the turn under way found: its reply's sources. */
    val found = TurnSources()

    /** "Use my library" for the chat [chatId] names (0: not created yet); null while it isn't offered. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun offered(chatId: Flow<Long>): Flow<Boolean?> =
        combine(
            chatId.flatMapLatest { if (it == 0L) pending else chats.useLibrary(it) },
            library?.hasDocuments ?: flowOf(false),
        ) { on, offered -> on.takeIf { offered } }

    /** Turns "Use my library" on or off for chat [id], or for the new chat (0) it will be created with. */
    suspend fun set(id: Long, on: Boolean) {
        if (id == 0L) pending.value = on else chats.setUseLibrary(id, on)
    }

    /**
     * Chat [id]'s turn on a model whose load says [info], answering [question]: nothing while "Use my library" is off
     * or the library is empty; the search tool on a model that calls tools; else the best passages for [question].
     * A search that fails leaves them out, and [onFailure] says so. Starts the turn's [found] afresh.
     */
    suspend fun forTurn(id: Long, info: ModelInfo, question: String, onFailure: (Exception) -> Unit): TurnLibrary {
        found.clear()
        val library = library?.takeIf { chats.useLibrary(id).first() && it.hasDocuments.first() }
        return when {
            library == null -> TurnLibrary.None
            callsTools(info) -> TurnLibrary(library, null)
            else -> TurnLibrary(null, passages(library, question, onFailure))
        }
    }

    /** The search tool over [library], its passages kept in [found]. */
    fun tool(library: ChatLibrary): TypedTool<SearchArgs, String> = libraryTool(library) { found.add(it) }

    private suspend fun passages(library: ChatLibrary, question: String, onFailure: (Exception) -> Unit): String? {
        if (question.isBlank()) return null
        val hits =
            try {
                library.search(question)
            } catch (e: CancellationException) {
                throw e
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception,
            ) {
                onFailure(e)
                return null
            }
        if (hits.isEmpty()) return null
        found.add(hits)
        return passagesPrompt(hits)
    }
}

/**
 * The passages a model without tools gets in its turn, after the chat's instructions in the system prompt, so the
 * person's own message stays as they wrote it.
 */
internal fun passagesPrompt(hits: List<Hit>): String = PASSAGES_INTRO + "\n\n" + hits.asContext()

/**
 * The passages a turn found, as the reply's sources: numbered from 1 in the order they were first found, so with one
 * search (the passages in the turn, or one call of the tool) a source's number is the `[n]` the model cites. A second
 * call's passages follow the first's, numbered on. Written by the tool's handler and read when the reply ends, so it
 * is locked.
 */
internal class TurnSources {
    private val sources = mutableListOf<Source>()

    @Synchronized fun clear() = sources.clear()

    @Synchronized fun add(hits: List<Hit>) {
        for (hit in hits) {
            val known = sources.any { it.documentId == hit.documentId && it.start == hit.start && it.end == hit.end }
            if (!known) {
                sources +=
                    Source(sources.size + 1, hit.documentId, hit.title, hit.start, hit.end, hit.headings, hit.text)
            }
        }
    }

    /** What was found, and nothing after: the next turn starts empty. */
    @Synchronized fun take(): List<Source> = sources.toList().also { sources.clear() }
}

private const val SEARCH_TOOL = "search_library"

private const val SEARCH_DESCRIPTION =
    "Searches the person's library of documents and returns the passages that best match the query, each numbered " +
        "[1], [2] and so on. Answer from them and cite the passages you used by number, as [n]."

private const val PASSAGES_INTRO =
    "Passages from the person's library that may help with their next message. Answer from them where they help, " +
        "and cite the passages you used by number, as [n]."
