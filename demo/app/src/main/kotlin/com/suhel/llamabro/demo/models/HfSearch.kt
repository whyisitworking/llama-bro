package com.suhel.llamabro.demo.models

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** A model as a search row: every upload of one model collapsed into one entry. */
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

/** A search or tree call that failed. [retryable]: trying again later can help (network, 5xx, 429). */
class SearchFailed(message: String, val retryable: Boolean, cause: Throwable? = null) : Exception(message, cause) {
    /** The phone couldn't reach Hugging Face at all (no network), as opposed to Hugging Face answering badly. */
    val offline: Boolean get() = cause is IOException
}

/**
 * Hugging Face search (Reference E) over `HttpURLConnection`, always on `Dispatchers.IO`. The token goes only to the
 * host it was meant for: redirects are followed here, by hand, and `Authorization` is dropped when one leaves the
 * origin's scheme, host or port.
 */
class HfSearch(private val endpoint: String = "https://huggingface.co", private val token: suspend () -> String?) {
    suspend fun search(query: String): List<SearchModel> {
        val q = URLEncoder.encode(query.trim(), "UTF-8")
        val items = parseArray(get("/api/models?search=$q&filter=gguf&sort=downloads&direction=-1&limit=40"))
        return group(items.mapNotNull(::parseItem))
    }

    /** Every `.gguf` in the repo's tree except projectors; split files count once, with all shards' bytes. */
    suspend fun files(repo: String): List<RepoFile> {
        val path = repo.split('/').joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
        val entries = parseArray(get("/api/models/$path/tree/main?recursive=true"))
        val files = entries.mapNotNull(::parseFile)
        // shards of one split file share a key; the entry takes the first shard's path and the sum of all sizes
        return files.groupBy { (file, shard) -> shard?.prefix ?: file.path }.values.map { shards ->
            val first = shards.minBy { (_, shard) -> shard?.index ?: 0 }.first
            first.copy(bytes = shards.sumOf { it.first.bytes })
        }
    }

    private data class Item(
        val repo: String,
        val author: String,
        val downloads: Long,
        val sees: Boolean,
        val key: String,
        val nameSource: String,
    )

