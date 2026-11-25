package com.suhel.llamabro.demo.models

import com.suhel.llamabro.hub.GgufSummary
import com.suhel.llamabro.hub.HubException
import com.suhel.llamabro.hub.LlamaBroHub
import com.suhel.llamabro.hub.SearchCursor
import com.suhel.llamabro.hub.SearchRepo
import com.suhel.llamabro.hub.SearchResult

/**
 * A model as a search row: the SDK's grouped result (D68) with the app's catalog entry. [groupKey] is the model's name
 * in lower case, as a suggestion's row is keyed. [name] is the Hub's own name, as stored; the name a row shows is
 * [displayName], derived when read, so a change to how names are cleaned never relabels a stored row when its refresh
 * lands.
 */
data class SearchModel(
    val groupKey: String,
    val name: String,
    val repo: String,
    val author: String,
    val downloads: Long,
    val sees: Boolean,
    val catalog: CatalogEntry?,
    /** Who made the original: the catalog's maker, else the owner of the repo the upload was quantized from. */
    val maker: String? = null,
    val capabilities: List<Capability> = emptyList(),
    /** Whether the model has a chat template and is not an embedding model: the app can chat with it. */
    val canChat: Boolean = true,
    /** The parameter count from the Hub's GGUF header (the catalog's own for a model it knows), or null. */
    val parameters: Long? = null,
    /** A mixture-of-experts model's parameters used per token, from its name; null for any other. */
    val activeParameters: Long? = null,
    /** The original the upload was quantized from (the catalog's, else the Hub's `base_model`); its page is shown. */
    val baseModel: String? = null,
    /** Every upload of the model the row stands for ([repo] is the representative), as the Hub names them. */
    val repos: List<String> = emptyList(),
) {
    /** The name a row shows: the catalog's, else [name] cleaned ([Names.display]). */
    val displayName: String get() = catalog?.realName ?: Names.display(name)
}

/** One page of a search's rows, in the Hub's order, and where the next page starts (null on the last). */
data class SearchRowsPage(val rows: List<SearchModel>, val next: String?)

/** What a model does, in the order a row shows it. */
enum class Capability { Thinks, Talks, SeesImages, Coding, Embeddings }

/** The phone couldn't reach Hugging Face at all, as opposed to Hugging Face answering badly. */
val HubException.offline: Boolean get() = this is HubException.Network && unreachable

/**
 * Finding models through the SDK's hub (D68): search and a repo's files. Open so the phone tests can stand in sizes
 * the fake hub can't hold.
 * The view models take its functions, not the class, so tests pass plain lambdas.
 */
open class ModelDiscovery(private val hub: LlamaBroHub) {
    /** One page of the Hub's GGUF repos for [query], most downloaded first, a row per model: a repo's facts. */
    open suspend fun search(query: String): List<SearchModel> =
        SearchRows
            .merge(hub.search(query.trim()) { limit = SEARCH_LIMIT }.results.map(SearchRows::of))
            .sortedByDescending { it.downloads }

    /**
     * A page of [query]'s rows in the Hub's order, a row per model, and the cursor of the next page (null on the last):
     * the first with no [cursor], else the one it names. Gated repos are always left out (models-search design §4):
     * the app can't accept a model's terms.
     */
    open suspend fun searchPage(query: HubQuery, cursor: String?): SearchRowsPage {
        val page =
            if (cursor == null) {
                hub.search(query.text) {
                    sort = query.sort
                    minParameters = query.minParameters
                    maxParameters = query.maxParameters
                    takesImages = query.seesImages
                    hideGated = true
                    limit = SEARCH_LIMIT
                }
            } else {
                hub.search(SearchCursor(cursor))
            }
        return SearchRowsPage(SearchRows.merge(page.results.map(SearchRows::of)), page.next?.value)
    }

    /**
     * Every quantisation in [repo] once, split files summed, with the encoder the hub would fetch with it; the encoder
     * is not one of them.
     */
    open suspend fun files(repo: String): List<RepoFile> =
        hub.files(repo).quantizations.map { RepoFile(it.path, it.name, it.sizeBytes, it.encoderBytes) }

    /** [repo]'s page, its `README.md` without the front matter; null when it has none. */
    open suspend fun page(repo: String): String? = hub.modelCard(repo)

    /** The original [repo] was quantized from, as the Hub tags it; null when it names none. */
    open suspend fun baseModel(repo: String): String? = hub.baseModel(repo)

    /**
     * What [url] opens: a model's card when it names a repo with GGUF uploads (its own, or those quantized from it),
     * else the browser; also when the Hub can't be asked.
     */
    open suspend fun related(url: String): LinkTarget {
        val repo = HubLinks.repoOf(url) ?: return LinkTarget.Browser(url)
        return try {
            val uploads =
                hub.search("") {
                    quantizedFrom = repo
                    hideGated = true
                    limit = SEARCH_LIMIT
                }
            val quantized = SearchRows.merge(uploads.results.map(SearchRows::of)).firstOrNull()
            val found =
                quantized
                    ?: SearchRows
                        .merge(hub.search(repo.substringAfter('/')).results.map(SearchRows::of))
                        .firstOrNull { row -> (row.repos + row.repo).any { it.equals(repo, ignoreCase = true) } }
            if (found != null) LinkTarget.Card(found) else LinkTarget.Browser(url)
        } catch (_: HubException) {
            LinkTarget.Browser(url)
        }
    }

