package com.suhel.llamabro.hub.internal

import com.suhel.llamabro.hub.HubException
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

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

    suspend fun list(repo: String): RepoListing = withContext(Dispatchers.IO) {
        require(isValidRepoId(repo)) { "invalid HF repo id: $repo" }
        try {
            val repoDir = File(cacheDir, repoFolder(repo))
            val refs = getJson("${endpoint}api/models/$repo/refs", repo) as? JsonObject
                ?: throw invalidResponse(repo, "refs")
            val branches = (refs["branches"] as? JsonArray).orEmpty().mapNotNull { entry ->
                val obj = entry as? JsonObject ?: return@mapNotNull null
                val name = obj.string("name") ?: return@mapNotNull null
                val commit = obj.string("targetCommit") ?: return@mapNotNull null
                if (!isValidCommit(commit) || !isValidSubpath(File(repoDir, "refs").toPath(), name)) null
                else name to commit
            }
            val selected = branches.firstOrNull { it.first == "main" } ?: branches.firstOrNull()
                ?: throw HubException.Network("no valid branch in $repo", null, true)
            val (branch, commit) = selected
            writeAtomic(File(repoDir, "refs/$branch"), commit.toByteArray(), storage)
            val tree = getJson("${endpoint}api/models/$repo/tree/$commit?recursive=true", repo) as? JsonArray
                ?: throw invalidResponse(repo, "tree")
            val snapshotDir = File(repoDir, "snapshots/$commit")
            val files = tree.mapNotNull { entry ->
                val obj = entry as? JsonObject ?: return@mapNotNull null
                if (obj.string("type") != "file") return@mapNotNull null
                val path = obj.string("path") ?: return@mapNotNull null
                if (!isValidSubpath(snapshotDir.toPath(), path)) return@mapNotNull null
                // As get_repo_files: an lfs object supplies the oid, and only without one is the git oid used.
                val lfs = obj["lfs"] as? JsonObject
                val oid = (if (lfs != null) lfs.string("oid") else obj.string("oid"))
                    ?: return@mapNotNull null // D40: a file without a digest can't be verified
                if (!isValidOid(oid)) return@mapNotNull null
                val size = ((lfs?.get("size") ?: obj["size"]) as? JsonPrimitive)?.longOrNull ?: 0L
                RepoFile(path, oid, size)
            }
            // download.cpp falls back to the cache whenever the online listing is empty.
            if (files.isEmpty()) cachedListing(repo) ?: RepoListing(commit, files, false)
            else RepoListing(commit, files, false)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            cachedListing(repo) ?: throw (e as? HubException
                ?: HubException.Network("listing $repo failed: ${e.message}", e, true))
        }
    }

    private fun invalidResponse(repo: String, what: String) =
        HubException.Network("invalid Hub $what response for $repo", null, true)

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private suspend fun getJson(url: String, repo: String): kotlinx.serialization.json.JsonElement {
        transport.open("GET", url, mapOf("Accept" to "application/json")).use { response ->
            val body = response.body?.bufferedReader()?.use { it.readText() }.orEmpty()
            when (response.status) {
                200 -> Unit
                401, 403 -> throw HubException.Unauthorized(response.status,
                    "gated or private repo $repo: set HubConfig.token and accept the model's terms on huggingface.co")
                404 -> throw HubException.NotFound("repository not found: $repo", emptyList())
                429 -> throw HubException.RateLimited(response.headers["retry-after"]?.firstOrNull()?.toLongOrNull())
                else -> throw HubException.Network(errorText(body), null, response.status >= 500)
            }
            try {
                return Json.parseToJsonElement(body)
            } catch (e: Exception) {
                throw HubException.Network("invalid Hub JSON from $url", e, true)
            }
        }
    }

    private fun errorText(body: String): String = try {
        Json.parseToJsonElement(body).jsonObject["error"]?.jsonPrimitive?.content ?: body
    } catch (_: Exception) { body }

    internal fun cachedListing(repo: String): RepoListing? {
        val repoDir = File(cacheDir, repoFolder(repo))
        val refsDir = File(repoDir, "refs")
        // A ref is its file's first line, as upstream reads it; writeAtomic's temp files are not refs.
        val refs = refsDir.listFiles().orEmpty().filter { it.isFile && !it.name.contains(".tmp-") &&
            isValidSubpath(refsDir.toPath(), it.name) }.mapNotNull { file ->
            file.useLines { it.firstOrNull() }?.trim()?.takeIf(::isValidCommit)?.let { file.name to it }
        }
        val commit = (refs.firstOrNull { it.first == "main" } ?: refs.minByOrNull { it.first })?.second
            ?: return null
        val snapshotDir = File(repoDir, "snapshots/$commit")
        if (!snapshotDir.exists()) return null
        val files = snapshotDir.walkTopDown().filter { it.isFile }.mapNotNull { file ->
            val path = snapshotDir.toPath().relativize(file.toPath()).toString().replace(File.separatorChar, '/')
            if (isValidSubpath(snapshotDir.toPath(), path)) RepoFile(path, null, file.length()) else null
        }.sortedBy { it.path }.toList()
        return if (files.isEmpty()) null else RepoListing(commit, files, true)
    }
}
