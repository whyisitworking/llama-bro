package com.suhel.llamabro.rag

import com.suhel.llamabro.Model
import com.suhel.llamabro.ModelTask
import com.suhel.llamabro.Normalization
import com.suhel.llamabro.Pooling
import com.suhel.llamabro.embed
import com.suhel.llamabro.rag.store.LibraryStore
import com.suhel.llamabro.rag.store.embedderIdentity
import com.suhel.llamabro.rag.store.readManifest
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
 * Decisions: the module and its scope,
 * [D167](https://github.com/whyisitworking/llama-bro/blob/main/docs/decisions.md#d167); the folder and its crash
 * safety,
 * [D168](https://github.com/whyisitworking/llama-bro/blob/main/docs/decisions.md#d168).
 *
 * Documents cut into passages, embedded by one model and kept in a folder (docs/design/rag.md). Open it with [open];
 * [add] and [remove] documents; it survives a crash with the last document committed whole.
 *
 * One write at a time: a mutex guards the folder, and every call that reads or changes it takes the mutex, so reads
 * wait for a commit in progress (a commit is short; embedding, the slow part of [add], runs outside the mutex). The
 * folder's I/O runs on [Dispatchers.IO]. If a write fails, that document's [add] (or [remove]) fails with the
 * [IOException] and the folder holds the library as it was; the next call opens the folder again and carries on. A
 * compaction that fails after a document is committed doesn't fail its [add]: the document is in, and the next write
 * compacts again.
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

    // the store for [block], under the mutex: search reads it as one consistent state (Search.kt)
    internal suspend fun <T> reading(block: suspend (LibraryStore) -> T): T = locked { block(store) }

    internal suspend fun embedQuery(query: String): FloatArray =
        embedder.embed(identity.format.queryPrefix + query, Normalization.Euclidean)

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
                if (failed || store.needsReopen) {
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
            withContext(Dispatchers.IO) {
                finishRebuild(fs, dir)
                store.open(identity)
            }
            return Library(fs, dir, embedder, chunker.copy(prefix = format.documentPrefix), identity, store)
        }

        /**
         * What the library in [dir] holds and which embedder built it, read without one: a list to show before an
         * embedder is loaded, and the identity to compare with one. Null when [dir] holds no library. It only reads:
         * an interrupted write is left for [open] to finish, and what it shows is the state that write committed.
         */
        public suspend fun contents(fs: FileSystem, dir: Path): LibraryContents? =
            withContext(Dispatchers.IO) {
                val committed = sibling(dir, REBUILT).takeIf { dir.parent != null && fs.exists(it) } ?: dir
                readManifest(fs, committed)?.let { manifest ->
                    LibraryContents(
                        manifest.embedder.toIdentity(),
                        manifest.documents.map { DocumentInfo(it.id, it.title, it.source, it.rowCount) },
                    )
                }
            }

        /**
         * Embeds the library in [dir] again with [embedder], for one [open] refused with [IndexMismatch]: every
         * document's passages, as they were cut and stored, go to a new library beside [dir] (in its parent folder),
         * which then takes its place. The documents keep their ids, titles, sources and order; a passage too long for
         * the new embedder is cut again, as in [add]. The parameters are [open]'s.
         *
         * A cold flow: [IngestProgress.Chunked] with the passages in the library, [IngestProgress.Embedded] over all
         * of them, then [IngestProgress.Saved] once the new library is in place. Until then [dir] holds the old one:
         * cancelling, a failure or a crash leaves it as it was (the next [open] deletes the half-written new one), and
         * a crash in the moment of the swap is finished by the next [open]. Close every [Library] open on [dir] first,
         * and run one rebuild of a folder at a time.
         */
        public fun rebuild(
            fs: FileSystem,
            dir: Path,
            embedder: Model,
            format: EmbeddingFormat,
            chunker: Chunker = Chunker(counter = TokenCounter.Model(embedder)),
            pooling: Pooling = Pooling.ModelDefault,
        ): Flow<IngestProgress> =
            flow {
                // the old library's passages stream from its folder a document at a time: its vectors and keyword
                // index are never loaded, and only the document being embedded is held beside the new library
                val old =
                    withContext(Dispatchers.IO) {
                        finishRebuild(fs, dir)
                        LibraryStore(fs, dir).passages()
                    }
                val next = sibling(dir, REBUILDING)
                try {
                    val documents = old?.documents.orEmpty()
                    var left = documents.sumOf { it.rowCount }
                    emit(IngestProgress.Chunked(left))
                    withContext(Dispatchers.IO) { fs.deleteRecursively(next) }
                    val library = open(fs, next, embedder, format, chunker, pooling)
                    try {
                        var done = 0
                        for (document in documents) {
                            val passages = withContext(Dispatchers.IO) { checkNotNull(old).read(document) }
                            left -= passages.size
                            val embedded =
                                Ingest(embedder, library.chunker).embed(passages) { n, of ->
                                    emit(IngestProgress.Embedded(done + n, done + of + left))
                                }
                            val (chunks, vectors) = embedded.unzip()
                            library.writing {
                                library.store.commit(document.id, document.title, document.source, chunks, vectors)
                            }
                            done += embedded.size
                        }
                    } finally {
                        library.close()
                    }
                } finally {
                    old?.close()
                }
                withContext(Dispatchers.IO) {
                    fs.atomicMove(next, sibling(dir, REBUILT))
                    finishRebuild(fs, dir)
                }
                emit(IngestProgress.Saved)
            }

        private const val PROBE = "a"
    }
}
