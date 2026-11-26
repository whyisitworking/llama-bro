package com.suhel.llamabro.rag.store

import com.suhel.llamabro.rag.Chunk
import com.suhel.llamabro.rag.EmbedderIdentity
import com.suhel.llamabro.rag.IndexMismatch
import com.suhel.llamabro.rag.VectorStore
import com.suhel.llamabro.rag.search.Bm25
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.Json
import okio.Buffer
import okio.BufferedSink
import okio.FileSystem
import okio.IOException
import okio.Path
import okio.buffer
import okio.utf8Size

/**
 * A library's folder on disk, and its contents in memory once [open]ed.
 *
 * - `manifest.json` ([Manifest]): the format version, the [EmbedderIdentity], the documents with their row ranges, and
 *   how many rows, bytes of chunks and tombstones count. It's written last, so it names only what is already on disk.
 * - `vectors.f16`: a row per chunk, `dimensions` IEEE float16 halves each, little-endian. Append-only.
 * - `chunks.jsonl`: a line per row, the chunk as JSON. Append-only.
 * - `deleted`: a line per removed document, `firstRow rowCount`; those rows are skipped until compaction.
 *
 * A crash can't leave a broken library. Whole files are written to `<name>.tmp`, flushed and moved over the old one,
 * and a crash mid-append leaves bytes past what the manifest counts, which the next write cuts off. Compaction (once
 * tombstones pass a quarter of the rows) writes `vectors.f16.tmp`, `chunks.jsonl.tmp`, and then `manifest.next`, the
 * point it is committed at; moving the three into place follows, and an [open] that finds `manifest.next` finishes it.
 *
 * Not thread-safe: one coroutine at a time ([open] first). The public library guards it with a mutex. Its I/O blocks
 * the caller's thread, so the caller picks the dispatcher.
 */
internal class LibraryStore(private val fs: FileSystem, private val dir: Path) {
    private val manifestPath = dir / "manifest.json"
    private val nextManifestPath = dir / "manifest.next"
    private val vectorsPath = dir / "vectors.f16"
    private val chunksPath = dir / "chunks.jsonl"
    private val deletedPath = dir / "deleted"

    private lateinit var manifest: Manifest
    private lateinit var flat: FlatVectors
    private val chunks = mutableListOf<Chunk>()

    /** Keyword search over the live rows, kept in step with [chunks] and the tombstones by every write. */
    var bm25 = Bm25()
        private set
    private var tombstones = listOf<Tombstone>()

    /** A write that failed; the files are whole, but which state they hold is known only by opening them again. */
    private var failure: Exception? = null

    /** A write failed, or the compaction after a commit did: open the store again before the next write. */
    val needsReopen: Boolean get() = failure != null

    private val dimensions: Int get() = manifest.embedder.dimensions

    /** The rows' vectors, for search. */
    val vectors: VectorStore get() = flat

    /** Rows stored, tombstoned ones included until compaction. */
    val rowCount: Int get() = flat.rows

    /** The documents, in the order they were committed. */
    val documents: List<StoredDocument> get() = manifest.documents

    /** The chunk at [row], live or tombstoned. */
    fun chunk(row: Int): Chunk = chunks[row]

    /** False once [row]'s document is removed or replaced. */
    fun isLive(row: Int): Boolean = flat.isLive(row)

    /**
     * Opens the folder, creating an empty library for [wanted] if there is none; finishes an interrupted compaction and
     * deletes what an interrupted write left.
     * @throws IndexMismatch if the library was built by another embedder; nothing is changed.
     */
    suspend fun open(wanted: EmbedderIdentity) {
        currentCoroutineContext().ensureActive()
        fs.createDirectories(dir)
        finishCompaction()
        fs.list(dir).filter { it.name.endsWith(TMP) }.forEach { fs.delete(it) }
        val existing = if (fs.exists(manifestPath)) json.decodeFromString<Manifest>(fs.readUtf8(manifestPath)) else null
        if (existing == null) {
            // a crash before the first manifest leaves data no manifest names
            listOf(vectorsPath, chunksPath, deletedPath).forEach { fs.delete(it) }
            manifest = Manifest(Manifest.VERSION, IdentityJson.of(wanted), 0, 0, 0, emptyList())
            writeWhole(manifestPath, manifestBytes(manifest))
        } else {
            if (existing.version != Manifest.VERSION) {
                throw IOException("library format ${existing.version} is not ${Manifest.VERSION}: $dir")
            }
            val built = existing.embedder.toIdentity()
            if (built != wanted) throw IndexMismatch(built, wanted)
            manifest = existing
        }
        load()
    }

    private fun load() {
        flat = FlatVectors(dimensions)
        if (manifest.rows > 0) fs.read(vectorsPath) { flat.read(this, manifest.rows) }
        chunks.clear()
        if (manifest.chunkBytes > 0) {
            fs.read(chunksPath) {
                val lines = readUtf8(manifest.chunkBytes).split('\n').filter { it.isNotEmpty() }
                lines.mapTo(chunks) { json.decodeFromString<ChunkJson>(it).toChunk() }
            }
        }
        if (chunks.size != manifest.rows) throw IOException("chunks.jsonl has ${chunks.size} of ${manifest.rows} rows")
        tombstones = if (manifest.tombstones > 0) readTombstones().take(manifest.tombstones) else emptyList()
        tombstones.forEach { flat.kill(it.firstRow, it.rowCount) }
        bm25 =
            Bm25().apply {
                add(chunks)
                tombstones.forEach { kill(it.firstRow, it.rowCount) }
            }
    }

