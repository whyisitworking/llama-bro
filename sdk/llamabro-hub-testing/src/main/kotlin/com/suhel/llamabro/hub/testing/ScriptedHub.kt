package com.suhel.llamabro.hub.testing

import com.suhel.llamabro.chat.Sampling
import com.suhel.llamabro.hub.spi.Response
import com.suhel.llamabro.hub.spi.Storage
import com.suhel.llamabro.hub.spi.Transport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.net.URLDecoder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.Locale

/** A file of a scripted repo, its digests computed once. */
internal class ScriptedFile(val path: String, val bytes: ByteArray) {
    val sha256: String = hex(MessageDigest.getInstance("SHA-256").digest(bytes))

    /** Git's blob id, which the Hub lists for a file outside LFS. */
    val gitOid: String =
        hex(
            MessageDigest
                .getInstance("SHA-1")
                .apply { update("blob ${bytes.size}\u0000".toByteArray(Charsets.UTF_8)) }
                .digest(bytes),
        )

    val isGguf: Boolean get() = path.endsWith(".gguf")
}

/** One commit of a scripted repo. */
private class Commit(val id: String, val order: Int, val repo: FakeLlamaBroHub.Repo, val files: List<ScriptedFile>)

/**
 * The Hugging Face Hub's routes the hub uses (search, model info, refs, tree, resolve with ranges), answered from the
 * repos a test declared, the way huggingface.co answers them (D75). Nothing leaves the process.
 */
internal class ScriptedHub(endpoint: String) : Transport {
    private val endpoint = endpoint.trimEnd('/') + "/"
    private val lock = Any()
    private val repos = LinkedHashMap<String, MutableList<Commit>>()
    private val log = mutableListOf<String>()
    private var commits = 0
    private var rateLimited = 0
    private var retryAfter: Long? = null
    private val stops = mutableMapOf<String, CompletableDeferred<Unit>>()

    @Volatile var downloadMillis: Long = 0

    @Volatile var offline: Boolean = false

    fun requests(): List<String> = synchronized(lock) { log.toList() }

    fun publish(id: String, repo: FakeLlamaBroHub.Repo) {
        synchronized(lock) {
            val order = ++commits
            val commit = Commit("%040x".format(Locale.ROOT, order), order, repo, repo.files.toList() + readmeOf(repo))
            repos.getOrPut(id) { mutableListOf() } += commit
        }
    }

    private fun readmeOf(repo: FakeLlamaBroHub.Repo): List<ScriptedFile> =
        listOfNotNull(repo.readme?.let { ScriptedFile("README.md", it.toByteArray(Charsets.UTF_8)) })

    /**
     * Makes the next download of [path] in [repo] stop halfway: its body sends the first half, completes the returned
     * signal, then holds until the request is cancelled. [clearStop] drops a stop that was never reached.
     */
    fun stopAt(repo: String, path: String): CompletableDeferred<Unit> =
        CompletableDeferred<Unit>().also { reached -> synchronized(lock) { stops["$repo/$path"] = reached } }

    fun clearStop(repo: String, path: String) {
        synchronized(lock) { stops.remove("$repo/$path") }
    }

    fun rateLimit(retryAfterSeconds: Long?, times: Int) {
        synchronized(lock) {
            rateLimited = times
            retryAfter = retryAfterSeconds
        }
    }

    override suspend fun open(
        method: String,
        url: String,
        headers: Map<String, String>,
        range: Long?,
        rangeEnd: Long?,
    ): Response {
        val job = currentCoroutineContext()[Job]
        return synchronized(lock) {
            log += "$method $url" + (range?.let { " bytes=$it-${rangeEnd ?: ""}" }.orEmpty())
            if (offline) throw IOException("FakeLlamaBroHub is offline")
            if (rateLimited > 0) {
                rateLimited--
                val retryHeaders = retryAfter?.let { mapOf("retry-after" to "$it") }
                return@synchronized answer(url, TOO_MANY_REQUESTS, "{}", retryHeaders)
            }
            route(method, url, range, rangeEnd, job)
        }
    }

