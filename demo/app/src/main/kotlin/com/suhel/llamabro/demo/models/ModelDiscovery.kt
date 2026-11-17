package com.suhel.llamabro.demo.models

import com.suhel.llamabro.LlamaBro
import com.suhel.llamabro.hub.HfModel
import com.suhel.llamabro.hub.HubException
import com.suhel.llamabro.hub.HubFile
import com.suhel.llamabro.hub.LlamaBroHub
import com.suhel.llamabro.hub.SearchResult

/**
 * A model as a search row: the SDK's grouped result (D68) with the app's name, line and catalog entry. [groupKey] is
 * the model's name in lower case, as a suggestion's row is keyed.
 */
data class SearchModel(
    val groupKey: String,
    val displayName: String,
    val repo: String,
    val author: String,
    val downloads: Long,
    val sees: Boolean,
    val line: String,
    val catalog: CatalogEntry?,
)

/** The phone couldn't reach Hugging Face at all, as opposed to Hugging Face answering badly. */
val HubException.offline: Boolean get() = this is HubException.Network && unreachable

/**
 * Finding models through the SDK's hub (D68): search, a repo's files, the SDK's memory projection of one file set, and
 * the quick fit of a model the list can't afford a real estimate for (demo design §7.1). Open so the phone tests can
 * hold a row's fit back, and stand in sizes the fake hub can't hold. The view models take its functions, not the
 * class, so tests pass plain lambdas.
 */
open class ModelDiscovery(
    private val hub: LlamaBroHub,
    private val llamaBro: LlamaBro,
    private val facts: suspend () -> DeviceFacts,
) {
    /** One page of the Hub's GGUF repos for [query], most downloaded first, a row per model. */
    open suspend fun search(query: String): List<SearchModel> =
        SearchRows.merge(hub.search(query.trim()) { limit = SEARCH_LIMIT }.results.map(SearchRows::of))

    /** Every quantisation in [repo] once, split files summed; the image reader is not one of them. */
    open suspend fun files(repo: String): List<RepoFile> =
        hub.files(repo).quantizations.map { RepoFile(it.path, it.name, it.sizeBytes) }

    /**
     * The files of [model] and the SDK's projection of them (the network and the GGUF headers). The fit verdict made
     * from it is the app's ([Fits.real]); the SDK makes none (D64).
     */
    open suspend fun estimate(model: HfModel): HubEstimate {
        val plan = hub.plan(model)
        val fit = llamaBro.estimateFit(hub.model(model))

        fun bytes(role: HubFile.Role) = plan.files.filter { it.role == role }.sumOf { it.sizeBytes }
        return HubEstimate(
            modelBytes = bytes(HubFile.Role.Model),
            mmprojBytes = bytes(HubFile.Role.Mmproj),
            fits = fit.fits,
            contextTokens = fit.contextTokens,
            memoryBytes = fit.bytesAtMinimum,
            chip = fit.device,
        )
    }

    /**
     * How the model in [repo] runs here, from the size of its Recommended file: at once for a [catalog] model (the
     * sizes ship with the app), one tree call for any other; null when the repo has no model file to size.
     */
    open suspend fun quickFit(repo: String, catalog: CatalogEntry?): FitView? {
        val device = facts()
        if (catalog != null) return Fits.quick(catalog, device)
        val recommended = Sizes.tiers(files(repo))[SizeTier.Recommended] ?: return null
        return Fits.quick(recommended.bytes, recommended.bytes, device)
    }

    private companion object {
        /** The Hub's page: enough to scroll, one request. */
        const val SEARCH_LIMIT = 40
    }
}

/** A search result as the app names it (demo design §5): the catalog's name and line, else from the repo's name. */
object SearchRows {
    fun of(result: SearchResult): SearchModel {
        val catalog = result.repos.firstNotNullOfOrNull { Catalog.byRepo(it.id) }
        // the catalog's own upload stands for a model it knows; otherwise the most downloaded one
        val rep =
            result.repos.firstOrNull { catalog != null && it.id.equals(catalog.key.repo, ignoreCase = true) }
                ?: result.repos.maxBy { it.downloads }
        val nameSource = stripGguf(result.id.substringAfterLast('/'))
        return SearchModel(
            groupKey = nameSource.lowercase(),
            displayName = catalog?.realName ?: displayName(nameSource),
            repo = rep.id,
            author = rep.author,
            downloads = result.downloads,
            sees = catalog?.sees ?: result.takesImages,
            line = catalog?.line ?: plainLine(nameSource, rep.author),
            catalog = catalog,
        )
    }

    /**
     * One row per name, most downloaded first. The SDK groups only uploads tagged as quantized from one original
     * (D68); results it leaves apart can still share a name, and a row's key is its name, as a suggestion's is (a
     * download started from either shows on both), so a list never holds two rows with one key. The row the catalog
     * knows stands for the name, as in [of], else the most downloaded one.
     */
    fun merge(rows: List<SearchModel>): List<SearchModel> =
        rows
            .groupBy { it.groupKey }
            .values
            .map { same ->
                val first = same.firstOrNull { it.catalog != null } ?: same.maxBy { it.downloads }
                first.copy(
                    downloads = same.sumOf { it.downloads },
                    sees = first.catalog?.sees ?: same.any { it.sees },
                )
            }.sortedByDescending { it.downloads }

    /** The plain line for a model the app doesn't know by name, from the parameter count in its name. */
    fun plainLine(name: String, author: String): String {
        val billions =
            PARAMS
                .find(name)
                ?.groupValues
                ?.get(1)
                ?.toDoubleOrNull()
                ?: return "A model from $author"
        return when {
            billions < TINY_BILLIONS -> "Tiny and quick, for everyday questions"
            billions < SMALL_BILLIONS -> "Balanced, a good all-rounder"
            billions < MID_BILLIONS -> "Smarter, but heavy for a phone"
            else -> "Needs a computer, not a phone"
        }
    }

    /** `Qwen3.5-4B` becomes `Qwen3.5 4B`: separators to spaces, the name's own casing kept. */
    fun displayName(source: String): String =
        source
            .replace('-', ' ')
            .replace('_', ' ')
            .trim()
            .replaceFirstChar { it.uppercaseChar() }

    private fun stripGguf(name: String): String = name.replace(GGUF_SUFFIX, "")

    private const val TINY_BILLIONS = 1.5
    private const val SMALL_BILLIONS = 5
    private const val MID_BILLIONS = 10
    private val GGUF_SUFFIX = Regex("[-_.]?gguf$", RegexOption.IGNORE_CASE)
    private val PARAMS = Regex("(?<![A-Za-z0-9.])(\\d+(?:\\.\\d+)?)B(?![A-Za-z])", RegexOption.IGNORE_CASE)
}
