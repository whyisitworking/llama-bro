package com.suhel.llamabro.hub.internal

import com.suhel.llamabro.LogLevel
import com.suhel.llamabro.Logger
import com.suhel.llamabro.hub.HubException
import com.suhel.llamabro.hub.HubFile
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Path
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
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
    suspend fun download(file: HubFile, progress: suspend (Long, Boolean) -> Unit) = withContext(Dispatchers.IO) {
        val repo = repoFolder(file.sourceRepo)
        val root = File(cacheDir, repo)
        val snapshot = File(root, "snapshots/${file.sourceCommit}/${file.path}")
        if (snapshot.isFile) return@withContext
        val oid = file.oid ?: throw HubException.NotFound("offline file missing: ${file.path}", emptyList())
        val blob = File(root, "blobs/$oid")
        val lockFile = File(cacheDir, ".locks/$repo/$oid.lock")
        Locks.withBlobLock(lockFile) {
            if (snapshot.isFile) return@withBlobLock
            if (!blob.isFile) fetch(file, blob, progress)
            finish(blob, snapshot)
        }
    }

    private suspend fun fetch(file: HubFile, blob: File, progress: suspend (Long, Boolean) -> Unit) {
        blob.parentFile!!.mkdirs()
        val url = resolveUrl(endpoint, file)
        val partial = File(blob.path + ".downloadInProgress")
        suspend fun discardPartial() {
            val length = partial.length()
            if (partial.delete() && length > 0) progress(-length, false) // its bytes were counted as done
        }
        var lastError: Exception? = null
        for (attempt in 0 until 3) {
            if (attempt > 0) delay(attempt * 2_000L)
            try {
                // D44: a partial as long as the listed file, killed before the rename, is checked against its digest
                // before any request; a match needs none.
                if (partial.isFile && partial.length() == file.sizeBytes) {
                    progress(0, true)
                    val digest = digestFor(file, file.sizeBytes)
                    hashInto(digest, partial)
                    if (hex(digest.digest()).equals(file.oid, ignoreCase = true)) {
                        commit(partial, blob)
                        return
                    }
                    discardPartial()
                }
                val (total, supportsRanges) = transport.open("HEAD", url).use { response ->
                    checkStatus(response, file.path)
                    val length = response.headers["content-length"]?.firstOrNull()?.toLongOrNull() ?: file.sizeBytes
                    // download.cpp resumes only when the server sends Accept-Ranges other than "none".
                    length to (response.headers["accept-ranges"]?.firstOrNull()?.let { it != "none" } ?: false)
                }
                if (partial.isFile && (!supportsRanges || partial.length() > total)) discardPartial()
                var offset = partial.takeIf { it.isFile }?.length() ?: 0L
                var digest = digestFor(file, total)
                if (offset > 0) {
                    progress(0, true)
                    hashInto(digest, partial)
                }
                if (offset == total) {
                    // Killed after the last byte but before the rename: the digest decides, no request needed.
                    val actual = hex(digest.digest())
                    if (actual.equals(file.oid, ignoreCase = true)) {
                        commit(partial, blob)
                        return
                    }
                    discardPartial()
                    offset = 0
                    digest = digestFor(file, total)
                }
                transport.open("GET", url, range = offset.takeIf { it > 0 }).use { response ->
                    if (offset > 0 && response.status != 206) {
                        if (response.status == 416 || response.status == 200) discardPartial()
                        throw statusError(response, file.path)
                    }
                    checkStatus(response, file.path)
                    if (response.status != 200 && response.status != 206) throw statusError(response, file.path)
                    val body = response.body ?: throw HubException.Network("empty body for ${file.path}", null, true)
                    var signalledMidBody = offset >= 2L * 1024 * 1024
                    openOutput(partial, offset > 0).use { output ->
                        val buffer = ByteArray(1 shl 20)
                        while (true) {
                            coroutineContext.ensureActive()
                            val count = body.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                            digest.update(buffer, 0, count)
                            offset += count
                            progress(count.toLong(), false)
                            if (!signalledMidBody && offset >= 2L * 1024 * 1024) {
                                signalledMidBody = true
                                HubTestHooks.onPhase?.invoke("midBody")
                            }
                        }
                        output.fd.sync()
                    }
                }
                if (partial.length() != total) throw HubException.Network(
                    "incomplete ${file.path}: ${partial.length()} of $total bytes", null, true)
                val actual = hex(digest.digest())
                if (!actual.equals(file.oid, ignoreCase = true)) {
                    discardPartial()
                    throw HubException.IntegrityMismatch(file.path, file.oid!!, actual)
                }
                commit(partial, blob)
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: HubException.IntegrityMismatch) {
                throw e
            } catch (e: IOException) {
                lastError = if (e.message?.contains("ENOSPC") == true ||
                    e.message?.contains("No space left") == true) {
                    throw HubException.InsufficientStorage(
                        (file.sizeBytes - partial.length()).coerceAtLeast(0), storage.allocatableBytes(cacheDir))
                } else HubException.Network("download ${file.path}: ${e.message}", e, true)
            } catch (e: Exception) {
                if (e is HubException.Unauthorized || e is HubException.RateLimited || e is HubException.NotFound) throw e
                lastError = e
            }
        }
        throw lastError ?: HubException.Network("download failed: ${file.path}", null, true)
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
            } catch (e: Exception) {
                if (degradedSymlinks.compareAndSet(false, true)) logger?.log(LogLevel.Warn,
                    "failed to create symlink: ${e.message}; switching to degraded mode")
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

    private fun statusError(response: Response, path: String): HubException = when (response.status) {
        401, 403 -> HubException.Unauthorized(response.status, "access denied: $path")
        404 -> HubException.NotFound("file not found: $path", emptyList())
        429 -> HubException.RateLimited(response.headers["retry-after"]?.firstOrNull()?.toLongOrNull())
        else -> HubException.Network("HTTP ${response.status} for $path", null, response.status >= 500 || response.status == 416)
    }
}
