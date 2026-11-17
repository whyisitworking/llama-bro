package com.suhel.llamabro.hub.internal

import com.suhel.llamabro.LogLevel
import com.suhel.llamabro.Logger
import com.suhel.llamabro.ModelSource
import com.suhel.llamabro.hub.DraftRequest
import com.suhel.llamabro.hub.HfModel
import com.suhel.llamabro.hub.HubException
import com.suhel.llamabro.hub.HubFile
import com.suhel.llamabro.hub.HubPlan
import com.suhel.llamabro.hub.PlanOptions
import kotlinx.coroutines.CancellationException
import java.io.File
import java.nio.file.Files
import java.nio.file.NoSuchFileException

/**
 * The copy of a selection that loads, read from disk only (D64). Upstream's offline read follows `refs/main`, but that
 * ref means the newest commit the cache has seen: an online plan or a cancelled update moves it, and a load would then
 * miss a complete older copy and download the newer one unasked, or fail offline. So the SDK keeps an installed record
 * per selection, written only when a download completes, and loads that. A cache from before the record (complete by
 * the refs rule and its plan record) is adopted as installed the first time it is read, so no one downloads again.
 */
internal class LocalCopies(
    private val cacheDir: File,
    private val resolver: Resolver,
    storage: Storage,
    private val logger: Logger? = null,
) {
    private val lookup = CacheLookup(cacheDir)
    private val plans = PlanStore(cacheDir, storage)
    private val installed = PlanStore(cacheDir, storage, PlanStore.INSTALLED)

    /** The installed record of the selection, whether or not its files are still there. */
    fun installedRecord(model: HfModel, options: PlanOptions): PlanStore.Record? = installed.read(model, options)

    /**
     * A completed download is the copy that loads from now on. The copy it replaces is then reclaimed, so an update
     * doesn't double the storage: its snapshot entries and blobs go unless another record (any selection's plan or
     * installed copy) still names them, or, for a blob, another snapshot entry links to it. The download is complete
     * either way: a reclaim that fails is logged and leaves the old files for a later update or [Cache.delete].
     */
    suspend fun install(plan: HubPlan, options: PlanOptions) {
        val previous = installed.read(plan.model, options)
        installed.write(plan, options)
        if (previous == null) return
        try {
            reclaim(plan.model, previous)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception, // installed already: no failure here may undo that
        ) {
            logger?.log(LogLevel.Warn, "kept the replaced copy of ${plan.model}: $e")
        }
    }

    /**
     * Removes what only [old] (now replaced) named: its snapshot entries, then the blobs nothing links or names. Each
     * file is decided under its blob's download lock from the records as they are then: a download of another selection
     * that reuses the blob has either written its plan and linked it by then, so it stays, or finds it gone and fetches
     * it again; it never links a blob that is then removed.
     */
    private suspend fun reclaim(model: HfModel, old: PlanStore.Record) {
        HubTestHooks.onPhase?.invoke("reclaim")
        for (file in old.files) {
            val repo = file.repo ?: model.repo
            val commit = file.commit ?: old.commit
            val repoDir = File(cacheDir, repoFolder(repo))
            val entry = File(repoDir, "snapshots/$commit/${file.path}")
            val blob = linkedBlob(entry, File(repoDir, "blobs")) ?: file.oid?.let { File(repoDir, "blobs/$it") }
            if (blob?.isFile == true) {
                Locks.withFileLock(File(cacheDir, ".locks/${repoFolder(repo)}/${blob.name}.lock")) {
                    reclaimFile(repo, commit, file, blob)
                }
            } else {
                // No blob to remove (a degraded copy holds its bytes in the entry): nothing to wait for, and a blob
                // another selection commits meanwhile is left alone
                reclaimFile(repo, commit, file, null)
            }
        }
    }

    /** Removes [file]'s entry unless a record names it, then [blob] unless a record names it or an entry links it. */
    private fun reclaimFile(repo: String, commit: String, file: PlanStore.FileRecord, blob: File?) {
        val remaining =
            listOf(PlanStore.PLANS, PlanStore.INSTALLED).flatMap { folder ->
                PlanStore.readEvery(cacheDir, folder).flatMap { (owner, record) ->
                    record.files.map { named -> Named(named.repo ?: owner.repo, named.commit ?: record.commit, named) }
                }
            }
        HubTestHooks.onPhase?.invoke("reclaimRead")
        if (remaining.any { it.repo == repo && it.commit == commit && it.file.path == file.path }) return
        removeEntry(repo, commit, file)
        if (blob == null) return
        val named = remaining.any { it.repo == repo && it.file.oid == blob.name }
        if (!named && !linkedFromSnapshots(repo, blob)) Files.deleteIfExists(blob.toPath())
    }

    /** Deletes [file]'s snapshot entry, and its folders that are left empty. */
    private fun removeEntry(repo: String, commit: String, file: PlanStore.FileRecord) {
        val repoDir = File(cacheDir, repoFolder(repo))
        val entry = File(repoDir, "snapshots/$commit/${file.path}")
        Files.deleteIfExists(entry.toPath())
        removeEmptyParents(entry.parentFile, File(repoDir, "snapshots"))
    }

    /** Whether any snapshot entry of [repo] still links to [blob]. */
    private fun linkedFromSnapshots(repo: String, blob: File): Boolean =
        File(cacheDir, "${repoFolder(repo)}/snapshots").walkTopDown().any { link ->
            linkedBlob(link, blob.parentFile)?.canonicalPath == blob.canonicalPath
        }

    /** A record's file with the repo and commit whose snapshot holds it. */
    private class Named(val repo: String, val commit: String, val file: PlanStore.FileRecord)

    /**
     * The blob in [blobs] that [entry] links to, or null when it is no link into them (a degraded copy) or a delete
     * removed it since it was listed.
     */
    private fun linkedBlob(entry: File, blobs: File): File? {
        val link = entry.toPath()
        if (!Files.isSymbolicLink(link)) return null
        val target =
            try {
                link.parent
                    .resolve(Files.readSymbolicLink(link))
                    .toAbsolutePath()
                    .normalize()
            } catch (_: NoSuchFileException) {
                return null
            }
        return target.toFile().takeIf { target.startsWith(blobs.toPath().toAbsolutePath().normalize()) }
    }

    /** Deletes [dir] and its parents up to, not including, [stop] while they are empty. */
    private fun removeEmptyParents(dir: File?, stop: File) {
        var current = dir
        while (current != null && current != stop && current.list()?.isEmpty() == true) {
            current.delete()
            current = current.parentFile
        }
    }

    /** The installed copy, else a complete copy by the refs rule (then adopted), else [HubException.NotFound]. */
    fun source(model: HfModel, options: PlanOptions): ModelSource =
        installedSource(model, options) ?: legacySource(model, options)

    /** Records a complete copy by the refs rule as installed before a listing moves `refs/main` off it. */
    fun adoptBeforeListing(model: HfModel, options: PlanOptions) {
        if (installed.read(model, options) != null) return
        try {
            legacySource(model, options)
        } catch (_: HubException.NotFound) {
            // nothing complete to keep
        }
    }

    /**
     * The model GGUF (a split file's first part) of any complete installed copy of [model], read from disk only, or
     * null when none is on the phone. The model file is the same whatever the options (an image encoder, a draft), so
     * a copy made with any of them serves, wherever `refs/main` has moved since (D64); only with no installed record is
     * an old-format copy adopted, as [source] does.
     */
    fun installedModelFile(model: HfModel): File? {
        fun modelFileOf(record: PlanStore.Record): File? =
            pathsOf(model, record)?.firstOrNull { it.first == HubFile.Role.Model }?.second?.let(::File)
        installed.readAll(model).firstNotNullOfOrNull(::modelFileOf)?.let { return it }
        return try {
            source(model, PlanOptions())
            installed.read(model, PlanOptions())?.let(::modelFileOf)
        } catch (_: HubException.NotFound) {
            null
        }
    }

    /** Each installed file's role and path when every one is on disk at its recorded size, else null. */
    private fun installedPaths(model: HfModel, options: PlanOptions): List<Pair<HubFile.Role, String>>? =
        installed.read(model, options)?.let { pathsOf(model, it) }

    private fun pathsOf(model: HfModel, record: PlanStore.Record): List<Pair<HubFile.Role, String>>? =
        record.files.map { file ->
            val repo = file.repo ?: model.repo
            val path = File(cacheDir, "${repoFolder(repo)}/snapshots/${file.commit ?: record.commit}/${file.path}")
            if (!path.isFile || path.length() != file.size) return null
            file.role to path.absolutePath
        }

    /** The installed copy when every one of its files is on disk at its recorded size, else null. */
    fun installedSource(model: HfModel, options: PlanOptions): ModelSource? {
        val paths = installedPaths(model, options) ?: return null
        val primary = paths.firstOrNull { it.first == HubFile.Role.Model }?.second ?: return null
        return ModelSource.file(primary) {
            mmproj = paths.firstOrNull { it.first == HubFile.Role.Mmproj }?.second
            draft = paths.firstOrNull { it.first == HubFile.Role.Draft }?.second
        }
    }

    /** Today's upstream rule: the snapshot `refs/main` names, checked against the plan record when there is one. */
    private fun legacySource(model: HfModel, options: PlanOptions): ModelSource {
        installed.write(model, options, legacyRecord(model, options))
        return requireNotNull(installedSource(model, options)) { "adopted copy of $model is incomplete" }
    }

    /**
     * Whether [source] would load [model] from disk now, by the same rules, with nothing written: the installed copy
     * is complete, or one by the refs rule would be adopted.
     */
    fun loads(model: HfModel, options: PlanOptions): Boolean =
        installedPaths(model, options) != null ||
            try {
                pathsOf(model, legacyRecord(model, options)) != null
            } catch (_: HubException.NotFound) {
                false
            }

    /** What [legacySource] adopts: the refs rule's selection, checked against the plan record when there is one. */
    private fun legacyRecord(model: HfModel, options: PlanOptions): PlanStore.Record {
        val listing =
            resolver.cachedListing(model.repo)
                ?: throw HubException.NotFound("model is not cached: $model", emptyList())
        val record = plans.read(model, options)
        record?.let { requireRecordedFilesCached(it, model, options) }
        val selected =
            resolvePlan(
                listing.files,
                model.tag.orEmpty(),
                model.file.orEmpty(),
                options.mmproj,
                options.draft?.from == null && options.draft != null,
            )
        if (selected.modelFiles.isEmpty() || !isCompleteSplit(selected)) {
            throw HubException.NotFound("model is not cached: $model", emptyList())
        }

        fun local(file: RepoFile, role: HubFile.Role, repo: String, commit: String): PlanStore.FileRecord {
            val path = lookup.snapshotPath(repo, commit, file)
            val oid = record?.files?.firstOrNull { it.path == file.path }?.oid ?: linkedOid(path)
            return PlanStore.FileRecord(file.path, role, File(path).length(), oid, repo, commit)
        }
        val files =
            selected.modelFiles
                .map {
                    local(
                        it,
                        HubFile.Role.Model,
                        model.repo,
                        listing.commit,
                    )
                }.toMutableList()
        selected.mmproj?.let { files += local(it, HubFile.Role.Mmproj, model.repo, listing.commit) }
        options.draft?.let { draft ->
            val (repo, commit, file) = draftFile(draft, model, selected, listing)
            files += local(file, HubFile.Role.Draft, repo, commit)
        }
        return PlanStore.Record(listing.commit, files)
    }

    /** The oid a snapshot entry links to, which names its blob; null in a degraded (copied) cache. */
    private fun linkedOid(path: String): String? {
        val link = File(path).toPath()
        if (!Files.isSymbolicLink(link)) return null
        return Files
            .readSymbolicLink(link)
            .fileName
            .toString()
            .takeIf(::isValidOid)
    }

    /** A plan record that exists vouches for its files: every one must be on disk at its recorded size. */
    private fun requireRecordedFilesCached(record: PlanStore.Record, model: HfModel, options: PlanOptions) {
        val alternate = options.draft?.from
        val draftCommit = alternate?.let { resolver.cachedListing(it.repo)?.commit }
        for (file in record.files) {
            val draftSource = alternate.takeIf { file.role == HubFile.Role.Draft }
            val repo = draftSource?.repo ?: model.repo
            val commit = if (draftSource != null) draftCommit else record.commit
            val cached = commit?.let { File(cacheDir, "${repoFolder(repo)}/snapshots/$it/${file.path}") }
            if (cached?.isFile != true || cached.length() != file.size) {
                throw HubException.NotFound("file is not cached: ${file.path}", emptyList())
            }
        }
    }

    /** The draft as (repo, commit, file): the DFlash file beside the model, or the one the request names. */
    private fun draftFile(
        request: DraftRequest,
        model: HfModel,
        selected: Resolved,
        listing: RepoListing,
    ): Triple<String, String, RepoFile> {
        val from = request.from
        val found =
            if (from == null) {
                selected.dflash?.let { Triple(model.repo, listing.commit, it) }
            } else {
                namedDraft(from)
            }
        return found ?: throw HubException.NotFound("draft is not cached: ${from ?: model}", emptyList())
    }

    private fun namedDraft(from: HfModel): Triple<String, String, RepoFile>? {
        val draftListing = resolver.cachedListing(from.repo) ?: return null
        val choice = resolvePlan(draftListing.files, from.tag.orEmpty(), from.file.orEmpty(), false, true)
        val files = choice.dflash?.let { listOf(it) } ?: choice.modelFiles.takeIf { it.isNotEmpty() } ?: return null
        files.forEach { lookup.snapshotPath(from.repo, draftListing.commit, it) }
        return Triple(from.repo, draftListing.commit, files.first())
    }
}

/** Offline listings hold only the parts on disk: every part of a split model must be there. */
internal fun isCompleteSplit(selected: Resolved): Boolean {
    val first = selected.modelFiles.firstOrNull() ?: return false
    val count = splitInfo(first.path).count
    return count <= 1 || selected.modelFiles.size == count
}
