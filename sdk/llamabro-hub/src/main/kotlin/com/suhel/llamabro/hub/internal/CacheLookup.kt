package com.suhel.llamabro.hub.internal

import com.suhel.llamabro.hub.HubException
import com.suhel.llamabro.hub.HubFile
import java.io.File

/** What the cache holds of a plan's files: the snapshot entries, the blobs, and the partial downloads. */
internal class CacheLookup(private val cacheDir: File) {
    /** [files] of [repo] at [commit] as the plan lists them, each marked cached when its snapshot entry exists. */
    fun hubFiles(files: List<RepoFile>, role: HubFile.Role, repo: String, commit: String): List<HubFile> =
        files.map { file ->
            val snapshot = File(File(cacheDir, repoFolder(repo)), "snapshots/$commit/${file.path}")
            HubFile(file.path, role, file.sizeBytes, file.oid, snapshot.exists()).also {
                it.sourceRepo = repo
                it.sourceCommit = commit
            }
        }

    /** Bytes of the files still to fetch that are already on disk, whole or in part. */
    fun partialBytes(files: List<HubFile>): Long =
        files.filterNot { it.cached }.sumOf { file ->
            file.oid?.let { inProgressBytes(file.sourceRepo, it, file.sizeBytes) } ?: 0L
        }

    /** How much of one file is on disk: [bytes] of it, and whether that is all of it, linked into a snapshot. */
    internal class Held(val bytes: Long, val complete: Boolean)

    fun heldBytes(repo: String, commit: String?, file: PlanStore.FileRecord): Held {
        val snapshot = commit?.let { File(cacheDir, "${repoFolder(repo)}/snapshots/$it/${file.path}") }
        if (snapshot?.isFile == true && snapshot.length() == file.size) return Held(file.size, true)
        val oid = file.oid ?: return Held(0, false)
        return Held(inProgressBytes(repo, oid, file.size), false)
    }

    /** C.5: a verified blob not yet linked (killed before the link) counts in full, else what the partial holds. */
    fun inProgressBytes(repo: String, oid: String, size: Long): Long {
        val blobs = File(cacheDir, "${repoFolder(repo)}/blobs")
        val blob = File(blobs, oid)
        return if (blob.isFile &&
            blob.length() == size
        ) {
            size
        } else {
            File(blobs, "$oid.downloadInProgress").length().coerceAtMost(size)
        }
    }

    /** The absolute path of [file] in [repo]'s snapshot at [commit]. */
    fun snapshotPath(repo: String, commit: String, file: RepoFile): String {
        val path = File(cacheDir, "${repoFolder(repo)}/snapshots/$commit/${file.path}")
        if (!path.isFile) throw HubException.NotFound("file is not cached: ${file.path}", emptyList())
        return path.absolutePath
    }
}
