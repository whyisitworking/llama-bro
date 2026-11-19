package com.suhel.llamabro.hub

import com.suhel.llamabro.LogLevel
import com.suhel.llamabro.Logger
import com.suhel.llamabro.chat.Sampling
import com.suhel.llamabro.hub.internal.GgufHeader
import com.suhel.llamabro.hub.internal.HeaderFetch
import com.suhel.llamabro.hub.internal.Resolver
import com.suhel.llamabro.hub.internal.Response
import com.suhel.llamabro.hub.internal.Transport
import com.suhel.llamabro.hub.internal.findBestSibling
import com.suhel.llamabro.hub.internal.hubFailure
import com.suhel.llamabro.hub.internal.isModelGguf
import com.suhel.llamabro.hub.internal.isValidRepoId
import com.suhel.llamabro.hub.internal.splitInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.io.RandomAccessFile
import java.net.URL
import java.net.URLEncoder
import java.nio.channels.FileChannel
import java.util.UUID

/** The order of [LlamaBroHub.search]'s results, most first. */
public enum class SearchSort {
    /** Downloads in the last 30 days, the Hub's default. */
    Downloads,

    /** Likes, all time. */
    Likes,

    /** The Hub's trending score: recent likes and downloads. */
    Trending,

    /** The last commit, newest first. */
    RecentlyUpdated,
}

/** Mutable scope of [LlamaBroHub.search]. Every search is for GGUF repos. */
public class SearchOptions internal constructor() {
    /** The order of the results. */
    public var sort: SearchSort = SearchSort.Downloads

    /** Only repos of this user or organisation, such as `ggml-org`; null for anyone's. */
    public var author: String? = null

    /** Only models that take pictures as well as text (the Hub's `image-text-to-text` task). */
    public var takesImages: Boolean = false

    /** Repos per page, 1 to 100; a page may hold fewer results, since quantized uploads of one model group. */
    public var limit: Int = 40
}

/**
 * One page of [LlamaBroHub.search]: the results in the Hub's order, and where the next page starts. Grouping is per
 * page, so a later page can hold more uploads of a model already shown: for an endless list, merge each page's results
 * into the earlier ones by [SearchResult.id], ignoring case.
 */
public class SearchPage internal constructor(
    /** The models on this page, in the order of their first repo. */
    public val results: List<SearchResult>,
    /** Pass to [LlamaBroHub.search] for the next page; null on the last one. */
    public val next: SearchCursor?,
) {
    override fun toString(): String = "SearchPage(results=$results, next=${next != null})"
}

/**
 * Where a search's next page starts: the Hub's own cursor. Valid for the hub that returned it; the Hub may expire it,
 * so search again from the start rather than keeping one.
 */
public class SearchCursor internal constructor(internal val url: String)

/**
 * A model found by search: its quantized uploads grouped under the original they were made from (D68). Only repos
 * tagged `base_model:quantized:X` group under X; a fine-tune, merge or adapter is a model of its own, though it names
 * the same base (F146). Grouping is within one page: a later page can hold more uploads of the same model.
 */
public class SearchResult internal constructor(
    /** The original's repo id (`Qwen/Qwen3-4B`) for grouped uploads, else the repo's own id. */
    public val id: String,
    /** The repos found, in the Hub's order; each is a GGUF repo to pass to [LlamaBroHub.files]. */
    public val repos: List<SearchRepo>,
) {
    /** Downloads of every repo here. */
    public val downloads: Long get() = repos.sumOf { it.downloads }

    /** Likes of every repo here. */
    public val likes: Long get() = repos.sumOf { it.likes }

    /** Whether any repo here takes pictures (its Hub task, or a `vision` or `multimodal` tag). */
    public val takesImages: Boolean get() = repos.any { it.takesImages }

    override fun toString(): String = "SearchResult(id=$id, repos=$repos)"
}

