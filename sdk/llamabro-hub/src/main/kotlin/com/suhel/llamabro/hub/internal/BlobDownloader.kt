package com.suhel.llamabro.hub.internal

import com.suhel.llamabro.LogLevel
import com.suhel.llamabro.Logger
import com.suhel.llamabro.hub.HubException
import com.suhel.llamabro.hub.HubFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.coroutineContext

/** Downloads one immutable blob, then links it into upstream's snapshot layout. */
internal class BlobDownloader(
    private val endpoint: String,
    private val cacheDir: File,
    private val transport: Transport,
    private val storage: Storage,
    private val logger: Logger?,
    private val degradedSymlinks: AtomicBoolean,
    private val openOutput: (File, Boolean) -> FileOutputStream,
    private val createSymlink: (Path, Path) -> Unit,
) {
    suspend fun download(file: HubFile, progress: suspend (Long, Boolean) -> Unit) =
        withContext(Dispatchers.IO) {
            val repo = repoFolder(file.sourceRepo)
            val root = File(cacheDir, repo)
            val snapshot = File(root, "snapshots/${file.sourceCommit}/${file.path}")
            val oid =
                file.oid
                    ?: if (snapshot.isFile) {
                        return@withContext
                    } else {
                        throw HubException.NotFound("offline file missing: ${file.path}", emptyList())
                    }
            // A degraded entry holds its own bytes, with no blob to wait for. A linked one is checked under the lock
            // only: an update's reclaim and a delete decide under it whether its blob goes (LocalCopies, Cache).
            if (snapshot.isFile && !Files.isSymbolicLink(snapshot.toPath())) return@withContext
            val blob = File(root, "blobs/$oid")
            val lockFile = File(cacheDir, ".locks/$repo/$oid.lock")
            Locks.withFileLock(lockFile) {
                if (snapshot.isFile) return@withFileLock
                if (!blob.isFile) fetch(file, blob, progress)
                finish(blob, snapshot)
            }
        }

    private suspend fun fetch(file: HubFile, blob: File, progress: suspend (Long, Boolean) -> Unit) =
        BlobFetch(file, blob, progress).run()

    /** One blob's download: up to [MAX_ATTEMPTS] tries, each resuming a partial file that the digest vouches for. */
    private inner class BlobFetch(
        private val file: HubFile,
        private val blob: File,
        private val progress: suspend (Long, Boolean) -> Unit,
    ) {
        private val url = resolveUrl(endpoint, file)
        private val partial = File(blob.path + ".downloadInProgress")

        suspend fun run() {
            blob.parentFile!!.mkdirs()
            var lastError: Exception? = null
            for (attempt in 0 until MAX_ATTEMPTS) {
                if (attempt > 0) delay(attempt * RETRY_BACKOFF_MS)
                try {
                    attemptOnce()
                    return
                } catch (
                    @Suppress("TooGenericExceptionCaught") e: Exception,
                ) {
                    // A stop closes the connection under a blocked read, which fails as an IOException: it is the stop.
                    coroutineContext.ensureActive()
                    // Only a network failure that may pass gets another try. Cancelled, wrong bytes, a refusal (a 4xx),
                    // or a bug (a parse error) would end the same way again.
                    lastError =
                        when {
                            e is IOException -> failureOf(e)
                            e is HubException.Network && e.retryable -> e
                            else -> throw e
                        }
                }
            }
            throw lastError ?: HubException.Network("download failed: ${file.path}", null, true)
        }

        private fun failureOf(e: IOException): HubException {
            val message = e.message.orEmpty()
            if ("ENOSPC" in message || "No space left" in message) {
                throw HubException.InsufficientStorage(
                    (file.sizeBytes - partial.length()).coerceAtLeast(0),
                    storage.allocatableBytes(cacheDir),
                )
            }
            return HubException.Network("download ${file.path}: ${e.message}", e, true)
        }

        private suspend fun attemptOnce() {
            if (commitCompletePartial()) return
            val (total, supportsRanges) = headInfo()
            if (partial.isFile && (!supportsRanges || partial.length() > total)) discardPartial()
            var offset = partial.takeIf { it.isFile }?.length() ?: 0L
            var digest = digestFor(file, total)
            if (offset > 0) {
                progress(0, true)
                hashInto(digest, partial)
            }
            if (offset == total) {
                // Killed after the last byte but before the rename: the digest decides, no request needed.
                if (matchesOid(digest)) return commit(partial, blob)
                discardPartial()
                offset = 0
                digest = digestFor(file, total)
            }
            receive(offset, digest)
            if (partial.length() != total) {
                throw HubException.Network("incomplete ${file.path}: ${partial.length()} of $total bytes", null, true)
            }
            val actual = hex(digest.digest())
            if (!actual.equals(file.oid, ignoreCase = true)) {
                discardPartial()
                throw HubException.IntegrityMismatch(file.path, file.oid!!, actual)
            }
            commit(partial, blob)
        }

        /**
         * D44: a partial as long as the listed file, killed before the rename, is checked against its digest before any
         * request; a match needs none. True when it matched and was committed.
         */
        private suspend fun commitCompletePartial(): Boolean {
            if (!partial.isFile || partial.length() != file.sizeBytes) return false
            progress(0, true)
            val digest = digestFor(file, file.sizeBytes)
            hashInto(digest, partial)
            if (!matchesOid(digest)) {
                discardPartial()
                return false
            }
            commit(partial, blob)
            return true
        }

        private fun matchesOid(digest: MessageDigest) = hex(digest.digest()).equals(file.oid, ignoreCase = true)

        /** The file's length and whether the server resumes; download.cpp resumes unless Accept-Ranges says "none". */
        private suspend fun headInfo(): Pair<Long, Boolean> =
            transport.open("HEAD", url).use { response ->
                checkStatus(response, file.path)
                val length = response.headers["content-length"]?.firstOrNull()?.toLongOrNull() ?: file.sizeBytes
                length to (response.headers["accept-ranges"]?.firstOrNull()?.let { it != "none" } ?: false)
            }

        /** Appends the rest of the file (from [start]) to the partial, hashing as it goes. */
        private suspend fun receive(start: Long, digest: MessageDigest) {
            transport.open("GET", url, range = start.takeIf { it > 0 }).use { response ->
                rejection(response, start)?.let { throw it }
                val body = response.body ?: throw HubException.Network("empty body for ${file.path}", null, true)
                copy(body, start, digest)
            }
        }

        /** Why this answer to a (resumed) request can't be used, or null when it can. */
        private suspend fun rejection(response: Response, start: Long): HubException? {
            if (start > 0 && response.status != 206) {
                if (response.status != 416 && response.status != 200) return statusError(response, file.path)
                // The server won't resume this partial: it went, and a try from the first byte can work.
                discardPartial()
                return HubException.Network("HTTP ${response.status} resuming ${file.path}; restarting", null, true)
            }
            return if (response.status == 200 || response.status == 206) null else statusError(response, file.path)
        }

        private suspend fun copy(body: InputStream, start: Long, digest: MessageDigest) {
            var offset = start
            var signalledMidBody = offset >= MID_BODY_BYTES
            openOutput(partial, start > 0).use { output ->
                val buffer = ByteArray(1 shl 20)
                while (true) {
                    coroutineContext.ensureActive()
                    val count = body.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                    digest.update(buffer, 0, count)
                    offset += count
                    progress(count.toLong(), false)
                    if (!signalledMidBody && offset >= MID_BODY_BYTES) {
                        signalledMidBody = true
                        HubTestHooks.onPhase?.invoke("midBody")
                    }
                }
                output.fd.sync()
            }
        }

        private suspend fun discardPartial() {
            val length = partial.length()
            if (partial.delete() && length > 0) progress(-length, false) // its bytes were counted as done
        }
    }

    private suspend fun hashInto(digest: MessageDigest, file: File) {
        file.inputStream().buffered(1 shl 20).use { input ->
            val buffer = ByteArray(1 shl 20)
            while (true) {
                coroutineContext.ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
    }

    private fun commit(partial: File, blob: File) {
        HubTestHooks.onPhase?.invoke("beforeRename")
        Files.move(partial.toPath(), blob.toPath(), StandardCopyOption.ATOMIC_MOVE)
        storage.fsyncDir(blob.parentFile!!)
    }

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    private fun digestFor(file: HubFile, total: Long): MessageDigest {
        val digest = MessageDigest.getInstance(if (file.oid!!.length == 64) "SHA-256" else "SHA-1")
        if (file.oid.length == 40) digest.update("blob $total\u0000".toByteArray(Charsets.UTF_8))
        return digest
    }

    private fun finish(blob: File, snapshot: File) {
        if (snapshot.isFile) return
        snapshot.parentFile!!.mkdirs()
        HubTestHooks.onPhase?.invoke("beforeLink")
        // A dangling link left at the entry (its blob gone) is replaced, not mistaken for missing symlink support.
        if (Files.isSymbolicLink(snapshot.toPath())) Files.delete(snapshot.toPath())
        if (!degradedSymlinks.get()) {
            try {
                val relative = snapshot.parentFile!!.toPath().relativize(blob.toPath())
                createSymlink(snapshot.toPath(), relative)
                storage.fsyncDir(snapshot.parentFile!!)
                return
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception,
            ) {
                if (degradedSymlinks.compareAndSet(false, true)) {
                    logger?.log(
                        LogLevel.Warn,
                        "failed to create symlink: ${e.message}; switching to degraded mode",
                    )
                }
            }
        }
        try {
            Files.move(blob.toPath(), snapshot.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (_: IOException) {
            // Copy beside the entry, then rename: a kill mid-copy never leaves a short file at the entry.
            val temp = File(snapshot.path + ".tmp-copy")
            Files.copy(blob.toPath(), temp.toPath(), StandardCopyOption.REPLACE_EXISTING)
            Files.move(temp.toPath(), snapshot.toPath(), StandardCopyOption.ATOMIC_MOVE)
        }
        storage.fsyncDir(snapshot.parentFile!!)
    }

    private fun checkStatus(response: Response, path: String) {
        if (response.status !in 200..299) throw statusError(response, path)
    }

    // a 416 answers a range past the end: the partial is stale, and a fresh try starts it over
    private fun statusError(response: Response, path: String): HubException =
        hubFailure(
            response,
            unauthorized = "access denied: $path",
            notFound = "file not found: $path",
            other = "HTTP ${response.status} for $path",
            alsoRetryable = setOf(HTTP_RANGE_NOT_SATISFIABLE),
        )

    private companion object {
        const val HTTP_RANGE_NOT_SATISFIABLE = 416
        const val MAX_ATTEMPTS = 3
        const val RETRY_BACKOFF_MS = 2_000L
        const val MID_BODY_BYTES = 2L * 1024 * 1024
    }
}
