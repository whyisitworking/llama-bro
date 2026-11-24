package com.suhel.llamabro.hub

import android.content.Context
import androidx.annotation.CheckResult
import com.suhel.llamabro.DownloadPhase
import com.suhel.llamabro.DownloadProgress
import com.suhel.llamabro.InternalLlamaBroApi
import com.suhel.llamabro.LogLevel
import com.suhel.llamabro.Logger
import com.suhel.llamabro.ModelSource
import com.suhel.llamabro.RemoteModelSource
import com.suhel.llamabro.chat.Sampling
import com.suhel.llamabro.hub.internal.AndroidStorage
import com.suhel.llamabro.hub.internal.BlobDownloader
import com.suhel.llamabro.hub.internal.Cache
import com.suhel.llamabro.hub.internal.CacheLookup
import com.suhel.llamabro.hub.internal.DownloadStates
import com.suhel.llamabro.hub.internal.HttpTransport
import com.suhel.llamabro.hub.internal.LocalCopies
import com.suhel.llamabro.hub.internal.Locks
import com.suhel.llamabro.hub.internal.NetworkFailures
import com.suhel.llamabro.hub.internal.PlanStore
import com.suhel.llamabro.hub.internal.Planner
import com.suhel.llamabro.hub.internal.ProgressMeter
import com.suhel.llamabro.hub.internal.Storage
import com.suhel.llamabro.hub.internal.Transfers
import com.suhel.llamabro.hub.internal.Transport
import com.suhel.llamabro.hub.internal.isUnreachable
import com.suhel.llamabro.hub.internal.repoFolder
import com.suhel.llamabro.spi.LogcatLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Resolves and caches Hugging Face models under an app-private directory. The public facade: it owns the operations
 * (download, delete, search, sampling) and the delete gate, and wires the parts that hold the rest: the [Planner]
 * (which files a selection is), [DownloadStates] (what each selection shows) and the cache, plan and search helpers.
 */
