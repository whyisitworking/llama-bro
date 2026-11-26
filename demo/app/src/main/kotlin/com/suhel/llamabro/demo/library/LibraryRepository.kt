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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okio.FileSystem
import okio.IOException
import okio.Path

/**
 * The library's folder was indexed by [built] (the GGUF's name), and the embedder on the phone, [wanted], can't
 * search it: the page offers a rebuild.
 */
data class Mismatch(val built: String, val wanted: String)

/**
 * The person's library (docs/design/rag.md): documents imported from files, cut and embedded by [embedder] through
 * [embedders], and kept in [dir] (under `noBackupFilesDir`: the vectors are rebuilt from the documents, so a backup
 * needn't carry them). The list is read from the folder without loading the embedder; importing, removing, searching
 * and rebuilding load it, one at a time.
 *
 * A library another embedder built is never changed or deleted behind the person's back: opening it fails with
 * [IndexMismatch], [mismatch] says so, and [rebuild] embeds its passages again with the embedder on the phone.
 */
class LibraryRepository(
    private val fs: FileSystem,
    private val dir: Path,
    private val embedders: EmbedderHost,
    private val embedder: HfModel,
    private val read: suspend (Uri) -> Document,
    private val chunker: (Model) -> Chunker = { Chunker(counter = TokenCounter.Model(it)) },
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

    private val lock = Mutex()

    /** The library open on a loaded embedder; opened again when the host has loaded the embedder again. */
    private var open: Pair<Model, Library>? = null

    /**
     * Reads [uri] ([read]: a [TextImport]) and adds it, replacing the document from the same file: the library's
     * progress, ending with [IngestProgress.Saved] once it is listed.
     * @throws ImportRefused for a file it can't take; [EmbedderDoesNotFit] and [IndexMismatch] before anything is
     * embedded.
     */
    fun import(uri: Uri): Flow<IngestProgress> =
        flow {
            val document = read(uri)
            writing { library -> emitAll(library.add(document)) }
        }

    suspend fun remove(id: String) {
        writing { it.remove(id) }
    }

    /** The passages that best answer [query] (the library's defaults, F191). */
    suspend fun search(query: String): List<Hit> = withLibrary { it.search(query) }

    /**
     * Embeds the library again with the embedder on the phone, from the passages it stored (`Library.rebuild`):
     * nothing is deleted, and until it ends the folder holds the old library.
     */
    fun rebuild(): Flow<IngestProgress> =
        flow {
            lock.withLock {
                embedders.use(embedder) { model ->
                    open?.second?.close()
                    open = null
                    try {
                        emitAll(Library.rebuild(fs, dir, model, EmbeddingFormat.of(model.info), chunker(model)))
                        _mismatch.value = null
                    } finally {
                        changes.update { it + 1 }
                    }
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

    private suspend fun <T> withLibrary(block: suspend (Library) -> T): T =
        lock.withLock {
            embedders.use(embedder) { model ->
                val library = open?.takeIf { it.first === model }?.second ?: openOn(model)
                block(library)
            }
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