    private fun readTombstones(): List<Tombstone> =
        fs.readUtf8(deletedPath).lines().filter { it.isNotBlank() }.map { line ->
            val (first, count) = line.split(' ').map { it.toInt() }
            Tombstone(first, count)
        }

    /**
     * Adds a document's [chunks] and their [vectors] (unit length, one per chunk, in order), replacing the document
     * with the same [documentId]. It's in the library once this returns. If a write fails this throws, the library
     * on disk holds what it held before, and this store must be opened again before another write. If the compaction
     * that may follow the commit fails, this returns (the document is in) and [needsReopen] is set.
     */
    suspend fun commit(
        documentId: String,
        title: String,
        source: String?,
        chunks: List<Chunk>,
        vectors: List<FloatArray>,
    ) {
        require(chunks.size == vectors.size) { "${chunks.size} chunks and ${vectors.size} vectors" }
        require(vectors.all { it.size == dimensions }) { "every vector must have $dimensions dimensions" }
        require(chunks.all { it.documentId == documentId }) { "every chunk must belong to $documentId" }
        writing { add(documentId, title, source, chunks, vectors) }
    }

    private fun add(
        documentId: String,
        title: String,
        source: String?,
        chunks: List<Chunk>,
        vectors: List<FloatArray>,
    ) {
        val halves = ShortArray(vectors.size * dimensions)
        for ((row, vector) in vectors.withIndex()) {
            for (i in vector.indices) halves[row * dimensions + i] = Float16.fromFloat(vector[i])
        }
        val vectorBytes = halvesBytes(halves)
        val chunkBytes = chunkLines(chunks)
        val addedChunkBytes = chunkBytes.size

        append(vectorsPath, manifest.rows.toLong() * dimensions * Short.SIZE_BYTES, vectorBytes)
        append(chunksPath, manifest.chunkBytes, chunkBytes)
        val replaced = manifest.documents.find { it.id == documentId }
        val nextTombstones = tombstones + listOfNotNull(replaced?.let { Tombstone(it.firstRow, it.rowCount) })
        if (replaced != null) writeTombstones(nextTombstones)
        val document = StoredDocument(documentId, title, source, manifest.rows, chunks.size)
        val next =
            manifest.copy(
                rows = manifest.rows + chunks.size,
                chunkBytes = manifest.chunkBytes + addedChunkBytes,
                tombstones = nextTombstones.size,
                documents = manifest.documents.filter { it.id != documentId } + document,
            )
        writeWhole(manifestPath, manifestBytes(next))

        manifest = next
        this.chunks += chunks
        flat.append(halves)
        bm25.add(chunks)
        tombstones = nextTombstones
        replaced?.let {
            flat.kill(it.firstRow, it.rowCount)
            bm25.kill(it.firstRow, it.rowCount)
        }
        if (pastAQuarter(tombstones)) compactAfterCommit()
    }

    // The commit above stands whatever happens here: a failed compaction leaves the committed library (open() finishes
    // one whose manifest.next was written and drops the rest), so it marks the store for reopening instead of failing
    // the commit, and the next write, still past a quarter, compacts again.
    private fun compactAfterCommit() {
        try {
            compact(manifest.documents)
        } catch (e: IOException) {
            failure = e
        }
    }

    /** Removes [documentId]'s rows; nothing if it isn't in the library. A failed write is as in [commit]. */
    suspend fun remove(documentId: String) {
        writing { tombstone(documentId) }
    }

    private fun tombstone(documentId: String) {
        val removed = manifest.documents.find { it.id == documentId } ?: return
        val documents = manifest.documents - removed
        val nextTombstones = tombstones + Tombstone(removed.firstRow, removed.rowCount)
        if (pastAQuarter(nextTombstones)) {
            compact(documents)
            return
        }
        writeTombstones(nextTombstones)
        val next = manifest.copy(tombstones = nextTombstones.size, documents = documents)
        writeWhole(manifestPath, manifestBytes(next))
        manifest = next
        tombstones = nextTombstones
        flat.kill(removed.firstRow, removed.rowCount)
        bm25.kill(removed.firstRow, removed.rowCount)
    }

    /** Runs a write, unless the caller is cancelled: a write once started runs to its end, or to a failure. */
    private suspend inline fun writing(block: () -> Unit) {
        failure?.let { throw IllegalStateException("a write failed: open the library again", it) }
        currentCoroutineContext().ensureActive()
        try {
            block()
        } catch (e: IOException) {
            failure = e
            throw e
        }
    }

    private fun pastAQuarter(tombstones: List<Tombstone>): Boolean =
        tombstones.sumOf { it.rowCount } * QUARTERS > manifest.rows