    private companion object {
        /** The Hub's page: enough to scroll, one request. */
        const val SEARCH_LIMIT = 40
    }
}

/** A search result as the app names it (demo design §5): the catalog's name, else from the repo's name. */
object SearchRows {
    fun of(result: SearchResult): SearchModel {
        val catalog = result.repos.firstNotNullOfOrNull { Catalog.byRepo(it.id) }
        // the catalog's own upload stands for a model it knows; otherwise the most downloaded one
        val rep =
            result.repos.firstOrNull { catalog != null && it.id.equals(catalog.key.repo, ignoreCase = true) }
                ?: result.repos.maxBy { it.downloads }
        val nameSource = stripGguf(result.id.substringAfterLast('/'))
        val gguf = rep.gguf
        val sees = catalog?.sees ?: result.takesImages
        val embeds = embeds(rep, gguf, nameSource)
        return SearchModel(
            groupKey = Names.key(result.id.substringAfterLast('/')),
            name = nameSource,
            repo = rep.id,
            author = rep.author,
            downloads = result.downloads,
            sees = sees,
            catalog = catalog,
            maker =
                catalog?.maker
                    ?: result.repos
                        .firstNotNullOfOrNull { it.baseModel }
                        ?.substringBefore('/')
                        ?.let(Names::maker),
            capabilities = capabilities(catalog, rep, sees, embeds, nameSource),
            canChat = !embeds && (gguf?.hasChatTemplate ?: true),
            parameters = gguf?.parameters ?: catalog?.parameters,
            activeParameters = gguf?.let { Guess.activeParameters(nameSource, it.architecture) },
            baseModel = catalog?.baseModel ?: result.repos.firstNotNullOfOrNull { it.baseModel },
            repos = result.repos.map { it.id },
        )
    }

    private fun embeds(rep: SearchRepo, gguf: GgufSummary?, name: String): Boolean {
        val architecture = gguf?.architecture.orEmpty()
        return rep.task in EMBEDDING_TASKS ||
            "bert" in architecture ||
            "embedding" in architecture ||
            "embedding" in name.lowercase()
    }

    private fun capabilities(
        catalog: CatalogEntry?,
        rep: SearchRepo,
        sees: Boolean,
        embeds: Boolean,
        name: String,
    ): List<Capability> =
        buildSet {
            if (catalog?.thinks == true) add(Capability.Thinks)
            if (catalog?.talks == true) add(Capability.Talks)
            if (sees || rep.task == IMAGE_TASK) add(Capability.SeesImages)
            if ("code" in rep.tags || CODER.containsMatchIn(name)) add(Capability.Coding)
            if (embeds) add(Capability.Embeddings)
        }.sorted()

    /**
     * One row per name, where its first upload was (the Hub's order is kept). The SDK groups only uploads tagged as
     * quantized from one original (D68); results it leaves apart can still share a name, and a row's key is its name,
     * as a suggestion's is (a download started from either shows on both), so a list never holds two rows with one
     * key. The row the catalog knows stands for the name, as in [of], else the most downloaded one.
     */
    fun merge(rows: List<SearchModel>): List<SearchModel> =
        rows
            .groupBy { it.groupKey }
            .values
            .map { same ->
                val first = same.firstOrNull { it.catalog != null } ?: same.maxBy { it.downloads }
                // the representative's facts stand; where it has none, the first merged row that has them fills in
                val sized = if (first.parameters != null) first else same.firstOrNull { it.parameters != null } ?: first
                val sees = first.catalog?.sees ?: same.any { it.sees }
                // what any of them can do, in a row's order; seeing images as `sees` says, so icon and fit agree
                val capabilities = same.flatMap { it.capabilities }.toSortedSet().toList()
                first.copy(
                    downloads = same.sumOf { it.downloads },
                    sees = sees,
                    capabilities = capabilities.filter { it != Capability.SeesImages || sees },
                    parameters = sized.parameters,
                    activeParameters = sized.activeParameters,
                    canChat = if (first.parameters != null) first.canChat else sized.canChat,
                    maker = first.maker ?: same.firstNotNullOfOrNull { it.maker },
                    baseModel = first.baseModel ?: same.firstNotNullOfOrNull { it.baseModel },
                    repos = same.flatMap { it.repos }.distinct(),
                )
            }

    private fun stripGguf(name: String): String = name.replace(GGUF_SUFFIX, "")

    /** `Coder`, `StarCoder`, but not `Encoder` or `Decoder`, which name architectures. */
    private val CODER = Regex("(?<!en|de)coder", RegexOption.IGNORE_CASE)
    private const val IMAGE_TASK = "image-text-to-text"
    private val EMBEDDING_TASKS = setOf("feature-extraction", "sentence-similarity", "text-ranking")
    private val GGUF_SUFFIX = Regex("[-_.]?gguf$", RegexOption.IGNORE_CASE)
}
