package com.suhel.llamabro.hub.internal

import com.suhel.llamabro.hub.DraftRequest
import com.suhel.llamabro.hub.HfModel
import com.suhel.llamabro.hub.HubException
import com.suhel.llamabro.hub.HubFile
import com.suhel.llamabro.hub.HubPlan
import com.suhel.llamabro.hub.PlanOptions
import com.suhel.llamabro.hub.spi.Storage
import com.suhel.llamabro.hub.spi.Transport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Owns what a selection (a model and its [PlanOptions]) resolves to: which files of a repo's listing it names, as a
 * plan from the Hub ([plan]) and as a check against the cached listing alone ([isCachedComplete]). Both go through
 * [select] and [draftSelection], and every [Resolver] is built here, so a download and a progress read cannot choose
 * different files for one selection: if they could, `progress` would call a selection Complete that `download`
 * resolves differently.
 *
 * [hubTransport] is the hub's own; it serves no request here, since a cached read never leaves the disk. A [plan] is
 * given the transport of its operation (the one carrying the app's token).
 */
internal class Planner(
    private val endpoint: String,
    private val cacheDir: File,
    private val storage: Storage,
    private val lookup: CacheLookup,
    private val plans: PlanStore,
    private val hubTransport: Transport,
) {
    /** A resolver over [transport]; a [look] never moves a ref and answers from the cache only when offline. */
    fun resolver(transport: Transport, look: Boolean = false): Resolver =
        Resolver(endpoint, cacheDir, transport, storage, look)

    /** The cached listing of [repo], with no network request; null when nothing of it is cached. */
    fun cachedListing(repo: String): RepoListing? = resolver(hubTransport).cachedListing(repo)

    /** What [model] with [options] picks out of [listing]: the model's files and the sidecars asked for. */
    fun select(listing: RepoListing, model: HfModel, options: PlanOptions): Resolved =
        resolvePlan(
            listing.files,
            model.tag.orEmpty(),
            model.file.orEmpty(),
            options.mmproj,
            options.draft?.from == null && options.draft != null,
        )

    /** The files a draft from [from]'s [listing] is: its DFlash file, else its model's files; null when neither. */
    fun draftSelection(listing: RepoListing, from: HfModel): List<RepoFile>? {
        val selected = resolvePlan(listing.files, from.tag.orEmpty(), from.file.orEmpty(), false, true)
        return selected.dflash?.let { listOf(it) } ?: selected.modelFiles.takeIf { it.isNotEmpty() }
    }

    /**
     * Resolves [model] online (falling back to the cache only when the Hub can't be reached). With [record] false the
     * plan is a look only: no ref or plan record moves, and a refusal is thrown rather than answered from the cache.
     */
    suspend fun plan(model: HfModel, options: PlanOptions, transport: Transport, record: Boolean): HubPlan =
        withContext(Dispatchers.IO) {
            val resolver = resolver(transport, look = !record)
            val listing = resolver.list(model.repo)
            val selected = select(listing, model, options)
            val available = listing.files.map { it.path }.filter { it.endsWith(".gguf") }
            if (selected.primary == null) throw noMatch(model, listing.offline, available)
            val chosen =
                lookup
                    .hubFiles(
                        selected.modelFiles,
                        HubFile.Role.Model,
                        model.repo,
                        listing.commit,
                    ).toMutableList()
            selected.mmproj?.let {
                chosen +=
                    lookup.hubFiles(listOf(it), HubFile.Role.Mmproj, model.repo, listing.commit)
            }
            var offline = listing.offline
            options.draft?.let { draft ->
                val found = draftFiles(resolver, model, draft.from, selected, listing, available)
                chosen += found.files
                offline = offline || found.offline
            }
            val plan = HubPlan(model, listing.commit, chosen, offline, notesFor(listing))
            plan.partialBytes = lookup.partialBytes(chosen)
            if (!offline && record) plans.write(plan, options)
            plan
        }

    /**
     * Whether the cached listings alone hold every file [model] with [options] selects: a whole split model, and a
     * draft the options ask for. What `progress` calls Complete when no record of the selection survives.
     */
    fun isCachedComplete(model: HfModel, options: PlanOptions): Boolean {
        val listing = cachedListing(model.repo) ?: return false
        val selected = select(listing, model, options)
        return isCompleteSplit(selected) && draftCached(options.draft, selected)
    }

    private fun draftCached(draft: DraftRequest?, selected: Resolved): Boolean {
        if (draft == null) return true
        val from = draft.from ?: return selected.dflash != null
        val listing = cachedListing(from.repo) ?: return false
        return draftSelection(listing, from) != null
    }

    /** Why nothing matched: offline, the cache only lacks it and the Hub may have it, so a worker should retry. */
    private fun noMatch(model: HfModel, offline: Boolean, available: List<String>): HubException =
        if (offline) {
            HubException.Network("offline, and no cached GGUF matches $model", null, true, unreachable = true)
        } else {
            HubException.NotFound("no GGUF matches $model", available)
        }

    /** The draft files of a plan, and whether looking them up fell back to the cache. */
    private class DraftFiles(val files: List<HubFile>, val offline: Boolean)

    /** A DFlash draft of the model's own repo ([from] null), or the one the caller named. */
    private suspend fun draftFiles(
        resolver: Resolver,
        model: HfModel,
        from: HfModel?,
        selected: Resolved,
        listing: RepoListing,
        available: List<String>,
    ): DraftFiles {
        if (from == null) {
            val draft =
                selected.dflash
                    ?: throw HubException.NotFound(
                        "no dflash- file in ${model.repo} for ${model.tag.orEmpty()}",
                        available,
                    )
            return DraftFiles(
                lookup.hubFiles(listOf(draft), HubFile.Role.Draft, model.repo, listing.commit),
                listing.offline,
            )
        }
        val draftListing = resolver.list(from.repo)
        val draftFiles =
            draftSelection(draftListing, from)
                ?: throw HubException.NotFound("no DFlash draft or model in $from", emptyList())
        return DraftFiles(
            lookup.hubFiles(draftFiles, HubFile.Role.Draft, from.repo, draftListing.commit),
            draftListing.offline,
        )
    }

    private fun notesFor(listing: RepoListing): List<String> =
        if (listing.files.any { it.path == "preset.ini" }) {
            listOf("preset.ini ignored: llama-server router presets aren't supported")
        } else {
            emptyList()
        }
}
