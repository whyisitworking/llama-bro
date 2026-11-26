package com.suhel.llamabro.demo.library

import android.net.Uri
import android.util.Log
import com.suhel.llamabro.Model
import com.suhel.llamabro.demo.models.Names
import com.suhel.llamabro.hub.HfModel
import com.suhel.llamabro.rag.Chunker
import com.suhel.llamabro.rag.Document
import com.suhel.llamabro.rag.DocumentInfo
import com.suhel.llamabro.rag.EmbeddingFormat
import com.suhel.llamabro.rag.Hit
import com.suhel.llamabro.rag.IndexMismatch
import com.suhel.llamabro.rag.IngestProgress
import com.suhel.llamabro.rag.Library
import com.suhel.llamabro.rag.LibraryContents
import com.suhel.llamabro.rag.TokenCounter
import com.suhel.llamabro.rag.search
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okio.ByteString.Companion.encodeUtf8
import okio.FileSystem
import okio.IOException
import okio.Path

/**
 * The library's folder was indexed by [built] (the GGUF's name), and the embedder on the phone, [wanted], can't
 * search it: the page offers a rebuild.
 */
data class Mismatch(val built: String, val wanted: String)

/** The library is being rebuilt: what needs it fails at once rather than wait minutes for the rebuild to end. */
class LibraryBusy : IllegalStateException("the library is being rebuilt")

/**
 * The person's library (docs/design/rag.md): documents imported from files, cut and embedded by [embedder] through
 * [embedders], and kept in [dir] (under `noBackupFilesDir`: the vectors are rebuilt from the documents, so a backup
 * needn't carry them). The list is read from the folder without loading the embedder; importing, removing, searching
 * and rebuilding load it. The repository's lock covers only opening and closing the library and a rebuild's claim on
 * the folder, so a search never waits behind an import or a rebuild.
 *
 * A library another embedder built is never changed or deleted behind the person's back: opening it fails with
 * [IndexMismatch], [mismatch] says so, and [rebuild] embeds its passages again with the embedder on the phone.
 *
 * Each imported document's text is also kept in [texts] (beside the library's folder, which a rebuild replaces), so a
 * chat's source can show the passage in its surroundings ([documentText]); it goes when the document is removed.
 */