@Suppress("TooManyFunctions") // the public facade: each function is one operation of the API
public class LlamaBroHub internal constructor(
    internal val config: HubConfig,
    internal val cacheDir: File,
    internal val transport: Transport,
    internal val storage: Storage,
    internal val openOutput: (File, Boolean) -> FileOutputStream = { file, append -> FileOutputStream(file, append) },
    internal val createSymlink: (Path, Path) -> Unit = { link, target ->
        Files.createSymbolicLink(link, target)
        Unit
    },
    internal val parallelDownloads: Int = 1,
    defaultLogger: Logger? = null,
) {
    init {
        require(parallelDownloads >= 1) { "parallelDownloads must be at least 1, was $parallelDownloads" }
    }

    // D66/F144: the app's logger wins; with none, lines go to the default (logcat `llamabro-hub` from create).
    private val logger: Logger? = config.effectiveLogger(defaultLogger)
    private val degradedSymlinks = AtomicBoolean(false)
    private val transfers = Transfers()

    // The models a delete is removing now, one entry per running delete: a download of one of them fails fast.
    private val deleting = mutableListOf<HfModel>()
    private val plans: PlanStore = PlanStore(cacheDir, storage)
    private val lookup = CacheLookup(cacheDir)
    private val copies = LocalCopies(cacheDir, storage, logger)
    private val planner = Planner(config.endpoint, cacheDir, storage, lookup, plans, transport)
    private val states = DownloadStates(plans, copies, lookup, planner, logger)
    private val searches = HubSearch(config.endpoint, cacheDir, logger)

    internal suspend fun operationTransport(): Transport =
        transportFor(config.token?.invoke(), config, transport, logger)

    /**
     * The model [ref] names (`owner/repo[:tag][#file]`, as `-hf`) as a source for `llama.loadModel` and
     * `llama.estimateFit` (D64, D75). Building it does no I/O. On load, the installed copy (the last download that
     * completed) loads with no network request at all; with none it resolves and downloads as `-hf` does (F82),
     * resuming a partial (F114-F116), and the load reports the download as `LoadProgress.Fetching`. [plan] never
     * changes what loads; a newer commit replaces the installed copy only when the app asks ([hasUpdate], then
     * [download]) and that download completes. `estimateFit` on it reads the installed files when complete, else only
     * GGUF headers.
     *
     * The source's failures reach `loadModel` and `estimateFit` unchanged, as [HubException]s:
     * [HubException.Network] offline with nothing installed (the first launch with no connection),
     * [HubException.Unauthorized] for a gated repo, [HubException.NotFound] for a removed repo or a tag it lacks, and,
     * from `loadModel` only, [HubException.InsufficientStorage].
     *
     * @throws IllegalArgumentException if [ref] is not a valid Hub reference.
     */
    public fun model(ref: String, configure: PlanOptions.() -> Unit = {}): RemoteModelSource =
        model(HfModel.parse(ref), configure)

    /** [model] as a source to load or fit; see the `String` overload. */
    public fun model(model: HfModel, configure: PlanOptions.() -> Unit = {}): RemoteModelSource =
        HubModelSource(this, model, configure)

    /**
     * Whether the Hub's newest commit of [model] has files that differ from the installed copy (the last download that
     * completed): an online look that changes nothing in the cache. False when nothing is installed (with no request)
     * or when the Hub can't be reached (no route to it). [download] fetches the newest files; until it completes,
     * [model] and [source] keep loading the installed copy (D64). Once it completes, the files only the replaced copy
     * used are removed, so an update doesn't double the storage; files another selection or tag uses stay.
     *
     * @throws HubException.Unauthorized or [HubException.RateLimited] when the Hub refuses the look,
     * [HubException.NotFound] when the repo is gone, and [HubException.Network] when the Hub answers with an error
     * (a 5xx, a malformed answer), so "no update" is never a guess.
     */
    public suspend fun hasUpdate(model: HfModel, configure: PlanOptions.() -> Unit = {}): Boolean =
        withContext(Dispatchers.IO) {
            val options = PlanOptions().apply(configure)
            if (copies.installedSource(model, options) == null) {
                logger?.log(LogLevel.Debug, "hasUpdate: nothing installed: $model")
                return@withContext false
            }
            val installed = copies.installedRecord(model, options) ?: return@withContext false
            val latest =
                try {
                    planInternal(model, options, operationTransport(), record = false)
                } catch (offline: HubException.Network) {
                    if (!isUnreachable(offline)) throw offline // the Hub answered: an error, not "offline"
                    logger?.log(LogLevel.Info, "hasUpdate: Hub unreachable, no update: ${offline.message}")
                    return@withContext false
                }
            // Same selection, same bytes: every file by path, role, size and digest (an unknown digest never matches).
            val newest = latest.files.map { listOf(it.path, it.role, it.sizeBytes, it.oid) }.toSet()
            val held = installed.files.mapNotNull { f -> f.oid?.let { listOf(f.path, f.role, f.size, it) } }.toSet()
            !latest.offline && newest != held
        }

    /**
     * Searches the Hub's GGUF repos for [query] (empty for all), grouping each model's quantized uploads under the
     * original (D68, F146). Sizes come from [files]; whether one fits is the app's call (`memoryBudgetBytes()` or
     * `llama.estimateFit(hub.model(…))`). The token goes only to the endpoint's host (F82).
     *
     * @throws HubException.Network when the Hub can't be reached, [HubException.RateLimited] or
     * [HubException.Unauthorized] when it refuses.
     * @throws IllegalArgumentException if [SearchOptions.limit] is out of range, or the parameter range is negative or
     * empty.
     */
    public suspend fun search(query: String, configure: SearchOptions.() -> Unit = {}): SearchPage {
        val url = searches.firstPage(query, SearchOptions().apply(configure))
        return searches.page(operationTransport(), url)
    }

    /**
     * The page after the one [next] came with; failures as the first page's.
     *
     * @throws IllegalArgumentException if [next] is not on this hub's endpoint.
     */
    public suspend fun search(next: SearchCursor): SearchPage = searches.next(operationTransport(), next)

    /**
     * The GGUF files of [repo] (`owner/name`) at its newest commit: each quantisation once with its bytes (split files
     * summed) and whether an image encoder is there (D68). Changes nothing in the cache; when the Hub can't be reached
     * it answers from the cache, with [RepoFiles.offline] set, as [plan] does.
     *
     * @throws HubException.NotFound, [HubException.Unauthorized] or [HubException.Network] as [plan] does.
     * @throws IllegalArgumentException if [repo] is not a valid repo id.
     */
    public suspend fun files(repo: String): RepoFiles =
        searches.files(planner.resolver(operationTransport(), look = true), repo)

    /**
     * The model card of [repo] (the original model's repo, not a GGUF upload's): its `README.md` at `main` as
     * markdown, with the YAML front matter removed, or null when the repo has none. At most 512 KiB are read. The
     * token goes only to the endpoint's host (F82).
     *
     * @throws HubException.Network when the Hub can't be reached, [HubException.RateLimited] or
     * [HubException.Unauthorized] when it refuses (a gated repo without a token).
     * @throws IllegalArgumentException if [repo] is not a repo id.
     */
    public suspend fun modelCard(repo: String): String? = searches.modelCard(operationTransport(), repo)

    /**
     * The original model [repo] (`owner/name`, usually a GGUF upload) was quantized from: its `base_model:quantized:`
     * tag, or null when the repo has none. A fact the Hub states; nothing is guessed from names. One request; the
     * token goes only to the endpoint's host (F82).
     *
     * @throws HubException.NotFound, [HubException.Unauthorized], [HubException.RateLimited] or
     * [HubException.Network] as [files] does.
     * @throws IllegalArgumentException if [repo] is not a valid repo id.
     */
    public suspend fun baseModel(repo: String): String? = searches.baseModelOf(operationTransport(), repo)

    /**
     * The sampling recommended for [model] from both places the Hub can hold it, each null when absent (D68, D79):
     * [RecommendedSampling.fromFile], the GGUF header's `general.sampling.*`, which is what loads by default (F17), and
     * [RecommendedSampling.fromBaseModel], the base model's `generation_config.json`, found through the repo's
     * `base_model:quantized:` tag. Nothing is applied. The header is read from disk when the model (as [model] fetches
     * it, with any options) is on the phone, else with range requests (the header only, from 8 MiB) that leave nothing
     * behind. With the model on the phone this works offline: the base model can't be looked up then, so
     * [RecommendedSampling.fromBaseModel] and [RecommendedSampling.baseModel] are null. A gated base model gives a null
     * [RecommendedSampling.fromBaseModel], not an error. Model card text is never read.
     *
     * @throws HubException.Unauthorized when a configured token is refused for [model]'s own repo, as [plan] does;
     * with no token a gated repo gives nulls instead.
     * @throws HubException.Network when the Hub can't be reached and the model isn't on the phone,
     * [HubException.RateLimited] when it refuses, and [HubException.NotFound] when [model]'s repo or file doesn't
     * exist.
     */
    public suspend fun recommendedSampling(model: HfModel): RecommendedSampling =
        withContext(Dispatchers.IO) {
            val token = config.token?.invoke()
            val transport = transportFor(token, config, this@LlamaBroHub.transport, logger)
            val local = copies.installedModelFile(model)?.let { file -> localSampling(searches, file, logger) }
            // a header read from disk with no values is the answer: the same file on the Hub holds none either, and
            // asking again would fail offline for a model that is on the phone
            val fromFile =
                if (local != null) {
                    local.sampling
                } else {
                    unlessGated(token, model, logger) {
                        val plan = planInternal(model, PlanOptions(), transport, record = false)
                        searches.fromRemoteFile(transport, plan)
                    }
                }
            try {
                val base = unlessGated(token, model, logger) { searches.baseModelOf(transport, model.repo) }
                RecommendedSampling(fromFile, base?.let { searches.fromBaseModel(transport, it) }, base)
            } catch (offline: HubException.Network) {
                // D79: a local header works offline; the base model can't be known then, which isn't the file's loss.
                // Only no route to the Hub is offline: an error it answered with is thrown as it is.
                if (local == null || !isUnreachable(offline)) throw offline
                logger?.log(LogLevel.Debug, "recommendedSampling: base model unknown offline: ${offline.message}")
                RecommendedSampling(fromFile, null, null)
            }
        }

    /**
     * Resolves [model] online, falling back to the cache only when the Hub can't be reached: a look that changes
     * nothing on the phone (no ref moves, no record is written), so what [progress], [cached] and [source] report
     * stays as it was. Only [download] records a selection.
     *
     * @throws HubException.Unauthorized, [HubException.RateLimited] or [HubException.NotFound] when the Hub refuses,
     * and [HubException.Network] when it answers with an error (a 5xx, a malformed answer), or can't be reached and the
     * cache holds no match.
     */
    public suspend fun plan(model: HfModel, configure: PlanOptions.() -> Unit = {}): HubPlan =
        planInternal(model, PlanOptions().apply(configure), operationTransport(), record = false)

    /**
     * With [record] false the plan is a look only: no ref or plan record moves, and a refusal is thrown rather than
     * answered from the cache.
     */
    internal suspend fun planInternal(
        model: HfModel,
        options: PlanOptions,
        operationTransport: Transport,
        record: Boolean = true,
    ): HubPlan = planner.plan(model, options, operationTransport, record)

    /**
     * Shares one transfer per selection; collecting again after cancellation resumes partials. When it completes, its
     * files become the installed copy that [model] and [source] load (D64), and the files only the replaced copy used
     * (an older commit's) are removed; files another selection or tag still uses stay.
     *
     * Fails with a [HubException]: [HubException.Network] for a connection or I/O failure (retryable). A [delete] of
     * the model while it runs stops it and ends the flow with a [HubException.Network] that is not retryable, so a
     * background worker doesn't fetch the deleted model again. Any other exception is a bug, passed through as it is.
     */
    @CheckResult
    public fun download(model: HfModel, configure: PlanOptions.() -> Unit = {}): Flow<DownloadProgress> {
        val options = PlanOptions().apply(configure)
        val key = states.key(model, options)
        val state = states.track(key, model, options)
        return transfers
            .join(key, run = { emit -> transfer(model, options, emit) }, onIdle = {
                states.settleIdle(state, model, options)
            })
            .onEach { update ->
                state.value =
                    if (update.phase == DownloadPhase.Complete) {
                        DownloadState.Complete
                    } else {
                        DownloadState.Downloading(update)
                    }
            }.catch { error ->
                // Only the transfer's own failures: a collector's exceptions pass through untouched. A delete sets the
                // state from disk itself, whenever this collector gets here.
                if (error is HubException && error.cause !is DeletedWhileDownloading) {
                    state.value = DownloadState.Failed(error)
                }
                throw error
            }
    }

    /**
     * Shared process state. A selection first seen starts as [DownloadState.Absent] and is filled from the local plan
     * and
     * cache on Dispatchers.IO, so calling this on the main thread does no disk I/O.
     */
    public fun progress(model: HfModel, configure: PlanOptions.() -> Unit = {}): StateFlow<DownloadState> {
        val options = PlanOptions().apply(configure)
        return states.track(states.key(model, options), model, options)
    }

    /** Tests: waits until [progress]'s first read from disk is done. */
    internal suspend fun awaitDiskState(model: HfModel, configure: PlanOptions.() -> Unit = {}) =
        states.awaitLoaded(model, PlanOptions().apply(configure))

    private suspend fun transfer(model: HfModel, options: PlanOptions, emit: suspend (DownloadProgress) -> Unit) {
        // A delete gates the model, then stops the transfers it finds; this one ran only after its transfer was
        // there to find. So either the delete stopped it, or it sees the gate here.
        synchronized(deleting) { deleting.firstOrNull { deletes(it, model, options) } }?.let { throw stoppedBy(it) }
        val meter = ProgressMeter(config.progressInterval, emit)
        meter.update(DownloadPhase.Resolving, 0, 0, null, 0, 0)
        val requestTransport = operationTransport()
        val plan = planInternal(model, options, requestTransport)
        cacheDir.mkdirs()
        val needed = plan.bytesToDownload
        reserveSpace(needed)
        val done = AtomicLong(plan.bytesTotal - needed)
        meter.update(DownloadPhase.Downloading, done.get(), plan.bytesTotal, null, 0, plan.files.size)
        downloadFiles(plan, requestTransport, done, meter)
        copies.install(plan, options) // only now does the new copy replace the installed one (D64)
        meter.update(DownloadPhase.Complete, plan.bytesTotal, plan.bytesTotal, null, 0, plan.files.size)
    }

    /** Fails before any byte is fetched when the phone can't hold [needed] bytes, and sets them aside. */
    private fun reserveSpace(needed: Long) {
        val available = storage.allocatableBytes(cacheDir)
        if (needed > available) throw HubException.InsufficientStorage(needed, available)
        try {
            storage.allocate(cacheDir, needed)
        } catch (ignored: IOException) {
            throw HubException.InsufficientStorage(needed, storage.allocatableBytes(cacheDir))
        }
    }

    /**
     * Every file of [plan], [parallelDownloads] at a time, taken in the plan's order (model parts, image encoder,
     * draft), reporting through [meter] as bytes arrive. With one at a time, a download stopped partway holds a prefix
     * of the plan, and its progress counts the files up in order.
     */
    private suspend fun downloadFiles(
        plan: HubPlan,
        requestTransport: Transport,
        done: AtomicLong,
        meter: ProgressMeter,
    ) {
        val downloader =
            BlobDownloader(
                config.endpoint,
                cacheDir,
                requestTransport,
                storage,
                logger,
                degradedSymlinks,
                openOutput,
                createSymlink,
            )
        val next = AtomicInteger()
        coroutineScope {
            repeat(minOf(parallelDownloads, plan.files.size)) {
                launch(Dispatchers.IO) {
                    while (true) {
                        val index = next.getAndIncrement()
                        val file = plan.files.getOrNull(index) ?: break
                        downloader.download(file) { delta, verifying ->
                            val phase = if (verifying) DownloadPhase.Verifying else DownloadPhase.Downloading
                            meter.update(
                                phase,
                                done.addAndGet(delta),
                                plan.bytesTotal,
                                file.path,
                                index + 1,
                                plan.files.size,
                            )
                        }
                    }
                }
            }
        }
    }

    /**
     * Lists the models in the local cache that load from it, without a network request: complete selections only, so a
     * stopped download (its projector or a split part missing) is left out (D89).
     */
    public suspend fun cached(): List<CachedModel> =
        withContext(Dispatchers.IO) {
            Cache(cacheDir).list()
        }

    /**
     * Removes [model] from the local cache while keeping files selected by other tags. Deletes of one repository run
     * one at a time, in this process and across processes: of two at once for one model, the second finds it gone and
     * returns false.
     *
     * A [download] in this process of what it removes is stopped first, and waited for: one of the repo for a bare
     * repo, of the same tag for a tag, of the same file for a file (as the model or as its draft). Its flow ends with a
     * [HubException.Network] that is not retryable, and the model stays deleted. A download of it that starts while
     * the delete runs fails at once the same way, before it plans or fetches anything.
     */
    public suspend fun delete(model: HfModel): Boolean =
        withContext(Dispatchers.IO) {
            // Gated before the stop: a download that joins after it fails fast, so none runs while files go.
            synchronized(deleting) { deleting += model }
            try {
                transfers.cancel(stoppedBy(model)) { key ->
                    states.selectionOf(key)?.let { (selected, options) -> deletes(model, selected, options) } == true
                }
                val removed =
                    Locks.withFileLock(File(cacheDir, ".locks/${repoFolder(model.repo)}/delete.lock")) {
                        Cache(cacheDir).delete(model)
                    }
                if (removed) states.refreshAfterDelete(model)
                removed
            } finally {
                synchronized(deleting) { deleting -= model }
            }
        }

    /**
     * The installed copy of [model] as a core source, from disk only and never the network: the files of the last
     * download that completed, wherever `refs/main` has moved since (D64). [HubException.NotFound] when there is none.
     */
    public suspend fun source(model: HfModel, configure: PlanOptions.() -> Unit = {}): ModelSource =
        withContext(Dispatchers.IO) { copies.source(model, PlanOptions().apply(configure)) }

    public companion object {
        private val created = mutableMapOf<String, Pair<HubConfig, LlamaBroHub>>()

        /**
         * The hub for [config]'s cache folder (D70): one per folder. The first call for a folder creates it; a later
         * call with the same endpoint and progress interval returns the same object, keeping the first logger and token
         * provider, so a DI container can hold it as a singleton (D106). Hubs on different folders coexist, so an app
         * can move its models without a restart.
         *
         * Does no network, no native code and no disk I/O beyond resolving the folder: cheap on the main thread.
         *
         * @throws IllegalStateException if a hub already exists for the folder with another [config]: its endpoint,
         * or progress interval differ.
         */
        public fun create(context: Context, config: HubConfig = HubConfig.Default): LlamaBroHub {
            // A repeat call with an equal config skips the disk work below (D70).
            registered(config)?.let { return it }
            val app = context.applicationContext
            val cacheDir = cacheDirFor(config, app.noBackupFilesDir)
            return create(cacheDir, config) {
                // The hub has no level setting of its own, so the default sink filters at Warn (D66).
                val defaultLogger = LogcatLogger("llamabro-hub", minLevel = LogLevel.Warn)
                LlamaBroHub(
                    config,
                    cacheDir,
                    HttpTransport(config.endpoint, token = null, logger = config.effectiveLogger(defaultLogger)),
                    AndroidStorage(app),
                    defaultLogger = defaultLogger,
                )
            }
        }

        /**
         * A hub over another [transport] and [storage], for `llamabro-hub-testing`'s `FakeLlamaBroHub` (D75), as
         * `LlamaBro.withEngine` is for `FakeLlamaBro` (D27). The cache is [HubConfig.cacheDir], which must be set,
         * since there is no app folder to default to; the hub is not registered with [create]. An `IOException` from
         * [transport] is a retryable [HubException.Network], as the network's is.
         *
         * @throws IllegalArgumentException if [config] has no cache folder, or has a token provider: with a token the
         * hub sends its requests through its own HTTP transport (F82), so a scripted Hub would not see them.
         */
        @InternalLlamaBroApi
        public fun withTransport(transport: Transport, storage: Storage, config: HubConfig): LlamaBroHub {
            val cacheDir = requireNotNull(config.cacheDir) { "LlamaBroHub.withTransport: set HubConfig.cacheDir" }
            require(config.token == null) { "LlamaBroHub.withTransport: a scripted Hub takes no token provider" }
            return LlamaBroHub(config, cacheDir, NetworkFailures(transport), storage)
        }

        /** A hub already created for an equal [config]: equal configs name one folder, so no path is resolved. */
        @Synchronized
        private fun registered(config: HubConfig): LlamaBroHub? =
            created.values.firstOrNull { it.first === config || it.first == config }?.second

        /** The folder a config names, or the app's default under [noBackupDir]. */
        internal fun cacheDirFor(config: HubConfig, noBackupDir: File): File =
            config.cacheDir ?: File(noBackupDir, "llamabro/hub")

        /**
         * The registry behind [create], free of `Context`, keyed by the canonical folder so two spellings of one folder
         * are one hub. A refused config never reaches [build].
         */
        @Synchronized
        internal fun create(cacheDir: File, config: HubConfig, build: () -> LlamaBroHub): LlamaBroHub {
            val key = cacheDir.canonicalPath
            val existing = created[key]
            if (existing == null) return build().also { created[key] = config to it }
            check(sameSettings(existing.first, config)) {
                "LlamaBroHub.create: a hub for $key already exists with ${existing.first}; it can't be created again " +
                    "with $config. Two hubs on one folder would download the same files twice: create the config " +
                    "once and share the object."
            }
            return existing.second
        }

        // The key already says the folder is the same, so its spelling (null, `..`, a symlink) is not a conflict. The
        // logger and the token provider are not settings: the first hub's stay in use (D106).
        private fun sameSettings(a: HubConfig, b: HubConfig): Boolean =
            a.endpoint == b.endpoint && a.progressInterval == b.progressInterval

        /** Forgets the created hubs so a test can start clean. */
        @Synchronized
        internal fun resetCreatedForTest() {
            created.clear()
        }
    }
}

