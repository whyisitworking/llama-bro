package com.suhel.llamabro.hub.testing

import com.suhel.llamabro.chat.Sampling
import com.suhel.llamabro.hub.DownloadState
import com.suhel.llamabro.hub.HfModel
import com.suhel.llamabro.hub.HubConfig
import com.suhel.llamabro.hub.HubException
import com.suhel.llamabro.hub.HubFile
import com.suhel.llamabro.hub.LlamaBroHub
import com.suhel.llamabro.hub.PlanOptions
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.Closeable
import java.io.File
import java.nio.file.Files
import kotlin.time.Duration.Companion.milliseconds

/**
 * A [LlamaBroHub] for an app's JVM tests: no phone, no network
 * ([D70](https://github.com/whyisitworking/llama-bro/blob/main/docs/decisions.md#d70),
 * [D75](https://github.com/whyisitworking/llama-bro/blob/main/docs/decisions.md#d75)). Pass [hub] to the code under
 * test. It is the real hub, over a scripted Hugging Face Hub and a temporary folder, so `model`, `download`,
 * `progress`, `hasUpdate`, `search`, `files`, `recommendedSampling`, `capabilities` and the installed-copy rule
 * ([D64](https://github.com/whyisitworking/llama-bro/blob/main/docs/decisions.md#d64)) run as in the app, as
 * `FakeLlamaBro` runs the real `Model` over a scripted engine
 * ([D27](https://github.com/whyisitworking/llama-bro/blob/main/docs/decisions.md#d27)).
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
 * `InsufficientStorage`. The hub takes no token provider, since a token sends requests over the real network
 * ([F82](https://github.com/whyisitworking/llama-bro/blob/main/docs/research/findings.md#f82)).
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
     * cancelled download stops within a few milliseconds and keeps its partial file, as on the phone
     * ([F114](https://github.com/whyisitworking/llama-bro/blob/main/docs/research/findings.md#f114) to
     * [F116](https://github.com/whyisitworking/llama-bro/blob/main/docs/research/findings.md#f116)).
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

    /**
     * Leaves [model]'s download as a stopped one leaves it on the phone (a Pause, or the app killed): the files the
     * plan takes before [path] (a file of its plan, which takes them in order: model parts, image encoder, draft) in
     * the cache, [path] half received, the ones after it not there; its plan recorded and no copy installed. So
     * `hub.cached()` leaves it out, `hub.source` throws `HubException.NotFound`, `hub.progress` reads
     * `DownloadState.Partial`, and a `download` resumes it, fetching only the rest. A copy installed before stays the
     * one that loads, and stays listed. [repo] is the repo [path] comes from: the model's, or for a draft from another
     * repo ([PlanOptions.draft]'s `from`), that one.
     *
     * @throws IllegalArgumentException if [path] is not a file the download would fetch from [repo] (not in the plan,
     * or in the cache already), or is too small to stop halfway (under 2 bytes).
     */
    public suspend fun stopDownload(
        model: HfModel,
        path: String,
        repo: String = model.repo,
        configure: PlanOptions.() -> Unit = {},
    ) {
        val pending = hub.plan(model, configure).files.filterNot { it.cached }
        // a file of another repo can only be the draft; in the model's own repo, the draft is a dflash- file beside it
        val named = pending.filter { it.path == path }
        val file =
            if (repo == model.repo) {
                named.firstOrNull { it.role != HubFile.Role.Draft } ?: named.firstOrNull()
            } else {
                named.firstOrNull { it.role == HubFile.Role.Draft }
            }
        requireNotNull(file) {
            "$model's download fetches ${pending.map { it.path }.ifEmpty { "nothing" }}, not $repo/$path"
        }
        require(file.sizeBytes >= 2) { "$repo/$path is ${file.sizeBytes} bytes: too small to stop halfway" }
        val reached = scripted.stopAt(repo, path)
        try {
            coroutineScope {
                val download =
                    launch {
                        try {
                            hub.download(model, configure).collect()
                            reached.completeExceptionally(IllegalStateException("$model downloaded without $path"))
                        } catch (failed: HubException) {
                            reached.completeExceptionally(failed)
                        }
                    }
                try {
                    reached.await()
                } finally {
                    download.cancelAndJoin()
                }
            }
        } finally {
            scripted.clearStop(repo, path)
        }
        // the hub reads the stopped state from disk once the transfer has let go; wait for it, so progress reads it
        hub.progress(model, configure).first { it !is DownloadState.Downloading }
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

        /** The Hub's task (`pipeline_tag`); null lists `text-generation`, or the image task if [takesImages]. */
        public var task: String? = null

        /** Extra Hub tags, beside `gguf` and the `base_model:` ones. */
        public var tags: List<String> = emptyList()

        /** The GGUF parameter count the Hub lists (`gguf.total`); null lists no `gguf` summary at all. */
        public var parameters: Long? = null

        /** The architecture listed in the summary, when [parameters] is set. */
        public var architecture: String = "llama"

        /** Whether the listed summary has a chat template; false is how an embedding model lists. */
        public var chatTemplate: Boolean = true

        /** The repo's README text, as the Hub serves it; null serves none. */
        public var readme: String? = null

        /** Whether the repo needs a token: every request for it answers 401, `HubException.Unauthorized`. */
        public var gated: Boolean = false

        internal val files = mutableListOf<ScriptedFile>()

        /**
         * A GGUF file at [path] (`Q8_0/model-Q8_0-00001-of-00002.gguf` for a split part, `mmproj-…` for an image
         * encoder, `dflash-…` for a draft), [sizeBytes] long: a valid header naming the file and holding [sampling]
         * as its `general.sampling.*` keys (the ones a GGUF can hold), [chatTemplate] as `tokenizer.chat_template` and
         * [metadata] (Boolean, String, Int or Float values) as further keys, e.g. `clip.has_vision_encoder` on a
         * projector, which `capabilities` reads; then zeros. The bytes are kept in memory: keep it small.
         */
        public fun gguf(
            path: String,
            sizeBytes: Int = DEFAULT_GGUF_BYTES,
            sampling: Sampling? = null,
            chatTemplate: String? = null,
            metadata: Map<String, Any> = emptyMap(),
        ) {
            files += ScriptedFile(path, Gguf.bytes(path, sampling, sizeBytes, chatTemplate, metadata))
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
