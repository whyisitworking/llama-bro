package com.suhel.llamabro.hub.testing

import com.suhel.llamabro.chat.Sampling
import com.suhel.llamabro.hub.HubConfig
import com.suhel.llamabro.hub.LlamaBroHub
import java.io.Closeable
import java.io.File
import java.nio.file.Files
import kotlin.time.Duration.Companion.milliseconds

/**
 * A [LlamaBroHub] for an app's JVM tests: no phone, no network (D70, D75). Pass [hub] to the code under test. It is the
 * real hub, over a scripted Hugging Face Hub and a temporary folder, so `model`, `download`, `progress`, `hasUpdate`,
 * `search`, `files`, `recommendedSampling` and the installed-copy rule (D64) run as in the app, as `FakeLlamaBro` runs
 * the real `Model` over a scripted engine (D27).
 *
 * ```
 * val fakeHub = FakeLlamaBroHub {
 *     repo("unsloth/Qwen3-4B-GGUF") {
 *         baseModel = "Qwen/Qwen3-4B"
 *         downloads = 900
 *         gguf("Qwen3-4B-Q4_K_M.gguf")
 *         gguf("mmproj-F16.gguf")
 *     }
 *     repo("Qwen/Qwen3-4B") { generationConfig("""{"temperature":0.6}""") }
 * }
 * val vm = ModelsViewModel(fakeHub.hub, FakeLlamaBro().llamaBro)
 * …
 * fakeHub.offline = true    // HubException.Network from here on
 * ```
 * Downloads write the scripted files, so `llama.loadModel(fakeHub.hub.model("unsloth/Qwen3-4B-GGUF:Q4_K_M"))` on a
 * `FakeLlamaBro` fetches and loads them. Failures, as `HubException`s: a repo not declared is `NotFound`, a
 * [Repo.gated] one `Unauthorized`; [rateLimit], [offline] and [freeBytes] give `RateLimited`, `Network` and
 * `InsufficientStorage`. The hub takes no token provider, since a token sends requests over the real network (F82).
 */
public class FakeLlamaBroHub(script: FakeLlamaBroHub.() -> Unit = {}) : Closeable {
    private val cacheDir: File = Files.createTempDirectory("llamabro-hub-fake").toFile()

    // a backstop for a test that never calls close(): the folder still goes when the JVM exits
    private val cleanup = Thread { cacheDir.deleteRecursively() }.also { Runtime.getRuntime().addShutdownHook(it) }
    private val scripted = ScriptedHub(ENDPOINT)
    private val storage = ScriptedStorage()

    /** The [LlamaBroHub] to hand to the code under test. */
    public val hub: LlamaBroHub =
        LlamaBroHub.withTransport(
            scripted,
            storage,
            HubConfig {
                endpoint = ENDPOINT
                cacheDir = this@FakeLlamaBroHub.cacheDir
                progressInterval = 100.milliseconds // the shortest allowed, so a paced download shows its steps
            },
        )

    /**
     * How long each file's download takes, sent in four steps. With 0 a download may report only its start and its
     * end; set it to a few hundred milliseconds to see the bytes move, or to cancel one midway and resume it. A
     * cancelled download stops within a few milliseconds and keeps its partial file, as on the phone (F114-F116).
     */
    public var downloadMillis: Long
        get() = scripted.downloadMillis
        set(value) {
            scripted.downloadMillis = value
        }

    /** While true every request fails as with no network: `HubException.Network`, or the cache where it has one. */
    public var offline: Boolean
        get() = scripted.offline
        set(value) {
            scripted.offline = value
        }

    /** The bytes the phone has free; a download needing more fails with `HubException.InsufficientStorage`. */
    public var freeBytes: Long
        get() = storage.freeBytes
        set(value) {
            storage.freeBytes = value
        }

    /**
     * Every request the hub made, as `"GET <url>"` or `"HEAD <url>"`, with ` bytes=<first>-[<last>]` for a ranged one
     * (a resumed download, a header read), in order: none means no network was used.
     */
    public val requests: List<String> get() = scripted.requests()

    init {
        script()
    }

    /**
     * Deletes the fake's cache folder and everything downloaded into it. The [hub] must not be used afterwards. Without
     * a call the folder goes when the JVM exits.
     */
    override fun close() {
        cacheDir.deleteRecursively()
        try {
            Runtime.getRuntime().removeShutdownHook(cleanup)
        } catch (_: IllegalStateException) {
            // the JVM is already exiting, and the hook does the same
        }
    }

    /**
     * Declares the repo [id] (`owner/name`), or publishes a new commit of it when it is declared already (which
     * `hasUpdate` reports when its files differ from the installed copy). The files of earlier commits stay readable.
     */
    public fun repo(id: String, block: Repo.() -> Unit = {}) {
        require(id.count { it == '/' } == 1 && !id.startsWith('/') && !id.endsWith('/')) {
            "a repo id is owner/name, was $id"
        }
        scripted.publish(id, Repo().apply(block))
    }

    /** Answers the next [times] requests with HTTP 429: `HubException.RateLimited` with [retryAfterSeconds]. */
    public fun rateLimit(retryAfterSeconds: Long? = null, times: Int = 1) {
        require(times > 0) { "times must be positive" }
        scripted.rateLimit(retryAfterSeconds, times)
    }

    /** One repo as the Hub lists it, built in [repo]. */
    public class Repo internal constructor() {
        /** The original this repo is a quantized upload of (its `base_model:quantized:` tag); search groups by it. */
        public var baseModel: String? = null

        /** Downloads, the default search order. */
        public var downloads: Long = 0

        /** Likes. */
        public var likes: Long = 0

        /** Whether it takes pictures (the Hub's `image-text-to-text` task), for search's filter. */
        public var takesImages: Boolean = false

        /** Whether the repo needs a token: every request for it answers 401, `HubException.Unauthorized`. */
        public var gated: Boolean = false

        internal val files = mutableListOf<ScriptedFile>()

        /**
         * A GGUF file at [path] (`Q8_0/model-Q8_0-00001-of-00002.gguf` for a split part, `mmproj-…` for an image
         * encoder, `dflash-…` for a draft), [sizeBytes] long: a valid header naming the file and holding [sampling]
         * as its `general.sampling.*` keys (the ones a GGUF can hold), then zeros. The bytes are kept in memory: keep
         * it small.
         */
        public fun gguf(path: String, sizeBytes: Int = DEFAULT_GGUF_BYTES, sampling: Sampling? = null) {
            files += ScriptedFile(path, Gguf.bytes(path, sampling, sizeBytes))
        }

        /** The repo's `generation_config.json`, as the base model's authors publish it (`recommendedSampling`). */
        public fun generationConfig(json: String) {
            files.removeAll { it.path == GENERATION_CONFIG }
            files += ScriptedFile(GENERATION_CONFIG, json.toByteArray(Charsets.UTF_8))
        }
    }

    private companion object {
        /** A reserved name (RFC 2606): nothing can reach it, so no test request leaves the machine. */
        private const val ENDPOINT = "https://hub.invalid/"
        private const val DEFAULT_GGUF_BYTES = 4096
        private const val GENERATION_CONFIG = "generation_config.json"
    }
}