private fun HubConfig.effectiveLogger(default: Logger?): Logger? = logger ?: default

/**
 * Whether deleting [deleted] removes what a download of [selected] with [options] fetches, as the model or as its
 * draft, matched as the cache's removal matches records.
 */
private fun deletes(deleted: HfModel, selected: HfModel, options: PlanOptions): Boolean =
    deletes(deleted, selected) || options.draft?.from?.let { deletes(deleted, it) } == true

private fun deletes(deleted: HfModel, selected: HfModel): Boolean =
    selected.repo == deleted.repo &&
        when {
            deleted.file != null -> selected.file == deleted.file
            deleted.tag != null -> selected.file == null && selected.tag == deleted.tag
            else -> true
        }

/** The failure a download of what a delete removes ends with: not retryable, so no worker fetches it again. */
private fun stoppedBy(deleted: HfModel): HubException =
    HubException.Network("download stopped: $deleted was deleted", DeletedWhileDownloading(deleted), retryable = false)

/** The cause of the failure [LlamaBroHub.delete] ends a running download with. */
private class DeletedWhileDownloading(model: HfModel) : CancellationException("$model was deleted")

/**
 * [read], or null when [model]'s repo is gated and no token is set. A configured token the repo refuses is thrown, as
 * `files` and `plan` do: the app's token is wrong, which a null would hide.
 */
