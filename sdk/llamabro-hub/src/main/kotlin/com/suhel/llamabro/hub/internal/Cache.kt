package com.suhel.llamabro.hub.internal

import com.suhel.llamabro.hub.CachedModel
import com.suhel.llamabro.hub.HfModel
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Local cache enumeration and upstream-compatible removal. No network requests. */
internal class Cache(private val cacheDir: File, private val resolver: Resolver) {
    fun list(): List<CachedModel> = cacheDir.listFiles().orEmpty().asSequence()
        .filter { it.isDirectory }
        .mapNotNull { directory -> repoOfFolder(directory.name).takeIf(::isValidRepoId) }
        .flatMap { repo ->
            val files = resolver.cachedListing(repo)?.files.orEmpty()
            files.asSequence().mapNotNull { file ->
                val info = splitInfo(file.path)
                info.tag.takeIf { info.index == 1 && it.isNotEmpty() && !nameIsSidecar(info.prefix) }
            }.distinct().map { tag ->
                val primary = files.first { file ->
                    val info = splitInfo(file.path)
                    info.index == 1 && info.tag == tag && !nameIsSidecar(info.prefix)
                }
                CachedModel(HfModel("$repo:$tag"), splitFiles(files, primary).sumOf { it.sizeBytes })
            }
        }.sortedBy { it.model.toString() }.toList()

    fun delete(model: HfModel): Boolean {
        val repoDir = File(cacheDir, repoFolder(model.repo))
        val tag = model.tag?.uppercase()
        val removed = when {
            // "org/repo#file": that file (all its split parts) only; the repo's other quants stay
            model.file != null -> removeEntries(repoDir) { files -> files.firstOrNull { it.path == model.file }
                ?.let { file -> splitFiles(files, file).map { it.path }.toSet() }.orEmpty() }
            tag == null -> repoDir.isDirectory && repoDir.deleteRecursively()
            else -> removeTag(repoDir, tag)
        }
        val (plansRemoved, partials) = removePlans(model)
        // A removed selection's unfinished downloads go too, unless another selection still plans them.
        var partialsRemoved = false
        for (oid in partials) partialsRemoved = File(repoDir, "blobs/$oid.downloadInProgress").delete() || partialsRemoved
        return removed || plansRemoved || partialsRemoved
    }

    /** Removes the snapshot entries [select] picks in each snapshot, then the blobs no entry links any more. */
    private fun removeEntries(repoDir: File, select: (List<RepoFile>) -> Set<String>): Boolean {
        val snapshots = File(repoDir, "snapshots")
        if (!snapshots.isDirectory) return false
        val toRemove = mutableListOf<Path>()
        for (snapshot in snapshots.listFiles().orEmpty().filter { it.isDirectory }) {
            val files = snapshotFiles(snapshot)
            for (path in select(files)) toRemove.add(File(snapshot, path).toPath())
        }
        return removeAndCollect(repoDir, snapshots, toRemove)
    }

    private fun snapshotFiles(snapshot: File): List<RepoFile> = snapshot.walkTopDown().filter { it.isFile }.mapNotNull { file ->
        val relative = snapshot.toPath().relativize(file.toPath()).toString().replace(File.separatorChar, '/')
        if (isValidSubpath(snapshot.toPath(), relative)) RepoFile(relative, sizeBytes = file.length()) else null
    }.toList()

    private fun removeTag(repoDir: File, tag: String): Boolean {
        val snapshots = File(repoDir, "snapshots")
        if (!snapshots.isDirectory) return false
        val toRemove = mutableListOf<Path>()
        for (snapshot in snapshots.listFiles().orEmpty().filter { it.isDirectory }) {
            val files = snapshot.walkTopDown().filter { it.isFile }.mapNotNull { file ->
                val relative = snapshot.toPath().relativize(file.toPath()).toString()
                    .replace(File.separatorChar, '/')
                if (isValidSubpath(snapshot.toPath(), relative)) RepoFile(relative, sizeBytes = file.length())
                else null
            }.toList()
            val inUse = files.asSequence().mapNotNull { file ->
                val info = splitInfo(file.path)
                info.tag.takeIf { info.index == 1 && it.isNotEmpty() && it != tag &&
                    !nameIsSidecar(info.prefix) }
            }.distinct().flatMap { otherTag ->
                val primary = findBestModel(files, otherTag)
                if (primary == null) emptySequence() else sequence {
                    yieldAll(splitFiles(files, primary).map { it.path })
                    for (keyword in sidecarKeywords) {
                        findBestSibling(files, primary.path, keyword,
                            if (keyword == "dflash-") otherTag else "")?.let { yield(it.path) }
                    }
                }
            }.toSet()
            for (file in files) {
                if (splitInfo(file.path).tag == tag && file.path !in inUse) {
                    toRemove.add(File(snapshot, file.path).toPath())
                }
            }
        }
        return removeAndCollect(repoDir, snapshots, toRemove)
    }

    private fun removeAndCollect(repoDir: File, snapshots: File, toRemove: List<Path>): Boolean {
        if (toRemove.isEmpty()) return false
        val blobs = File(repoDir, "blobs").toPath().toAbsolutePath().normalize()
        val removedBlobTargets = toRemove.mapNotNull { path -> blobTarget(path, blobs) }.toSet()
        for (path in toRemove) Files.deleteIfExists(path)
        val remainingBlobTargets = snapshots.walkTopDown().filter { Files.isSymbolicLink(it.toPath()) }
            .mapNotNull { blobTarget(it.toPath(), blobs) }.toSet()
        for (blob in removedBlobTargets - remainingBlobTargets) Files.deleteIfExists(blob)
        return true
    }

    private fun blobTarget(link: Path, blobs: Path): Path? {
        if (!Files.isSymbolicLink(link)) return null
        val target = link.parent.resolve(Files.readSymbolicLink(link)).toAbsolutePath().normalize()
        return target.takeIf { it.startsWith(blobs) }
    }

    /**
     * Removes [model]'s plan records (a bare repo: all of them) and returns whether any went, with the oids those
     * records planned in [model]'s repo that no remaining record plans.
     */
    private fun removePlans(model: HfModel): Pair<Boolean, Set<String>> {
        val tag = model.tag?.uppercase()
        val removedOids = mutableSetOf<String>()
        val keptOids = mutableSetOf<String>()
        var removed = false
        for (plan in File(cacheDir, ".llamabro/plans").listFiles().orEmpty()) {
            if (!plan.isFile || plan.extension != "json") continue
            val json = runCatching { Json.parseToJsonElement(plan.readText()).jsonObject }.getOrNull() ?: continue
            val reference = runCatching { json["model"]?.jsonPrimitive?.content?.let(HfModel::parse) }.getOrNull()
                ?: continue
            val oids = runCatching {
                json["files"]?.jsonArray.orEmpty().mapNotNull { it.jsonObject["oid"]?.jsonPrimitive?.contentOrNull }
            }.getOrDefault(emptyList()).filter(::isValidOid)
            val matches = reference.repo == model.repo && when {
                model.file != null -> reference.file == model.file
                tag == null -> true
                else -> reference.file == null && reference.tag?.uppercase() == tag
            }
            if (matches && plan.delete()) {
                removed = true
                if (reference.repo == model.repo) removedOids += oids
            } else if (reference.repo == model.repo) {
                keptOids += oids
            }
        }
        return removed to (removedOids - keptOids)
    }

    private companion object {
        val sidecarKeywords = listOf("mmproj", "mtp-", "dflash-", "eagle3-", "dspark-")
    }
}
