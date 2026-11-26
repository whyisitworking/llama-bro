package com.suhel.llamabro.demo.chat

import com.suhel.llamabro.ModelInfo
import com.suhel.llamabro.demo.data.ChatRepository
import com.suhel.llamabro.demo.data.Source
import com.suhel.llamabro.rag.Hit
import com.suhel.llamabro.rag.Library
import com.suhel.llamabro.rag.SearchArgs
import com.suhel.llamabro.rag.asContext
import com.suhel.llamabro.rag.search
import com.suhel.llamabro.rag.searchTool
import com.suhel.llamabro.typed.TypedTool
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf

/** The person's library as a chat uses it (docs/design/rag.md). */
interface ChatLibrary {
    /** Whether the library holds at least one document: a chat offers "Use my library" only then. */
    val hasDocuments: Flow<Boolean>

    /**
     * Runs [block] with the library open on its embedder, which stays loaded until [block] ends: a search, or a whole
     * turn whose model may call the search tool.
     * @throws Exception when the library can't be opened (its embedder doesn't fit or failed, another embedder built
     * it); [block] doesn't run then.
     */
    suspend fun <T> use(block: suspend (Library) -> T): T

    /** The whole text of [documentId], for a passage's surroundings; null once the document is gone. */
    suspend fun documentText(documentId: String): String?
}

/**
 * Whether a model calls tools, by its chat template's caps as its load reports them: llama-server parses tool calls
 * only from a template that writes them (`supports_tool_calls`), so a model without it gets the passages in its turn.
 */
fun callsTools(templateCaps: Map<String, Boolean>): Boolean = templateCaps["supports_tool_calls"] == true

/**
 * The passages a model without tools gets in its turn, after the chat's instructions in the system prompt, so the
 * person's own message stays as they wrote it.
 */
internal fun passagesPrompt(hits: List<Hit>): String = PASSAGES_INTRO + "\n\n" + hits.asContext()

/**
 * What a turn asks with from the library: the SDK's search [tool] (made for this turn, so its numbers start at [1] and
 * go on across its calls, D172), or the [passages] for its system prompt.
 */
internal class TurnLibrary(val tool: TypedTool<SearchArgs, String>?, val passages: String?) {
    companion object {
        val None = TurnLibrary(null, null)
    }
}

/**
 * A chat's use of the library: its "Use my library" ([offered], [set]; a new chat's choice is [pending] until its first
 * message creates it), and what each turn gets ([forTurn]), with the passages it was shown kept as the reply's sources
 * ([found]). One per chat.
 */
internal class ChatLibraryUse(
    private val chats: ChatRepository,
    private val library: ChatLibrary?,
    private val callsTools: (ModelInfo) -> Boolean,
) {
    // user input: a new chat's "Use my library", until its first message creates the chat with it
    val pending = MutableStateFlow(false)

    /** The passages the turn under way showed the model: its reply's sources. */
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
     * Runs chat [id]'s turn, [turn], on a model whose load says [info], answering [question]: with nothing while "Use
     * my library" is off or the library is empty; on a model that calls tools, with the SDK's search tool, the
     * embedder held until the turn ends; else with the best passages for [question]. A library that can't be opened
     * or searched is left out, the turn goes on, and [onFailure] says so. Starts the turn's [found] afresh.
     */
    suspend fun <T> forTurn(
        id: Long,
        info: ModelInfo,
        question: String,
        onFailure: (Exception) -> Unit,
        turn: suspend (TurnLibrary) -> T,
    ): T {
        found.clear()
        val library =
            library?.takeIf { chats.useLibrary(id).first() && it.hasDocuments.first() } ?: return turn(TurnLibrary.None)
        if (!callsTools(info)) return turn(TurnLibrary(null, passages(library, question, onFailure)))
        var opened = false
        return try {
            library.use { open ->
                opened = true
                turn(TurnLibrary(open.searchTool(onHits = { found.add(it) }), null))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            // only the library's opening is caught here: what the turn itself throws is the turn's
            if (opened) throw e
            onFailure(e)
            turn(TurnLibrary.None)
        }
    }

    private suspend fun passages(library: ChatLibrary, question: String, onFailure: (Exception) -> Unit): String? {
        if (question.isBlank()) return null
        val hits =
            try {
                library.use { it.search(question) }
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
 * The passages a turn showed the model, as the reply's sources, each numbered by its [Hit.rank]: its `[n]` in the
 * search tool's numbering (D172), or its place in the passages a model without tools was given. Written by the tool's
 * `onHits` and read when the reply ends, so it is locked.
 */
internal class TurnSources {
    private val sources = mutableListOf<Source>()

    @Synchronized fun clear() = sources.clear()

    @Synchronized fun add(hits: List<Hit>) {
        hits.mapTo(sources) { Source(it.rank, it.documentId, it.title, it.start, it.end, it.headings, it.text) }
    }

    /** What was found, and nothing after: the next turn starts empty. */
    @Synchronized fun take(): List<Source> = sources.toList().also { sources.clear() }
}

private const val PASSAGES_INTRO =
    "Passages from the person's library that may help with their next message. Answer from them where they help, " +
        "and cite the passages you used by number, as [n]."