private inline fun <T> unlessGated(token: String?, model: HfModel, logger: Logger?, read: () -> T): T? =
    try {
        read()
    } catch (gated: HubException.Unauthorized) {
        if (token != null) throw gated
        logger?.log(LogLevel.Debug, "recommendedSampling: $model is gated: ${gated.message}")
        null
    }

/** With a token, a transport that sends it to the endpoint's host only (F82); without, the hub's own. */
private fun transportFor(token: String?, config: HubConfig, own: Transport, logger: Logger?): Transport =
    token?.let { HttpTransport(config.endpoint, it, logger) } ?: own

/** A local header's sampling; [sampling] is null when the header holds none. */
private class LocalSampling(val sampling: Sampling?)

/**
 * The sampling in [file]'s header (D79: free, and works offline), or null when the file went between finding and
 * reading it, or its header doesn't parse: the caller then reads the Hub's copy, as with no local file.
 */
private fun localSampling(searches: HubSearch, file: File, logger: Logger?): LocalSampling? =
    try {
        LocalSampling(searches.fromLocalFile(file))
    } catch (unreadable: IOException) {
        logger?.log(LogLevel.Warn, "recommendedSampling: local header unreadable, reading the Hub's: $unreadable")
        null
    } catch (corrupt: IllegalArgumentException) {
        logger?.log(LogLevel.Warn, "recommendedSampling: local header invalid, reading the Hub's: $corrupt")
        null
    }
