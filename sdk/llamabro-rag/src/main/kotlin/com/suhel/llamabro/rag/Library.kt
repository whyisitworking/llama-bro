package com.suhel.llamabro.rag

import com.suhel.llamabro.Model
import com.suhel.llamabro.ModelTask
import com.suhel.llamabro.Normalization
import com.suhel.llamabro.Pooling
import com.suhel.llamabro.embed
import com.suhel.llamabro.rag.store.LibraryStore
import com.suhel.llamabro.rag.store.embedderIdentity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.IOException
import okio.Path

/**
 * A document in a [Library].
 * @property chunks how many passages it was cut into.
 */
public data class DocumentInfo(val id: String, val title: String, val source: String?, val chunks: Int)

/**
 * Documents cut into passages, embedded by one model and kept in a folder (docs/design/rag.md). Open it with [open];
 * [add] and [remove] documents; it survives a crash with the last document committed whole.
 *
 * One write at a time: a mutex guards the folder, and every call that reads or changes it takes the mutex, so reads
 * wait for a commit in progress (a commit is short; embedding, the slow part of [add], runs outside the mutex). The
 * folder's I/O runs on [Dispatchers.IO]. If a write fails, that document's [add] (or [remove]) fails with the
 * [IOException] and the folder holds the library as it was (or, when the failure was in the compaction that follows a
 * commit, with the document in); the next call opens the folder again and carries on.
 */
public class Library internal constructor(
    private val fs: FileSystem,
    private val dir: Path,
    private val embedder: Model,
    private val chunker: Chunker,
    internal val identity: EmbedderIdentity,
    private var store: LibraryStore,
) : AutoCloseable {
    private val mutex = Mutex()
    private var failed = false

    @Volatile
    private var closed = false

    /**
     * Cuts [document] into passages, embeds them in batches and commits them, replacing any document with its id: the
     * new passages and the old ones' removal are one write, so after a crash the library holds one version or the
     * other. A cold flow: nothing happens until it is collected. Cancelling it before [IngestProgress.Saved] commits
     * nothing of the document. A passage longer than the embedder's batch is cut again and retried.
     * @throws IOException (from the flow) if the commit fails; the library holds what it held before.
     */
    public fun add(document: Document): Flow<IngestProgress> =
        flow {
            checkOpen()
            val chunks = chunker.chunks(document)
            emit(IngestProgress.Chunked(chunks.size))
            val embedded =
                Ingest(embedder, chunker).embed(chunks) { done, of -> emit(IngestProgress.Embedded(done, of)) }
            val (passages, vectors) = embedded.unzip()
            writing { store.commit(document.id, document.title, document.source, passages, vectors) }
            emit(IngestProgress.Saved)
        }

    /** Removes [documentId] and its passages; nothing if it isn't in the library. A failed write is as in [add]. */
    public suspend fun remove(documentId: String) {
        writing { store.remove(documentId) }
    }

    /** The documents, in the order they were added (a replaced one moves to the end). */
    public suspend fun documents(): List<DocumentInfo> =
        locked { store.documents.map { DocumentInfo(it.id, it.title, it.source, it.rowCount) } }

    /** Closes the library; later calls throw [IllegalStateException]. Its files are already whole on disk. */
    override fun close() {
        closed = true
    }

    private fun checkOpen() = check(!closed) { "the library is closed" }

    // runs [block] on the store under the mutex, after opening the folder again if the last write failed
    private suspend fun <T> locked(block: suspend () -> T): T =
        mutex.withLock {
            checkOpen()
            withContext(Dispatchers.IO) {
                if (failed) {
                    store = LibraryStore(fs, dir).apply { open(identity) }
                    failed = false
                }
                block()
            }
        }

    private suspend fun writing(block: suspend () -> Unit) {
        locked {
            try {
                block()
            } catch (e: IOException) {
                failed = true
                throw e
            }
        }
    }

    public companion object {
        /**
         * Opens the library in [dir], creating it if there is none, for [embedder], a model loaded with
         * [ModelTask.Embedding]. It embeds one short text to learn the vectors' length.
         *
         * @param format the instructions put before queries and passages ([EmbeddingFormat.of] picks a preset).
         * @param chunker how documents are cut. The default counts with the embedder's own tokenizer
         * ([TokenCounter.Model]). Whatever chunker is passed, the library gives it [format]'s
         * [EmbeddingFormat.documentPrefix] as its prefix (any prefix it was made with is replaced), so its `maxTokens`
         * covers the prefix and the passage, as the embedder sees them.
         * @param pooling the pooling [embedder] was loaded with ([ModelTask.Embedding.pooling]): a model doesn't report
         * it, and it is part of the library's identity.
         * @throws IndexMismatch if [dir] holds a library built by another embedder, pooling or format; nothing changes.
         */
        public suspend fun open(
            fs: FileSystem,
            dir: Path,
            embedder: Model,
            format: EmbeddingFormat,
            chunker: Chunker = Chunker(counter = TokenCounter.Model(embedder)),
            pooling: Pooling = Pooling.ModelDefault,
        ): Library {
            val probe = embedder.embed(format.documentPrefix + PROBE, Normalization.Euclidean)
            val identity = embedderIdentity(embedder.info, probe.size, pooling, Normalization.Euclidean, format)
            val store = LibraryStore(fs, dir)
            withContext(Dispatchers.IO) { store.open(identity) }
            return Library(fs, dir, embedder, chunker.copy(prefix = format.documentPrefix), identity, store)
        }

        private const val PROBE = "a"
    }
}