    private fun route(
        method: String,
        url: String,
        range: Long?,
        rangeEnd: Long?,
        job: Job?,
    ): Response {
        if (!url.startsWith(endpoint)) return notFound(url)
        val uri = URI(url)
        val path = uri.path.removePrefix("/").split('/')
        if (path == listOf("api", "models")) return search(url, query(uri))
        val api = path.size >= API_REPO_PARTS && path[0] == "api" && path[1] == "models"
        val resolve = !api && path.size >= RESOLVE_PARTS && path[2] == "resolve"
        val id = (if (api) path.subList(2, API_REPO_PARTS) else path.take(2)).joinToString("/")
        val history = repos[id]
        return when {
            (!api && !resolve) || history == null -> notFound(url)
            history.last().repo.gated -> answer(url, UNAUTHORIZED, """{"error":"gated"}""")
            api -> api(url, id, history, path.drop(API_REPO_PARTS))
            else -> resolve(method, url, history, path, Range(range, rangeEnd), job)
        }
    }

    /** `api/models/{owner}/{name}` and its [tail]: nothing, `refs`, or `tree/{commit}`. */
    private fun api(url: String, id: String, history: List<Commit>, tail: List<String>): Response {
        val latest = history.last()
        return when {
            tail.isEmpty() -> {
                answer(url, OK, info(id, latest).toString())
            }

            tail == listOf("refs") -> {
                val refs =
                    buildJsonObject {
                        putJsonArray("branches") {
                            addJsonObject {
                                put("name", "main")
                                put("targetCommit", latest.id)
                            }
                        }
                    }
                answer(url, OK, refs.toString())
            }

            tail.size == 2 && tail[0] == "tree" -> {
                history
                    .firstOrNull { it.id == tail[1] }
                    ?.let { answer(url, OK, tree(it).toString()) }
                    ?: notFound(url)
            }

            else -> {
                notFound(url)
            }
        }
    }

    /** `{owner}/{name}/resolve/{revision}/{path}`: HEAD, a whole GET, or a byte range. */
    private fun resolve(
        method: String,
        url: String,
        history: List<Commit>,
        path: List<String>,
        range: Range,
        job: Job?,
    ): Response {
        val revision = path[REVISION]
        val name = path.drop(RESOLVE_PARTS - 1).joinToString("/")
        val commit = if (revision == "main") history.last() else history.firstOrNull { it.id == revision }
        val file = commit?.files?.firstOrNull { it.path == name } ?: return notFound(url)
        val size = file.bytes.size.toLong()
        val head = mapOf("accept-ranges" to listOf("bytes"))
        if (method == "HEAD") return Response(OK, head + length(size), url, null) {}
        val start = range.first ?: 0L
        if (start >= size && size > 0) return answer(url, RANGE_NOT_SATISFIABLE, "")
        val end = minOf(range.last ?: (size - 1), size - 1)
        val slice = file.bytes.copyOfRange(start.toInt(), (end + 1).toInt())
        // a header read (bounded) answers at once; a download is paced so its progress can be seen
        val stop = if (range.last == null) stops.remove("${path[0]}/${path[1]}/$name") else null
        val body =
            when {
                stop != null -> Stopping(slice, job, stop)
                range.last == null -> Paced(slice, downloadMillis, job)
                else -> slice.inputStream()
            }
        val headers = head + length(slice.size.toLong())
        return if (range.first == null) {
            Response(OK, headers, url, body) {}
        } else {
            Response(
                PARTIAL_CONTENT,
                headers + ("content-range" to listOf("bytes $start-$end/$size")),
                url,
                body,
            ) {}
        }
    }

