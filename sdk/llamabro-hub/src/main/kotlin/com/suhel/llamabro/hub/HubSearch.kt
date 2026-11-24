package com.suhel.llamabro.hub

import com.suhel.llamabro.LogLevel
import com.suhel.llamabro.Logger
import com.suhel.llamabro.chat.Sampling
import com.suhel.llamabro.hub.internal.GgufHeader
import com.suhel.llamabro.hub.internal.HeaderFetch
import com.suhel.llamabro.hub.internal.HttpStatus
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
import java.io.InputStream
import java.io.RandomAccessFile
import java.net.URL
import java.net.URLEncoder
import java.nio.channels.FileChannel
import java.util.UUID

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
        val min = options.minParameters
        val max = options.maxParameters
        require(min == null || min >= 0) { "minParameters must not be negative, was $min" }
        require(max == null || max > 0) { "maxParameters must be positive, was $max" }
        require(min == null || max == null || min <= max) { "minParameters $min is above maxParameters $max" }
        val params = mutableListOf<String>()
        query.trim().takeIf { it.isNotEmpty() }?.let { params += "search=${encode(it)}" }
        params += "filter=gguf"
        params += "sort=${sortKey(options.sort)}"
        params += "direction=-1"
        params += "limit=${options.limit}"
        EXPANDED_FIELDS.forEach { params += "expand[]=$it" }
        options.author
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let { params += "author=${encode(it)}" }
        if (options.takesImages) params += "pipeline_tag=$IMAGE_TASK"
        listOfNotNull(min?.let { "min:$it" }, max?.let { "max:$it" })
            .takeIf { it.isNotEmpty() }
            ?.let { params += "num_parameters=" + it.joinToString(",") }
        if (options.hideGated) params += "gated=false"
        return "${endpoint}api/models?" + params.joinToString("&")
    }

    /** A cursor's page, only on the endpoint's origin: a stored cursor is text an app hands back. */
    suspend fun next(transport: Transport, cursor: SearchCursor): SearchPage {
        val url = runCatching { URL(cursor.value) }.getOrNull()
        require(url != null && sameOrigin(url, origin)) { "a search cursor must be on the hub's endpoint" }
        return page(transport, cursor.value)
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
        return SearchRepo(
            id,
            item.long("downloads"),
            item.long("likes"),
            takesImages,
            quantizedBase(tags),
            item.string("pipeline_tag"),
            tags,
            item.string("lastModified"),
            (item["gguf"] as? JsonObject)?.let(::ggufSummary),
        )
    }

    private fun ggufSummary(gguf: JsonObject): GgufSummary? {
        val parameters = gguf.long("total").takeIf { it > 0 } ?: return null
        val contextLength = (gguf["context_length"] as? JsonPrimitive)?.longOrNull
        return GgufSummary(parameters, gguf.string("architecture").orEmpty(), contextLength, "chat_template" in gguf)
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
        require(isValidRepoId(repo)) { "invalid HF repo id: $repo" }
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

    /**
     * [repo]'s `README.md` at `main` without its front matter, or null when it has none. At most [README_LIMIT_BYTES]
     * are read, so a huge card is cut short instead of failing. A refusal is thrown.
     */
    suspend fun modelCard(transport: Transport, repo: String): String? {
        require(isValidRepoId(repo)) { "invalid HF repo id: $repo" }
        val text =
            withContext(Dispatchers.IO) {
                transport.open("GET", "$endpoint$repo/resolve/main/README.md").use { response ->
                    if (response.status == HttpStatus.NOT_FOUND) return@use null
                    if (response.status != HttpStatus.OK) throw failure(response, repo)
                    val bytes = response.body?.readAtMost(README_LIMIT_BYTES) ?: ByteArray(0)
                    String(bytes, Charsets.UTF_8)
                }
            }
        return text?.let(::withoutFrontMatter)
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
                if (response.status != HttpStatus.OK) throw failure(response, what)
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
        private const val README_LIMIT_BYTES = 512 * 1024
        private const val FRONT_MATTER_FENCE = "---"

        /** [card] minus a leading `---` block (to the next line that is exactly `---`) and the blank lines after it. */
        internal fun withoutFrontMatter(card: String): String {
            val lines = card.split("\n")
            if (lines.first().trimEnd('\r') != FRONT_MATTER_FENCE) return card
            val close =
                lines.indices.drop(1).firstOrNull { lines[it].trimEnd('\r') == FRONT_MATTER_FENCE }
                    ?: return card
            return lines.drop(close + 1).dropWhile { it.isBlank() }.joinToString("\n")
        }

        private const val MAX_LIMIT = 100
        private const val HEADER_LIMIT_BYTES = 256L shl 20 // HeaderFetch's bound on a GGUF header
        private const val IMAGE_TASK = "image-text-to-text"
        private val EXPANDED_FIELDS =
            listOf("downloads", "likes", "tags", "pipeline_tag", "lastModified", "gguf")
        private const val QUANTIZED = "base_model:quantized:"
        private const val GGUF_SAMPLING = "general.sampling."
        private val NEXT_LINK = Regex("<([^>]+)>\\s*;\\s*rel=\"?next\"?")

        private fun sortKey(sort: SearchSort): String =
            when (sort) {
                SearchSort.Downloads -> "downloads"
                SearchSort.Likes -> "likes"
                SearchSort.Trending -> "trendingScore"
                SearchSort.RecentlyUpdated -> "lastModified"
                SearchSort.Newest -> "createdAt"
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

/** The stream's first [limit] bytes, or all of it when it is shorter. `InputStream.readNBytes(int)` needs API 33. */
private fun InputStream.readAtMost(limit: Int): ByteArray {
    val buffer = ByteArray(limit)
    var filled = 0
    while (filled < limit) {
        val read = read(buffer, filled, limit - filled)
        if (read < 0) break
        filled += read
    }
    return buffer.copyOf(filled)
}