class LibraryRepository(
    private val fs: FileSystem,
    private val dir: Path,
    private val embedders: EmbedderHost,
    private val embedder: HfModel,
    private val read: suspend (Uri) -> Document,
    private val chunker: (Model) -> Chunker = { Chunker(counter = TokenCounter.Model(it)) },
    private val texts: Path? = null,
) {
    // bumped after every write, so the list is read again
    private val changes = MutableStateFlow(0)

    /** What the folder holds and what built it; null with no library yet, or one that can't be read. */
    val contents: Flow<LibraryContents?> =
        changes.map {
            try {
                Library.contents(fs, dir)
            } catch (e: IOException) {
                Log.w("LibraryRepository", "the library can't be read: ${e.javaClass.simpleName}")
                null
            }
        }

    /** The documents, in the order they were added (a replaced one moves to the end). */
    val documents: Flow<List<DocumentInfo>> = contents.map { it?.documents.orEmpty() }

    // the repository's own source: set when opening the folder fails with IndexMismatch, cleared by a rebuild
    private val _mismatch = MutableStateFlow<Mismatch?>(null)

    /** The folder was built by another embedder; null when it opens, or hasn't been opened yet. */
    val mismatch: StateFlow<Mismatch?> = _mismatch.asStateFlow()

    // covers opening and closing the library and the rebuild's claim on the folder, never a whole import or rebuild
    private val lock = Mutex()

    /** The library open on a loaded embedder; opened again when the host has loaded the embedder again. */
    private var open: Pair<Model, Library>? = null

    /** A rebuild owns the folder; under [lock]. */
    private var rebuilding = false

    /**
     * Reads [uri] ([read]: a [TextImport]) and adds it, replacing the document from the same file: the library's
     * progress, ending with [IngestProgress.Saved] once it is listed.
     * @throws ImportRefused for a file it can't take; [EmbedderDoesNotFit] and [IndexMismatch] before anything is
     * embedded.
     */
    fun import(uri: Uri): Flow<IngestProgress> =
        flow {
            val document = read(uri)
            writing { library ->
                emitAll(library.add(document))
                keepText(document)
            }
        }

    suspend fun remove(id: String) {
        writing {
            it.remove(id)
            texts?.let { folder -> withContext(Dispatchers.IO) { fs.delete(folder / textName(id)) } }
        }
    }

    /**
     * The whole text of document [id] as it was imported; null when it isn't in the library (removed, or never
     * imported), or was imported before texts were kept.
     */
    suspend fun documentText(id: String): String? {
        val folder = texts ?: return null
        if (documents.first().none { it.id == id }) return null
        return withContext(Dispatchers.IO) {
            val file = folder / textName(id)
            try {
                if (fs.exists(file)) fs.read(file) { readUtf8() } else null
            } catch (e: IOException) {
                Log.w("LibraryRepository", "a document's text can't be read: ${e.javaClass.simpleName}")
                null
            }
        }
    }

    // after the document is committed: a text that fails to save only loses the passage's surroundings
    private suspend fun keepText(document: Document) {
        val folder = texts ?: return
        withContext(Dispatchers.IO) {
            try {
                fs.createDirectories(folder)
                val file = folder / textName(document.id)
                val partial = folder / (textName(document.id) + ".tmp")
                fs.write(partial) { writeUtf8(document.text) }
                fs.atomicMove(partial, file)
            } catch (e: IOException) {
                Log.w("LibraryRepository", "a document's text can't be kept: ${e.javaClass.simpleName}")
            }
        }
    }

    // a document id is a content URI: its hash names the file
    private fun textName(id: String) = id.encodeUtf8().sha256().hex() + ".txt"

    /**
     * Runs [block] with the library open on the embedder, which stays leased until [block] ends: a chat's turn, whose
     * model may search through the SDK's tool (D172) while it writes. The repository's lock is held only to open the
     * library, so an import or a removal can run meanwhile (the library takes one write at a time itself), and a turn
     * never waits for one. During a rebuild it fails at once with [LibraryBusy]; a rebuild that starts later closes
     * the library, and a search after that fails as a closed library does.
     */
    suspend fun <T> leased(block: suspend (Library) -> T): T = withLibrary(block)

    /** The passages that best answer [query] (the library's defaults, F191), as of the last commit. */
    suspend fun search(query: String): List<Hit> = withLibrary { it.search(query) }

    /**
     * Embeds the library again with the embedder on the phone, from the passages it stored (`Library.rebuild`):
     * nothing is deleted, and until it ends the folder holds the old library. It claims the folder while it runs:
     * searches, imports and removals fail at once with [LibraryBusy] rather than wait, and a second rebuild too.
     */
    fun rebuild(): Flow<IngestProgress> =
        flow {
            embedders.use(embedder) { model ->
                lock.withLock {
                    if (rebuilding) throw LibraryBusy()
                    rebuilding = true
                    open?.second?.close()
                    open = null
                }
                try {
                    emitAll(Library.rebuild(fs, dir, model, EmbeddingFormat.of(model.info), chunker(model)))
                    _mismatch.value = null
                } finally {
                    withContext(NonCancellable) { lock.withLock { rebuilding = false } }
                    changes.update { it + 1 }
                }
            }
        }

    private suspend fun writing(block: suspend (Library) -> Unit) {
        withLibrary { library ->
            try {
                block(library)
            } finally {
                changes.update { it + 1 }
            }
        }
    }

    // the lock covers opening the library, not [block]: the library serializes its own writes, and embedding runs
    // outside them, so a search sees the library as of the last commit and never waits for an import
    private suspend fun <T> withLibrary(block: suspend (Library) -> T): T =
        embedders.use(embedder) { model ->
            val library =
                lock.withLock {
                    // the rebuild owns the folder: opening it now would delete the rebuild's half-written folder
                    if (rebuilding) throw LibraryBusy()
                    open?.takeIf { it.first === model }?.second ?: openOn(model)
                }
            block(library)
        }

    private suspend fun openOn(model: Model): Library {
        open?.second?.close()
        open = null
        val library =
            try {
                Library.open(fs, dir, model, EmbeddingFormat.of(model.info), chunker(model))
            } catch (e: IndexMismatch) {
                _mismatch.value = Mismatch(e.built.modelName, Names.of(embedder))
                throw e
            }
        _mismatch.value = null
        open = model to library
        return library
    }
}