    /**
     * `api/models?search=…&author=…&pipeline_tag=…&num_parameters=min:…,max:…&gated=false&sort=…&limit=…`, paged
     * through a `Link` header. A repo with no listed parameter count is outside any range.
     */
    private fun search(url: String, params: Map<String, String>): Response {
        val query = params["search"].orEmpty().lowercase()
        val author = params["author"]?.lowercase()
        val images = params["pipeline_tag"] == IMAGE_TASK
        val range = parameterRange(params["num_parameters"])
        val hideGated = params["gated"] == "false"
        val found =
            repos.entries
                .map { (id, history) -> id to history.last() }
                .filter { (id, commit) ->
                    commit.files.any { it.isGguf } &&
                        query in id.lowercase() &&
                        (author == null || id.substringBefore('/').lowercase() == author) &&
                        (!images || commit.repo.takesImages) &&
                        (range == null || commit.repo.parameters?.let { it in range } == true) &&
                        (!hideGated || !commit.repo.gated)
                }.sortedWith(order(params["sort"]))
        val offset = params["cursor"]?.toIntOrNull() ?: 0
        val limit = params["limit"]?.toIntOrNull() ?: found.size
        val page = found.drop(offset).take(limit)
        val items = JsonArray(page.map { (id, commit) -> info(id, commit) })
        val next = offset + limit
        val link =
            if (next < found.size) {
                val base = url.replace(Regex("&cursor=[^&]*"), "")
                mapOf("link" to "<$base&cursor=$next>; rel=\"next\"")
            } else {
                null
            }
        return answer(url, OK, items.toString(), link)
    }

    /** When [id] was created: its first commit's order. */
    private fun firstOrder(id: String): Int = repos[id]?.first()?.order ?: 0

    /** `min:1000,max:4000` (either part optional) as a range; null when absent. */
    private fun parameterRange(value: String?): LongRange? {
        if (value == null) return null
        val parts = value.split(',').associate { it.substringBefore(':') to it.substringAfter(':').toLong() }
        return (parts["min"] ?: 0L)..(parts["max"] ?: Long.MAX_VALUE)
    }

    /**
     * The Hub's orders, most first; trending reads as downloads, a later commit as more recently updated, and a repo
     * scripted later as newer.
     */
    private fun order(sort: String?): Comparator<Pair<String, Commit>> =
        when (sort) {
            "likes" -> compareByDescending { it.second.repo.likes }
            "lastModified" -> compareByDescending { it.second.order }
            "createdAt" -> compareByDescending { firstOrder(it.first) }
            else -> compareByDescending { it.second.repo.downloads }
        }

    private fun info(id: String, commit: Commit) =
        buildJsonObject {
            put("id", id)
            put("downloads", commit.repo.downloads)
            put("likes", commit.repo.likes)
            put(
                "pipeline_tag",
                commit.repo.task ?: if (commit.repo.takesImages) IMAGE_TASK else "text-generation",
            )
            putJsonArray("tags") {
                if (commit.files.any { it.isGguf }) add(JsonPrimitive("gguf"))
                commit.repo.tags.forEach { add(JsonPrimitive(it)) }
                commit.repo.baseModel?.let { base ->
                    add(JsonPrimitive("base_model:$base"))
                    add(JsonPrimitive("base_model:quantized:$base"))
                }
            }
            commit.repo.parameters?.let { parameters ->
                putJsonObject("gguf") {
                    put("total", parameters)
                    put("architecture", commit.repo.architecture)
                    if (commit.repo.chatTemplate) put("chat_template", "{{ messages }}")
                }
            }
        }

    /** As the Hub lists a tree: GGUFs in LFS with their SHA-256, other files by their git blob id. */
    private fun tree(commit: Commit) =
        buildJsonArray {
            for (file in commit.files) {
                addJsonObject {
                    put("type", "file")
                    put("path", file.path)
                    put("size", file.bytes.size)
                    put("oid", file.gitOid)
                    if (file.isGguf) {
                        putJsonObject("lfs") {
                            put("oid", file.sha256)
                            put("size", file.bytes.size)
                        }
                    }
                }
            }
        }

    private fun length(size: Long) = mapOf("content-length" to listOf("$size"))