/** One GGUF repo in a [SearchResult]. */
public class SearchRepo internal constructor(
    /** The repo id, `owner/name`. */
    public val id: String,
    /** Downloads in the last 30 days, as the Hub counts them. */
    public val downloads: Long,
    /** Likes, all time. */
    public val likes: Long,
    /** Whether the Hub lists it as taking pictures (its task, or a `vision` or `multimodal` tag). */
    public val takesImages: Boolean,
    /** The original this repo is a quantized upload of (its `base_model:quantized:` tag), or null. */
    public val baseModel: String?,
) {
    /** The user or organisation that uploaded it. */
    public val author: String get() = id.substringBefore('/')

    override fun toString(): String = id
}

/** A repo's GGUF files as [LlamaBroHub.files] lists them: sizes, and no verdict on whether they fit (D68). */
public class RepoFiles internal constructor(
    /** The repo id. */
    public val repo: String,
    /** The commit listed. */
    public val commit: String,
    /** Each model file once, split files as one entry with every part's bytes, in the repo's order. */
    public val quantizations: List<Quantization>,
    /** Whether the repo has an image encoder (an `mmproj` file), which [LlamaBroHub.model] fetches with the model. */
    public val hasImageEncoder: Boolean,
    /** True when the Hub could not be reached and the list is what this phone's cache holds of the repo. */
    public val offline: Boolean,
) {
    override fun toString(): String = "RepoFiles($repo@$commit, $quantizations, imageEncoder=$hasImageEncoder)"
}

/** One quantisation of a model in [RepoFiles]. */
public class Quantization internal constructor(
    /** The quantisation's name from the file name, such as `Q4_K_M`, or the file's name when it has none. */
    public val name: String,
    /** The file, or a split file's first part. */
    public val path: String,
    /** Bytes of every part. */
    public val sizeBytes: Long,
    /** How many files it is split into; 1 when whole. */
    public val parts: Int,
    /** This exact file as a reference for [LlamaBroHub.model], which fetches every part and the image encoder. */
    public val model: HfModel,
    /**
     * Bytes of the image or audio encoder (the `mmproj` file) [LlamaBroHub.model] fetches with this quantisation, by
     * the same choice it makes when it downloads (llama.cpp's `-hf` rule); 0 when the repo has none. From the listing
     * alone: no file is read (D101).
     */
    public val encoderBytes: Long,
) {
    override fun toString(): String = "$name ($sizeBytes bytes, $parts part(s), encoder $encoderBytes bytes)"
}

/**
 * A model's recommended sampling from both places the Hub can hold it, each null when absent (D68, D79). Nothing is
 * applied: [fromFile] is what loads by default, since llama.cpp applies a GGUF's own values at load (F17); the app
 * chooses whether to use [fromBaseModel] instead. Fields a source doesn't set stay null in its [Sampling].
 */
public class RecommendedSampling internal constructor(
    /** The GGUF header's `general.sampling.*`: what the model runs with unless the app sets its own. */
    public val fromFile: Sampling?,
    /**
     * The base model's `generation_config.json`, the values its authors publish; null when absent or gated.
     * `do_sample: false` reads as greedy (temperature 0); other values map as given, even where transformers would
     * read a missing `do_sample` as greedy.
     */
    public val fromBaseModel: Sampling?,
    /** The base model read for [fromBaseModel] (the repo's `base_model:quantized:` tag), or null when it has none. */
    public val baseModel: String?,
) {
    override fun toString(): String =
        "RecommendedSampling(fromFile=$fromFile, fromBaseModel=$fromBaseModel, baseModel=$baseModel)"
}

/**
 * Search, repo files and recommended sampling for [LlamaBroHub] (D68), ported from the demo's search with its
 * grouping fixed (F146). Every request goes through the hub's transport, which sends the token only to the endpoint's
 * own host and drops it on a redirect elsewhere (F82, §11.4).
 */