    private fun parseItem(e: JsonElement): Item? {
        val o = e as? JsonObject ?: return null
        val id = o["id"]?.jsonPrimitive?.contentOrNull ?: return null
        val tags = (o["tags"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList()
        val base = tags.firstOrNull { it.startsWith(BASE_TAG) }?.removePrefix(BASE_TAG)
        val nameSource = stripGguf((base ?: id).substringAfterLast('/'))
        val pipeline = (o["pipeline_tag"] as? JsonPrimitive)?.contentOrNull
        val sees = pipeline == "image-text-to-text" || "vision" in tags || "multimodal" in tags
        return Item(
            repo = id,
            author = id.substringBefore('/', missingDelimiterValue = ""),
            downloads = (o["downloads"] as? JsonPrimitive)?.longOrNull ?: 0L,
            sees = sees,
            key = nameSource.lowercase(),
            nameSource = nameSource,
        )
    }

    private fun group(items: List<Item>): List<SearchModel> =
        items
            .groupBy { it.key }
            .map { (key, members) ->
                val catalog = members.firstNotNullOfOrNull { Catalog.byRepo(it.repo) }
                val rep =
                    members.firstOrNull { catalog != null && it.repo.equals(catalog.key.repo, ignoreCase = true) }
                        ?: members.maxBy { it.downloads }
                SearchModel(
                    groupKey = key,
                    displayName = catalog?.realName ?: displayName(rep.nameSource),
                    repo = rep.repo,
                    author = rep.author,
                    downloads = members.sumOf { it.downloads },
                    sees = catalog?.sees ?: members.any { it.sees },
                    line = catalog?.line ?: plainLine(rep.nameSource, rep.author),
                    catalog = catalog,
                )
            }.sortedByDescending { it.downloads }

    private fun parseFile(e: JsonElement): Pair<RepoFile, Shard?>? {
        val o = e as? JsonObject ?: return null
        val path = o["path"]?.jsonPrimitive?.contentOrNull ?: return null
        if (!path.endsWith(".gguf", ignoreCase = true)) return null
        if (o["type"]?.jsonPrimitive?.contentOrNull == "directory") return null
        val name = path.substringAfterLast('/')
        if (name.contains("mmproj", ignoreCase = true)) return null
        val lfsSize = (o["lfs"] as? JsonObject)?.get("size")?.jsonPrimitive?.longOrNull
        val bytes = lfsSize ?: o["size"]?.jsonPrimitive?.longOrNull ?: 0L
        val stem = name.dropLast(".gguf".length)
        val split = SPLIT.matchEntire(stem)
        val unsplit = split?.groupValues?.get(1) ?: stem
        val quant =
            QUANT
                .find(unsplit)
                ?.groupValues
                ?.get(1)
                ?.uppercase() ?: unsplit
        val shard =
            split?.let {
                Shard(path.substringBeforeLast('/', "") + "/" + it.groupValues[1], it.groupValues[2].toInt())
            }
        return RepoFile(path, quant, bytes) to shard
    }

    private class Shard(val prefix: String, val index: Int)

    private fun parseArray(body: String): JsonArray =
        try {
            Json.parseToJsonElement(body).jsonArray
        } catch (e: SerializationException) {
            throw SearchFailed("Hugging Face sent something unexpected", retryable = false, cause = e)
        } catch (e: IllegalArgumentException) {
            throw SearchFailed("Hugging Face sent something unexpected", retryable = false, cause = e)
        }

    private suspend fun get(path: String): String {
        val first = token()
        return withContext(Dispatchers.IO) {
            var url = URL(endpoint + path)
            var auth = first?.let { "Bearer $it" }
            repeat(MAX_REDIRECTS + 1) {
                when (val hop = request(url, auth)) {
                    is Hop.Body -> {
                        return@withContext hop.text
                    }

                    is Hop.Redirect -> {
                        if (!sameOrigin(url, hop.to)) auth = null
                        url = hop.to
                    }
                }
            }
            throw SearchFailed("Hugging Face redirected too many times", retryable = false)
        }
    }

    /** What one request came to: the answer, or somewhere else to ask. */
    private sealed interface Hop {
        data class Body(val text: String) : Hop

        data class Redirect(val to: URL) : Hop
    }

    /** One request, redirects not followed. */
    private fun request(url: URL, auth: String?): Hop {
        val c = url.openConnection() as HttpURLConnection
        try {
            c.instanceFollowRedirects = false
            c.connectTimeout = TIMEOUT_MS
            c.readTimeout = TIMEOUT_MS
            c.setRequestProperty("Accept", "application/json")
            auth?.let { c.setRequestProperty("Authorization", it) }
            val code = c.responseCode
            return when {
                code in SUCCESS -> Hop.Body(c.inputStream.bufferedReader().use { it.readText() })

                code in REDIRECTS -> Hop.Redirect(redirectTarget(url, c))

                else -> throw SearchFailed(
                    "Hugging Face answered $code",
                    code >= SERVER_ERROR_FROM || code == TOO_MANY_REQUESTS || code == REQUEST_TIMEOUT,
                )
            }
        } catch (e: IOException) {
            throw SearchFailed("Couldn't reach Hugging Face", retryable = true, cause = e)
        } finally {
            c.disconnect()
        }
    }

    private fun redirectTarget(from: URL, c: HttpURLConnection): URL {
        val location =
            c.getHeaderField("Location")
                ?: throw SearchFailed("Hugging Face sent a redirect with nowhere to go", false)
        val next = URL(from, location)
        if (next.protocol != "http" && next.protocol != "https") {
            throw SearchFailed("Hugging Face redirected somewhere unsupported", false)
        }
        return next
    }

    private fun sameOrigin(a: URL, b: URL): Boolean =
        a.protocol == b.protocol && a.host.equals(b.host, ignoreCase = true) &&
            a.port.orDefault(a) == b.port.orDefault(b)

    private fun Int.orDefault(url: URL): Int = if (this == -1) url.defaultPort else this

    companion object {
        private const val BASE_TAG = "base_model:quantized:"
        private const val TIMEOUT_MS = 15_000
        private const val MAX_REDIRECTS = 5
        private val SUCCESS = 200..299
        private const val TOO_MANY_REQUESTS = 429
        private const val REQUEST_TIMEOUT = 408
        private const val SERVER_ERROR_FROM = 500
        private const val TINY_BILLIONS = 1.5
        private const val SMALL_BILLIONS = 5
        private const val MID_BILLIONS = 10
        private val REDIRECTS = setOf(301, 302, 303, 307, 308)
        private val GGUF_SUFFIX = Regex("[-_.]?gguf$", RegexOption.IGNORE_CASE)
        private val PARAMS = Regex("(?<![A-Za-z0-9.])(\\d+(?:\\.\\d+)?)B(?![A-Za-z])", RegexOption.IGNORE_CASE)
        private val SPLIT = Regex("^(.+)-([0-9]{5})-of-[0-9]{5}$")
        private val QUANT =
            Regex("(?:^|[-._])((?:UD-)?(?:I?Q\\d+(?:_[A-Z0-9]+)*|BF16|F16|F32))$", RegexOption.IGNORE_CASE)

        private fun stripGguf(name: String): String = name.replace(GGUF_SUFFIX, "")

        /** `Qwen3.5-4B` becomes `Qwen3.5 4B`: separators to spaces, the name's own casing kept. */
        private fun displayName(source: String): String =
            source
                .replace('-', ' ')
                .replace('_', ' ')
                .trim()
                .replaceFirstChar { it.uppercaseChar() }

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
    }
}