    private fun notFound(url: String) = answer(url, NOT_FOUND, """{"error":"not found"}""")

    private fun answer(url: String, status: Int, body: String, headers: Map<String, String>? = null): Response {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val all = length(bytes.size.toLong()) + headers.orEmpty().mapValues { listOf(it.value) }
        return Response(status, all, url, bytes.inputStream()) {}
    }

    private fun query(uri: URI): Map<String, String> =
        uri.rawQuery
            .orEmpty()
            .split('&')
            .filter { '=' in it }
            .associate { pair ->
                val (key, value) = pair.split('=', limit = 2)
                decode(key) to decode(value)
            }

    private companion object {
        const val API_REPO_PARTS = 4
        const val RESOLVE_PARTS = 5
        const val REVISION = 3 // owner, name, "resolve", revision
        const val IMAGE_TASK = "image-text-to-text"

        fun decode(value: String): String = URLDecoder.decode(value, Charsets.UTF_8.name())
    }
}

/** A request's `Range`: the first byte and the optional last, both null for the whole file. */
private class Range(val first: Long?, val last: Long?)

/**
 * A download body in [STEPS] pieces over [millis], so the hub's progress reports the bytes as they move. The pause
 * between pieces ends as soon as [job] (the request's caller) is cancelled, so a cancel isn't held up by it.
 */
private class Paced(private val bytes: ByteArray, private val millis: Long, private val job: Job?) : InputStream() {
    private var position = 0
    private val step = maxOf(1, (bytes.size + STEPS - 1) / STEPS)

    override fun read(): Int {
        val one = ByteArray(1)
        return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and BYTE_MASK
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (position >= bytes.size) return -1
        pause(millis / STEPS)
        val count = minOf(length, step, bytes.size - position)
        System.arraycopy(bytes, position, buffer, offset, count)
        position += count
        return count
    }

    private fun pause(total: Long) {
        val until = System.nanoTime() + total * NANOS_PER_MILLI
        while (job?.isCancelled != true) {
            val left = (until - System.nanoTime()) / NANOS_PER_MILLI
            if (left <= 0) return
            Thread.sleep(minOf(left, SLICE_MILLIS))
        }
    }

    private companion object {
        const val STEPS = 4
        const val BYTE_MASK = 0xff
        const val SLICE_MILLIS = 5L
        const val NANOS_PER_MILLI = 1_000_000L
    }
}

/**
 * A download body that sends the first half of [bytes], completes [reached], then holds as a stalled connection does
 * until [job] (the request's caller) is cancelled, and fails then: what a Pause leaves is a half partial file.
 */
private class Stopping(
    private val bytes: ByteArray,
    private val job: Job?,
    private val reached: CompletableDeferred<Unit>,
) : InputStream() {
    private val half = bytes.size / 2
    private var position = 0

    override fun read(): Int {
        val one = ByteArray(1)
        return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and BYTE_MASK
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (position < half) {
            val count = minOf(length, half - position)
            System.arraycopy(bytes, position, buffer, offset, count)
            position += count
            return count
        }
        reached.complete(Unit)
        while (job?.isCancelled == false) Thread.sleep(SLICE_MILLIS)
        throw IOException("FakeLlamaBroHub stopped this download")
    }

    private companion object {
        const val BYTE_MASK = 0xff
        const val SLICE_MILLIS = 5L
    }
}

/** The phone's disk as a test sets it: [freeBytes] free, and nothing to sync. */
internal class ScriptedStorage : Storage {
    @Volatile var freeBytes: Long = Long.MAX_VALUE

    override fun allocatableBytes(dir: File): Long = freeBytes

    override fun allocate(dir: File, bytes: Long) {
        if (bytes > freeBytes) throw IOException("FakeLlamaBroHub: $bytes bytes asked, $freeBytes free")
    }

    override fun fsyncDir(dir: File) = Unit
}

