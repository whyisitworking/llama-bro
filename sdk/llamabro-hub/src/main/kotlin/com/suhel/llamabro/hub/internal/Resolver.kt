package com.suhel.llamabro.hub.internal

import com.suhel.llamabro.hub.HubException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.File

/** One repository listing, online or reconstructed from the snapshot tree. */
internal class RepoListing(val commit: String, val files: List<RepoFile>, val offline: Boolean)

/** Port of hf-cache.cpp's get_repo_commit/get_repo_files and download.cpp's offline fallback. */
internal class Resolver(
    endpoint: String,
    private val cacheDir: File,
    private val transport: Transport,
    private val storage: Storage,
) {
    private val endpoint = endpoint.trimEnd('/') + "/"

    suspend fun list(repo: String): RepoListing =
        withContext(Dispatchers.IO) {
            require(isValidRepoId(repo)) { "invalid HF repo id: $repo" }
            try {
                listOnline(repo)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                cachedListing(repo) ?: throw asHubFailure(repo, e)
            }
        }

    private fun asHubFailure(repo: String, e: Exception): HubException =
        e as? HubException ?: HubException.Network("listing $repo failed: ${e.message}", e, true)

    /** The branch's commit and its file tree from the Hub, falling back to the cache when the tree comes back empty. */
    private suspend fun listOnline(repo: String): RepoListing {
        val repoDir = File(cacheDir, repoFolder(repo))
        val (branch, commit) = pickBranch(repo, branches(repo, repoDir))
        writeAtomic(File(repoDir, "refs/$branch"), commit.toByteArray(), storage)
        val tree =
            getJson("${endpoint}api/models/$repo/tree/$commit?recursive=true", repo) as? JsonArray
                ?: throw invalidResponse(repo, "tree")
        val files = filesOf(tree, File(repoDir, "snapshots/$commit"))
        // download.cpp falls back to the cache whenever the online listing is empty.
        return if (files.isEmpty()) {
            cachedListing(
                repo,
            ) ?: RepoListing(commit, files, false)
        } else {
            RepoListing(commit, files, false)
        }
    }

    /** The repo's branches as (name, commit), leaving out any whose name or commit isn't safe to use. */
    private suspend fun branches(repo: String, repoDir: File): List<Pair<String, String>> {
        val refs =
            getJson("${endpoint}api/models/$repo/refs", repo) as? JsonObject
                ?: throw invalidResponse(repo, "refs")
        val refsDir = File(repoDir, "refs").toPath()
        return (refs["branches"] as? JsonArray).orEmpty().mapNotNull { entry ->
            val obj = entry as? JsonObject ?: return@mapNotNull null
            val name = obj.string("name") ?: return@mapNotNull null
            val commit = obj.string("targetCommit") ?: return@mapNotNull null
            if (isValidCommit(commit) && isValidSubpath(refsDir, name)) name to commit else null
        }
    }

    private fun pickBranch(repo: String, branches: List<Pair<String, String>>): Pair<String, String> =
        branches.firstOrNull { it.first == "main" } ?: branches.firstOrNull()
            ?: throw HubException.Network("no valid branch in $repo", null, true)

    /** The files of a tree listing: those with a safe path and a digest to verify them by (D40). */
    private fun filesOf(tree: JsonArray, snapshotDir: File): List<RepoFile> =
        tree.mapNotNull { entry ->
            val obj = entry as? JsonObject ?: return@mapNotNull null
            if (obj.string("type") != "file") return@mapNotNull null
            val path = obj.string("path") ?: return@mapNotNull null
            if (!isValidSubpath(snapshotDir.toPath(), path)) return@mapNotNull null
            // As get_repo_files: an lfs object supplies the oid, and only without one is the git oid used.
            val lfs = obj["lfs"] as? JsonObject
            val oid = (if (lfs != null) lfs.string("oid") else obj.string("oid")) ?: return@mapNotNull null
            if (!isValidOid(oid)) return@mapNotNull null
            val size = ((lfs?.get("size") ?: obj["size"]) as? JsonPrimitive)?.longOrNull ?: 0L
            RepoFile(path, oid, size)
        }

    private fun invalidResponse(repo: String, what: String) =
        HubException.Network("invalid Hub $what response for $repo", null, true)

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private suspend fun getJson(url: String, repo: String): JsonElement {
        transport.open("GET", url, mapOf("Accept" to "application/json")).use { response ->
            val body =
                response.body
                    ?.bufferedReader()
                    ?.use { it.readText() }
                    .orEmpty()
            statusFailure(response, body, repo)?.let { throw it }
            try {
                return Json.parseToJsonElement(body)
            } catch (e: Exception) {
                throw HubException.Network("invalid Hub JSON from $url", e, true)
            }
        }
    }

    /** What a non-200 answer means, or null for a 200. */
    private fun statusFailure(response: Response, body: String, repo: String): HubException? =
        when (response.status) {
            200 -> {
                null
            }

            401, 403 -> {
                HubException.Unauthorized(
                    response.status,
                    "gated or private repo $repo: set HubConfig.token and accept the model's terms on huggingface.co",
                )
            }

            404 -> {
                HubException.NotFound("repository not found: $repo", emptyList())
            }

            429 -> {
                HubException.RateLimited(response.headers["retry-after"]?.firstOrNull()?.toLongOrNull())
            }

            else -> {
                HubException.Network(errorText(body), null, response.status >= 500)
            }
        }

    private fun errorText(body: String): String =
        try {
            Json
                .parseToJsonElement(body)
                .jsonObject["error"]
                ?.jsonPrimitive
                ?.content ?: body
        } catch (_: Exception) {
            body
        }

    internal fun cachedListing(repo: String): RepoListing? {
        val repoDir = File(cacheDir, repoFolder(repo))
        val refsDir = File(repoDir, "refs")
        // A ref is its file's first line, as upstream reads it; writeAtomic's temp files are not refs.
        val refs =
            refsDir
                .listFiles()
                .orEmpty()
                .filter {
                    it.isFile && !it.name.contains(".tmp-") &&
                        isValidSubpath(refsDir.toPath(), it.name)
                }.mapNotNull { file ->
                    file
                        .useLines { it.firstOrNull() }
                        ?.trim()
                        ?.takeIf(::isValidCommit)
                        ?.let { file.name to it }
                }
        val commit =
            (refs.firstOrNull { it.first == "main" } ?: refs.minByOrNull { it.first })?.second
                ?: return null
        val snapshotDir = File(repoDir, "snapshots/$commit")
        if (!snapshotDir.exists()) return null
        val files =
            snapshotDir
                .walkTopDown()
                .filter { it.isFile }
                .mapNotNull { file ->
                    val path =
                        snapshotDir
                            .toPath()
                            .relativize(file.toPath())
                            .toString()
                            .replace(File.separatorChar, '/')
                    if (isValidSubpath(snapshotDir.toPath(), path)) RepoFile(path, null, file.length()) else null
                }.sortedBy { it.path }
                .toList()
        return if (files.isEmpty()) null else RepoListing(commit, files, true)
    }
}