internal class HubSearch(endpoint: String, private val cacheDir: File, private val logger: Logger?) {
    private val endpoint = endpoint.trimEnd('/') + "/"
    private val origin = URL(this.endpoint)

    fun firstPage(query: String, options: SearchOptions): String {
        require(options.limit in 1..MAX_LIMIT) { "limit must be 1 to $MAX_LIMIT, was ${options.limit}" }
        val params = mutableListOf<String>()
        query.trim().takeIf { it.isNotEmpty() }?.let { params += "search=${encode(it)}" }
        params += "filter=gguf"
        params += "sort=${sortKey(options.sort)}"
        params += "direction=-1"
        params += "limit=${options.limit}"
        options.author
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let { params += "author=${encode(it)}" }
        if (options.takesImages) params += "pipeline_tag=$IMAGE_TASK"
        return "${endpoint}api/models?" + params.joinToString("&")
    }

    suspend fun page(transport: Transport, url: String): SearchPage {
        val answer = getJson(transport, url, "search")
        val items = answer.json as? JsonArray ?: throw invalid("search")
        return SearchPage(group(items.mapNotNull(::repoOf)), nextPage(url, answer.headers)?.let(::SearchCursor))
    }

    /** The Hub pages through `Link: <…>; rel="next"`; a next page off the endpoint's origin is not followed. */
    private fun nextPage(current: String, headers: Map<String, List<String>>): String? {
        val link = headers["link"].orEmpty().firstNotNullOfOrNull { NEXT_LINK.find(it)?.groupValues?.get(1) }
        val next = link?.let { runCatching { URL(URL(current), it) }.getOrNull() } ?: return null
        return next.toString().takeIf { sameOrigin(next, origin) }
    }

    private fun repoOf(element: JsonElement): SearchRepo? {
        val item = element as? JsonObject ?: return null
        val id = item.string("id")?.takeIf(::isValidRepoId) ?: return null
        val tags = item.tags()
        val takesImages = item.string("pipeline_tag") == IMAGE_TASK || "vision" in tags || "multimodal" in tags
        return SearchRepo(id, item.long("downloads"), item.long("likes"), takesImages, quantizedBase(tags))
    }

    /** Groups by the original, case-insensitively as the Hub does; a group sits where its first repo did. */
    private fun group(repos: List<SearchRepo>): List<SearchResult> =
        repos
            .groupBy { (it.baseModel ?: it.id).lowercase() }
            .values
            .map { members -> SearchResult(members.first().baseModel ?: members.first().id, members) }

    /** Every model GGUF once, its split parts summed (upstream's split naming), and whether a projector is there. */
    suspend fun files(resolver: Resolver, repo: String): RepoFiles {
        require(isValidRepoId(repo)) { "invalid HF repo id: $repo" }
        val listing = resolver.list(repo)
        val ggufs = listing.files.filter { it.path.endsWith(".gguf") }
        val quantizations =
            ggufs
                .filter { isModelGguf(it.path) }
                .groupBy { splitKey(it.path) }
                .values
                .map { parts ->
                    val first = parts.minBy { splitInfo(it.path).index }
                    val split = splitInfo(first.path)
                    Quantization(
                        name = split.tag.ifEmpty { first.path.substringAfterLast('/').removeSuffix(".gguf") },
                        path = first.path,
                        sizeBytes = parts.sumOf { it.sizeBytes },
                        parts = parts.size,
                        model = HfModel("$repo#${first.path}"),
                        encoderBytes = findBestSibling(listing.files, first.path, "mmproj", split.tag)?.sizeBytes ?: 0L,
                    )
                }
        val encoder = ggufs.any { it.path.substringAfterLast('/').contains("mmproj") }
        return RepoFiles(repo, listing.commit, quantizations, encoder, listing.offline)
    }

    /**
     * The original [repo] is a quantized upload of (its `base_model:quantized:` tag), or null when it has none.
     * A refusal is thrown: the caller decides what it means.
     */
    suspend fun baseModelOf(transport: Transport, repo: String): String? {
        val info = getJson(transport, "${endpoint}api/models/$repo", repo).json as? JsonObject ?: throw invalid(repo)
        return quantizedBase(info.tags())
    }