/** GGUF files of metadata only, valid for the hub's header reads and for `FakeLlamaBro`'s loads. */
internal object Gguf {
    private const val VERSION = 3
    private const val ALIGNMENT = 32
    private const val TYPE_I32 = 5
    private const val TYPE_F32 = 6
    private const val TYPE_BOOL = 7
    private const val TYPE_STRING = 8

    /**
     * A GGUF of [size] bytes: a header with [name] as `general.name`, [sampling] as `general.sampling.*`,
     * [chatTemplate] as `tokenizer.chat_template` and [extra] as given (Boolean, String, Int or Float), then zeros.
     * The name makes each file's bytes, and so its digest, its own: the cache stores equal bytes once.
     */
    fun bytes(
        name: String,
        sampling: Sampling?,
        size: Int,
        chatTemplate: String? = null,
        extra: Map<String, Any> = emptyMap(),
    ): ByteArray {
        val out = ByteArrayOutputStream()
        val metadata =
            mapOf<String, Any>("general.name" to name) +
                sampling?.let(::keys).orEmpty() +
                listOfNotNull(chatTemplate?.let { "tokenizer.chat_template" to it }) +
                extra

        fun put(count: Int, fill: ByteBuffer.() -> Unit) =
            out.write(
                ByteBuffer
                    .allocate(count)
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .apply(fill)
                    .array(),
            )
        out.write("GGUF".toByteArray(Charsets.US_ASCII))
        put(Int.SIZE_BYTES) { putInt(VERSION) }
        put(Long.SIZE_BYTES) { putLong(0) } // no tensors
        put(Long.SIZE_BYTES) { putLong(metadata.size.toLong()) }

        fun string(value: String) {
            val raw = value.toByteArray(Charsets.UTF_8)
            put(Long.SIZE_BYTES) { putLong(raw.size.toLong()) }
            out.write(raw)
        }
        for ((key, value) in metadata) {
            string(key)
            when (value) {
                is String -> put(Int.SIZE_BYTES) { putInt(TYPE_STRING) }.also { string(value) }
                is Boolean -> put(Int.SIZE_BYTES + 1) { putInt(TYPE_BOOL).put(if (value) 1 else 0) }
                is Float -> put(Int.SIZE_BYTES + Float.SIZE_BYTES) { putInt(TYPE_F32).putFloat(value) }
                else -> put(Int.SIZE_BYTES * 2) { putInt(TYPE_I32).putInt((value as Number).toInt()) }
            }
        }
        val header = (out.size() + ALIGNMENT - 1) / ALIGNMENT * ALIGNMENT
        require(size >= header) { "a GGUF with this header needs at least $header bytes, was $size" }
        return out.toByteArray().copyOf(size)
    }

    /** The `general.sampling.*` keys upstream reads (common/sampling.cpp), under its names. */
    private fun keys(sampling: Sampling): Map<String, Number> =
        listOfNotNull(
            sampling.temperature?.let { "temp" to it },
            sampling.topK?.let { "top_k" to it },
            sampling.topP?.let { "top_p" to it },
            sampling.minP?.let { "min_p" to it },
            sampling.xtcProbability?.let { "xtc_probability" to it },
            sampling.xtcThreshold?.let { "xtc_threshold" to it },
            sampling.repeatLastN?.let { "penalty_last_n" to it },
            sampling.repeatPenalty?.let { "penalty_repeat" to it },
            sampling.mirostat?.let { "mirostat" to it },
            sampling.mirostatTau?.let { "mirostat_tau" to it },
            sampling.mirostatEta?.let { "mirostat_eta" to it },
        ).associate { (key, value) -> "general.sampling.$key" to value }
}

private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(Locale.ROOT, it) }

// the scripted hub's own statuses: a test double stays independent of the code it stands in for
private const val OK = 200
private const val PARTIAL_CONTENT = 206
private const val UNAUTHORIZED = 401
private const val NOT_FOUND = 404
private const val RANGE_NOT_SATISFIABLE = 416
private const val TOO_MANY_REQUESTS = 429