    /**
     * Rewrites the library holding only [documents]' rows, renumbered in their order. The kept rows stream from the old
     * files to the new ones, and memory is compacted in place once the new files are committed, so the index is never
     * held twice.
     */
    private fun compact(documents: List<StoredDocument>) {
        val kept = documents.flatMap { d -> d.firstRow until d.firstRow + d.rowCount }
        // documents are appended in commit order, so their rows ascend: each kept row moves down, never up
        check(kept.zipWithNext().all { (a, b) -> a < b }) { "documents' rows must ascend" }
        val rowBytes = dimensions.toLong() * Short.SIZE_BYTES
        writeFile(vectorsPath.tmp()) { sink ->
            fs.read(vectorsPath) {
                var next = 0
                for (row in kept) {
                    skip((row - next) * rowBytes)
                    sink.write(this, rowBytes)
                    next = row + 1
                }
            }
        }
        var chunkBytes = 0L
        writeFile(chunksPath.tmp()) { sink ->
            for (row in kept) {
                val line = json.encodeToString(chunkJson(chunks[row])) + "\n"
                sink.writeUtf8(line)
                chunkBytes += line.utf8Size()
            }
        }
        var firstRow = 0
        val renumbered = documents.map { d -> d.copy(firstRow = firstRow).also { firstRow += d.rowCount } }
        val next = manifest.copy(rows = kept.size, chunkBytes = chunkBytes, tombstones = 0, documents = renumbered)
        writeWhole(nextManifestPath, manifestBytes(next))
        finishCompaction()

        manifest = next
        flat.retain(kept)
        bm25.retain(kept)
        val keptChunks = kept.map { chunks[it] }
        chunks.clear()
        chunks += keptChunks
        tombstones = emptyList()
    }

    /** Moves a committed compaction's files into place; nothing unless `manifest.next` exists. */
    private fun finishCompaction() {
        if (!fs.exists(nextManifestPath)) return
        for (path in listOf(vectorsPath, chunksPath)) {
            if (fs.exists(path.tmp())) fs.atomicMove(path.tmp(), path)
        }
        fs.delete(deletedPath)
        fs.atomicMove(nextManifestPath, manifestPath)
    }

    private fun writeTombstones(tombstones: List<Tombstone>) {
        writeWhole(deletedPath, Buffer().apply { tombstones.forEach { writeUtf8("${it.firstRow} ${it.rowCount}\n") } })
    }

    /** Replaces [path] with [bytes] in one step: written to `<name>.tmp`, flushed, then moved over it. */
    private fun writeWhole(path: Path, bytes: Buffer) {
        writeFile(path.tmp(), bytes)
        fs.atomicMove(path.tmp(), path)
    }

    private fun writeFile(path: Path, bytes: Buffer) {
        writeFile(path) { it.writeAll(bytes) }
    }

    /** Writes [path] afresh through a buffered sink, then flushes it to the disk. */
    private fun writeFile(path: Path, write: (BufferedSink) -> Unit) {
        fs.openReadWrite(path).use { handle ->
            handle.resize(0)
            handle.sink().buffer().use(write)
            handle.flush()
        }
    }

    /** Writes [bytes] at [offset], cutting off whatever an interrupted append left past it. */
    private fun append(path: Path, offset: Long, bytes: Buffer) {
        fs.openReadWrite(path).use { handle ->
            handle.resize(offset)
            handle.write(offset, bytes, bytes.size)
            handle.flush()
        }
    }

    private fun halvesBytes(halves: ShortArray): Buffer = Buffer().apply { halves.forEach { writeShortLe(it.toInt()) } }

    private fun chunkLines(chunks: List<Chunk>): Buffer =
        Buffer().apply { chunks.forEach { writeUtf8(json.encodeToString(chunkJson(it))).writeByte('\n'.code) } }

    private fun manifestBytes(manifest: Manifest): Buffer = Buffer().writeUtf8(json.encodeToString(manifest))

    private fun FileSystem.readUtf8(path: Path): String = read(path) { readUtf8() }

    private fun Path.tmp(): Path = dir / "$name$TMP"

    private data class Tombstone(val firstRow: Int, val rowCount: Int)

    private companion object {
        const val TMP = ".tmp"
        const val QUARTERS = 4
        val json = Json { ignoreUnknownKeys = true }
    }
}

/**
 * The manifest of the library in [dir], read without opening it: `manifest.next` when a compaction was committed and
 * not yet moved into place (it is the library's state), else `manifest.json`; null when there is neither.
 */
internal fun readManifest(fs: FileSystem, dir: Path): Manifest? {
    val path = listOf(dir / "manifest.next", dir / "manifest.json").firstOrNull { fs.exists(it) } ?: return null
    return manifestJson.decodeFromString<Manifest>(fs.read(path) { readUtf8() })
}

private val manifestJson = Json { ignoreUnknownKeys = true }

private fun chunkJson(chunk: Chunk): ChunkJson =
    ChunkJson(chunk.documentId, chunk.index, chunk.text, chunk.start, chunk.end, chunk.headings)

private fun ChunkJson.toChunk(): Chunk = Chunk(documentId, index, text, start, end, headings)