    /**
     * [base]'s `generation_config.json` as sampling (D68), or null when the file is missing, holds no sampling values,
     * or is gated and the token (if any) can't read it: a missing recommendation, not an error (F146).
     */
    @Suppress("InstanceOfCheckForException") // a boundary that classifies what it caught
    suspend fun fromBaseModel(transport: Transport, base: String): Sampling? {
        val config =
            try {
                getJson(transport, "$endpoint$base/resolve/main/generation_config.json", base).json as? JsonObject
            } catch (
                // a gated or missing base model means no values, not a failure; anything else is thrown on
                absent: HubException,
            ) {
                if (absent !is HubException.Unauthorized && absent !is HubException.NotFound) throw absent
                logger?.log(
                    LogLevel.Debug,
                    "recommendedSampling: no generation_config.json from $base: ${absent.message}",
                )
                null
            } ?: return null
        return samplingOf(config)
    }

    /** A GGUF's `general.sampling.*` (F17) from a file on the phone: no request at all (D79). */
    fun fromLocalFile(file: File): Sampling? =
        RandomAccessFile(file, "r").use { raf ->
            val bytes = raf.channel.map(FileChannel.MapMode.READ_ONLY, 0, minOf(raf.length(), HEADER_LIMIT_BYTES))
            samplingOf(GgufHeader.metadata(bytes) { it.startsWith(GGUF_SAMPLING) })
        }

    /**
     * A GGUF's `general.sampling.*` (F17) read with the hub's bounded range requests into a stub that goes when this
     * returns, also on failure or cancellation. A refusal is thrown.
     */
    suspend fun fromRemoteFile(transport: Transport, plan: HubPlan): Sampling? {
        val file = plan.files.firstOrNull { it.role == HubFile.Role.Model } ?: return null
        val folder = File(cacheDir, ".llamabro/sampling/${UUID.randomUUID()}")
        try {
            val stub = File(folder, "header.gguf")
            val header = HeaderFetch(endpoint, transport).fetch(file, stub)
            return RandomAccessFile(stub, "r").use { raf ->
                val bytes = raf.channel.map(FileChannel.MapMode.READ_ONLY, 0, header.dataOffset)
                samplingOf(GgufHeader.metadata(bytes) { it.startsWith(GGUF_SAMPLING) })
            }
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                folder.deleteRecursively()
                folder.parentFile?.delete() // the shared parent, once no other read uses it
            }
        }
    }

    /** One JSON answer and its headers. */
    private class Answer(val json: JsonElement, val headers: Map<String, List<String>>)

    private suspend fun getJson(transport: Transport, url: String, what: String): Answer =
        withContext(Dispatchers.IO) {
            transport.open("GET", url, mapOf("Accept" to "application/json")).use { response ->
                val body =
                    response.body
                        ?.bufferedReader()
                        ?.use { it.readText() }
                        .orEmpty()
                if (response.status != HTTP_OK) throw failure(response, what)
                try {
                    Answer(Json.parseToJsonElement(body), response.headers)
                } catch (e: SerializationException) {
                    throw HubException.Network("invalid Hub JSON for $what", e, true)
                }
            }
        }

    private fun invalid(what: String) = HubException.Network("invalid Hub $what response", null, true)

    private fun failure(response: Response, what: String): HubException =
        hubFailure(response, "$what needs a token with access", "$what not found", "$what: HTTP ${response.status}")

    internal companion object {
        private const val MAX_LIMIT = 100
        private const val HEADER_LIMIT_BYTES = 256L shl 20 // HeaderFetch's bound on a GGUF header
        private const val HTTP_OK = 200
        private const val IMAGE_TASK = "image-text-to-text"
        private const val QUANTIZED = "base_model:quantized:"
        private const val GGUF_SAMPLING = "general.sampling."
        private val NEXT_LINK = Regex("<([^>]+)>\\s*;\\s*rel=\"?next\"?")

        private fun sortKey(sort: SearchSort): String =
            when (sort) {
                SearchSort.Downloads -> "downloads"
                SearchSort.Likes -> "likes"
                SearchSort.Trending -> "trendingScore"
                SearchSort.RecentlyUpdated -> "lastModified"
            }

        /** F146: only `base_model:quantized:X` names a re-upload's original; `finetune:`, `merge:` etc. don't. */
        private fun quantizedBase(tags: List<String>): String? =
            tags.firstOrNull { it.startsWith(QUANTIZED) }?.removePrefix(QUANTIZED)?.takeIf(::isValidRepoId)

        /** The parts of one split file share a key (upstream's `-0000n-of-0000m` naming); a whole file is its own. */
        private fun splitKey(path: String): String =
            splitInfo(path).let { split -> if (split.count > 1) "${split.prefix}#${split.count}" else path }

        private fun JsonObject.tags(): List<String> =
            (this["tags"] as? JsonArray).orEmpty().mapNotNull { tag ->
                (tag as? JsonPrimitive)?.takeIf { it.isString }?.content
            }

        private fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())

        private fun sameOrigin(a: URL, b: URL): Boolean =
            a.protocol.equals(b.protocol, ignoreCase = true) &&
                a.host.equals(b.host, ignoreCase = true) &&
                (if (a.port >= 0) a.port else a.defaultPort) == (if (b.port >= 0) b.port else b.defaultPort)

        private fun JsonObject.string(key: String): String? =
            (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

        private fun JsonObject.long(key: String): Long = (this[key] as? JsonPrimitive)?.longOrNull ?: 0L

        /**
         * `generation_config.json` as llama-server's fields (D68): `do_sample: false` is greedy (temperature 0, which
         * llama.cpp samples greedily); otherwise each field present maps by name, `repetition_penalty` to the repeat
         * penalty. Null when none is set. In transformers a missing `do_sample` also means greedy, but a config that
         * sets values without it means them to be used, so they are mapped as given (D68's wording).
         */
        internal fun samplingOf(config: JsonObject): Sampling? {
            fun number(key: String): Double? = (config[key] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull
            if ((config["do_sample"] as? JsonPrimitive)?.booleanOrNull == false) return Sampling { temperature = 0f }
            val sampling =
                Sampling {
                    temperature = number("temperature")?.toFloat()
                    topK = number("top_k")?.toInt()
                    topP = number("top_p")?.toFloat()
                    minP = number("min_p")?.toFloat()
                    repeatPenalty = number("repetition_penalty")?.toFloat()
                    presencePenalty = number("presence_penalty")?.toFloat()
                }
            return sampling.takeIf { it != Sampling.Default }
        }

        /** A GGUF's `general.sampling.*` under upstream's names (`common/sampling.cpp`); `sequence` is not mapped. */
        internal fun samplingOf(values: Map<String, Any>): Sampling? {
            fun number(key: String): Number? = values[GGUF_SAMPLING + key] as? Number
            val sampling =
                Sampling {
                    temperature = number("temp")?.toFloat()
                    topK = number("top_k")?.toInt()
                    topP = number("top_p")?.toFloat()
                    minP = number("min_p")?.toFloat()
                    xtcProbability = number("xtc_probability")?.toFloat()
                    xtcThreshold = number("xtc_threshold")?.toFloat()
                    repeatLastN = number("penalty_last_n")?.toInt()
                    repeatPenalty = number("penalty_repeat")?.toFloat()
                    mirostat = number("mirostat")?.toInt()
                    mirostatTau = number("mirostat_tau")?.toFloat()
                    mirostatEta = number("mirostat_eta")?.toFloat()
                }
            return sampling.takeIf { it != Sampling.Default }
        }
    }
}
