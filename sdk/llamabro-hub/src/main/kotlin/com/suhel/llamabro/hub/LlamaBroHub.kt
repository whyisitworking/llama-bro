package com.suhel.llamabro.hub

import android.content.Context
import com.suhel.llamabro.FitEstimate
import com.suhel.llamabro.InternalLlamaBroApi
import com.suhel.llamabro.LlamaBro
import com.suhel.llamabro.LogLevel
import com.suhel.llamabro.Logger
import com.suhel.llamabro.ModelConfig
import com.suhel.llamabro.ModelSource
import com.suhel.llamabro.hub.internal.AndroidStorage
import com.suhel.llamabro.hub.internal.BlobDownloader
import com.suhel.llamabro.hub.internal.Cache
import com.suhel.llamabro.hub.internal.CacheLookup
import com.suhel.llamabro.hub.internal.HeaderFetch
import com.suhel.llamabro.hub.internal.HttpTransport
import com.suhel.llamabro.hub.internal.PlanStore
import com.suhel.llamabro.hub.internal.ProgressMeter
import com.suhel.llamabro.hub.internal.RepoFile
import com.suhel.llamabro.hub.internal.RepoListing
import com.suhel.llamabro.hub.internal.Resolved
import com.suhel.llamabro.hub.internal.Resolver
import com.suhel.llamabro.hub.internal.Storage
import com.suhel.llamabro.hub.internal.Transfers
import com.suhel.llamabro.hub.internal.Transport
import com.suhel.llamabro.hub.internal.repoFolder
import com.suhel.llamabro.hub.internal.resolvePlan
import com.suhel.llamabro.hub.internal.splitInfo
import com.suhel.llamabro.internal.LogcatLogger
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Resolves and caches Hugging Face models under an app-private directory. */
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
    // D66/F144: the app's logger wins; with none, lines go to the default (logcat `llamabro-hub` from create).
    private val logger: Logger? = config.effectiveLogger(defaultLogger)
    private val degradedSymlinks = AtomicBoolean(false)
    private val transfers = Transfers()
    private val states = ConcurrentHashMap<String, MutableStateFlow<DownloadState>>()
    private val stateLoads = ConcurrentHashMap<String, Job>()

    // The disk reads below never throw out of the scope: a failed read leaves the state as it is, never kills the app.
    private val scope =
        CoroutineScope(
            SupervisorJob() + Dispatchers.IO +
                CoroutineExceptionHandler {
                    _,
                    error,
                    ->
                    logger?.log(LogLevel.Warn, "hub state read failed: $error")
                },
        )
    private val selections = ConcurrentHashMap<String, Pair<HfModel, PlanOptions>>()
    private val plans: PlanStore = PlanStore(cacheDir, storage)
    private val lookup = CacheLookup(cacheDir)

    private suspend fun operationTransport(): Transport =
        config.token?.invoke()?.let {
            HttpTransport(config.endpoint, it, logger)
        } ?: transport

    /** Resolves [model] online, falling back to the cache when the Hub is unavailable. */
    public suspend fun plan(model: HfModel, configure: PlanOptions.() -> Unit = {}): HubPlan =
        planInternal(model, PlanOptions().apply(configure), operationTransport())

    private suspend fun planInternal(model: HfModel, options: PlanOptions, operationTransport: Transport): HubPlan =
        withContext(Dispatchers.IO) {
            val resolver = Resolver(config.endpoint, cacheDir, operationTransport, storage)
            val listing = resolver.list(model.repo)
            val selected =
                resolvePlan(
                    listing.files,
                    model.tag.orEmpty(),
                    model.file.orEmpty(),
                    options.mmproj,
                    options.draft?.from == null && options.draft != null,
                )
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
            if (!offline) PlanStore(cacheDir, storage).write(plan, options)
            plan
        }

    /** Why nothing matched: offline, the cache only lacks it and the Hub may have it, so a worker should retry. */
    private fun noMatch(model: HfModel, offline: Boolean, available: List<String>): HubException =
        if (offline) {
            HubException.Network("offline, and no cached GGUF matches $model", null, true)
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
        val draftSelection = resolvePlan(draftListing.files, from.tag.orEmpty(), from.file.orEmpty(), false, true)
        val draftFiles =
            draftSelection.dflash?.let { listOf(it) }
                ?: draftSelection.modelFiles.takeIf { it.isNotEmpty() }
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

    /** Shares one transfer per selection; collecting again after cancellation resumes partials. */
    public fun download(model: HfModel, configure: PlanOptions.() -> Unit = {}): Flow<DownloadProgress> {
        val options = PlanOptions().apply(configure)
        val key = plans.key(model, options)
        selections.putIfAbsent(key, model to options)
        val state = stateFor(key, model, options)
        return transfers
            .join(key, run = { emit -> transfer(model, options, emit) }, onIdle = {
                val current = state.value
                if (current is DownloadState.Downloading) {
                    scope.launch {
                        state.compareAndSet(current, diskState(model, options))
                    }
                }
            })
            .onEach { update ->
                state.value =
                    if (update.phase == DownloadPhase.Complete) {
                        DownloadState.Complete
                    } else {
                        DownloadState.Downloading(update)
                    }
            }.catch { error ->
                // Only the transfer's own failures: a collector's exceptions pass through untouched.
                if (error is HubException) state.value = DownloadState.Failed(error)
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
        val key = plans.key(model, options)
        selections.putIfAbsent(key, model to options)
        return stateFor(key, model, options)
    }

    private fun stateFor(key: String, model: HfModel, options: PlanOptions): MutableStateFlow<DownloadState> =
        states.computeIfAbsent(key) {
            MutableStateFlow<DownloadState>(DownloadState.Absent).also { flow ->
                stateLoads[key] =
                    scope.launch {
                        val loaded = diskState(model, options)
                        ensureActive() // delete() cancels a read it would otherwise race (it sets the state itself)
                        flow.compareAndSet(DownloadState.Absent, loaded)
                    }
            }
        }

    /** Tests: waits until [progress]'s first read from disk is done. */
    internal suspend fun awaitDiskState(model: HfModel, configure: PlanOptions.() -> Unit = {}) {
        val options = PlanOptions().apply(configure)
        val key = plans.key(model, options)
        stateFor(key, model, options)
        stateLoads[key]?.join()
    }

    private fun diskState(model: HfModel, options: PlanOptions): DownloadState {
        val record =
            plans.read(model, options)
                ?: return if (offlineComplete(model, options)) DownloadState.Complete else DownloadState.Absent
        if (record.files.isEmpty()) return DownloadState.Absent
        val alternate = options.draft?.from
        val alternateCommit =
            alternate?.let { Resolver(config.endpoint, cacheDir, transport, storage).cachedListing(it.repo)?.commit }
        var done = 0L
        var allComplete = true
        for (file in record.files) {
            val draftSource = alternate.takeIf { file.role == HubFile.Role.Draft }
            val repo = draftSource?.repo ?: model.repo
            val commit = if (draftSource != null) alternateCommit else record.commit
            val held = lookup.heldBytes(repo, commit, file)
            done += held.bytes
            allComplete = allComplete && held.complete
        }
        return when {
            allComplete -> DownloadState.Complete
            done > 0 -> DownloadState.Partial(done, record.files.sumOf { it.size })
            else -> DownloadState.Absent
        }
    }

    private fun offlineComplete(model: HfModel, options: PlanOptions): Boolean {
        val resolver = Resolver(config.endpoint, cacheDir, transport, storage)
        val listing = resolver.cachedListing(model.repo) ?: return false
        val selected =
            resolvePlan(
                listing.files,
                model.tag.orEmpty(),
                model.file.orEmpty(),
                options.mmproj,
                options.draft?.from == null && options.draft != null,
            )
        return completeSplit(selected) && draftCached(resolver, options.draft, selected)
    }

    /** Whether the draft the options ask for, if any, can be found in the cache. */
    private fun draftCached(resolver: Resolver, draft: DraftRequest?, selected: Resolved): Boolean {
        if (draft == null) return true
        val from = draft.from ?: return selected.dflash != null
        val listing = resolver.cachedListing(from.repo) ?: return false
        val resolved = resolvePlan(listing.files, from.tag.orEmpty(), from.file.orEmpty(), false, true)
        return resolved.dflash != null || resolved.modelFiles.isNotEmpty()
    }

    private suspend fun transfer(model: HfModel, options: PlanOptions, emit: suspend (DownloadProgress) -> Unit) {
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

    /** Every file of [plan], [parallelDownloads] at a time, reporting through [meter] as bytes arrive. */
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
        val permits = Semaphore(parallelDownloads)
        coroutineScope {
            plan.files
                .mapIndexed { index, file ->
                    async(Dispatchers.IO) {
                        permits.withPermit {
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
                }.awaitAll()
        }
    }

    /** Lists quantized models already present in the local cache, without a network request. */
    public suspend fun cached(): List<CachedModel> =
        withContext(Dispatchers.IO) {
            Cache(cacheDir, Resolver(config.endpoint, cacheDir, transport, storage)).list()
        }

    /** Removes [model] from the local cache while keeping files selected by other tags. */
    public suspend fun delete(model: HfModel): Boolean =
        withContext(Dispatchers.IO) {
            val removed = Cache(cacheDir, Resolver(config.endpoint, cacheDir, transport, storage)).delete(model)
            if (removed) {
                for ((key, selection) in selections) {
                    val (selected, options) = selection
                    if (selected.repo == model.repo || options.draft?.from?.repo == model.repo) {
                        stateLoads[key]?.cancelAndJoin()
                        states[key]?.value = diskState(selected, options)
                    }
                }
            }
            removed
        }

    /** Returns cached files as a core source without contacting the network. */
    public suspend fun source(model: HfModel, configure: PlanOptions.() -> Unit = {}): ModelSource =
        withContext(Dispatchers.IO) {
            val options = PlanOptions().apply(configure)
            val resolver = Resolver(config.endpoint, cacheDir, transport, storage)
            val listing =
                resolver.cachedListing(model.repo)
                    ?: throw HubException.NotFound("model is not cached: $model", emptyList())
            plans.read(model, options)?.let { requireRecordedFilesCached(it, model, options, resolver) }
            val selected =
                resolvePlan(
                    listing.files,
                    model.tag.orEmpty(),
                    model.file.orEmpty(),
                    options.mmproj,
                    options.draft?.from == null && options.draft != null,
                )
            val primary =
                selected.modelFiles.firstOrNull()?.takeIf { completeSplit(selected) }
                    ?: throw HubException.NotFound("model is not cached: $model", emptyList())
            selected.modelFiles.forEach { lookup.snapshotPath(model.repo, listing.commit, it) }
            val mmproj = selected.mmproj?.let { lookup.snapshotPath(model.repo, listing.commit, it) }
            val draft = options.draft?.let { draftPath(resolver, it, model, selected, listing) }
            ModelSource.file(lookup.snapshotPath(model.repo, listing.commit, primary)) {
                this.mmproj = mmproj
                this.draft = draft
            }
        }

    /** A plan record that exists vouches for its files: every one must be on disk at its recorded size. */
    private fun requireRecordedFilesCached(
        record: PlanStore.Record,
        model: HfModel,
        options: PlanOptions,
        resolver: Resolver,
    ) {
        val alternate = options.draft?.from
        val draftCommit = alternate?.let { resolver.cachedListing(it.repo)?.commit }
        for (file in record.files) {
            val draftSource = alternate.takeIf { file.role == HubFile.Role.Draft }
            val repo = draftSource?.repo ?: model.repo
            val commit = if (draftSource != null) draftCommit else record.commit
            val cached = commit?.let { File(cacheDir, "${repoFolder(repo)}/snapshots/$it/${file.path}") }
            if (cached?.isFile != true || cached.length() != file.size) {
                throw HubException.NotFound("file is not cached: ${file.path}", emptyList())
            }
        }
    }

    /** The draft's path: the DFlash file beside the model, or the one in the repo the request names. */
    private fun draftPath(
        resolver: Resolver,
        request: DraftRequest,
        model: HfModel,
        selected: Resolved,
        listing: RepoListing,
    ): String {
        val from = request.from
        if (from == null) {
            val file = selected.dflash ?: throw HubException.NotFound("draft is not cached: $model", emptyList())
            return lookup.snapshotPath(model.repo, listing.commit, file)
        }
        return namedDraftPath(resolver, from)
    }

    private fun namedDraftPath(resolver: Resolver, from: HfModel): String {
        val listing =
            resolver.cachedListing(from.repo)
                ?: throw HubException.NotFound("draft is not cached: $from", emptyList())
        val choice = resolvePlan(listing.files, from.tag.orEmpty(), from.file.orEmpty(), false, true)
        val files = choice.dflash?.let { listOf(it) } ?: choice.modelFiles
        if (files.isEmpty()) throw HubException.NotFound("draft is not cached: $from", emptyList())
        files.forEach { lookup.snapshotPath(from.repo, listing.commit, it) }
        return lookup.snapshotPath(from.repo, listing.commit, files.first())
    }

    /** Offline listings hold only the parts on disk: every part of a split model must be there. */
    private fun completeSplit(selected: Resolved): Boolean {
        val first = selected.modelFiles.firstOrNull() ?: return false
        val count = splitInfo(first.path).count
        return count <= 1 || selected.modelFiles.size == count
    }

    /** Estimates the memory and context for [plan] from bounded GGUF headers before downloading weights. */
    public suspend fun estimateFit(
        plan: HubPlan,
        llama: LlamaBro,
        configure: ModelConfig.() -> Unit = {},
    ): FitEstimate =
        withContext(Dispatchers.IO) {
            val models = plan.files.filter { it.role == HubFile.Role.Model }
            val primary =
                models.firstOrNull()
                    ?: throw HubException.NotFound("plan has no model GGUF", emptyList())
            val drafts = plan.files.filter { it.role == HubFile.Role.Draft }
            val projector = plan.files.firstOrNull { it.role == HubFile.Role.Mmproj }
            val root = File(cacheDir, ".llamabro/estimate/${primary.oid ?: "offline"}/${UUID.randomUUID()}")
            val headers = HeaderFetch(config.endpoint, operationTransport())

            fun destination(role: String, file: HubFile): File = File(root, "$role/${file.path}")
            try {
                suspend fun stub(role: String, file: HubFile): String {
                    val target = destination(role, file)
                    headers.fetch(file, target)
                    return target.absolutePath
                }
                for (file in models) stub("model", file)
                for (file in drafts) stub("draft", file)
                val mmproj =
                    projector?.let { file ->
                        val target = destination("mmproj", file)
                        requireNotNull(target.parentFile).mkdirs()
                        RandomAccessFile(target, "rw").use { it.setLength(file.sizeBytes) }
                        target.absolutePath
                    }
                val source =
                    ModelSource.file(destination("model", primary).absolutePath) {
                        this.mmproj = mmproj
                        this.draft = drafts.firstOrNull()?.let { destination("draft", it).absolutePath }
                    }
                llama.estimateFit(source, configure)
            } finally {
                root.deleteRecursively()
                root.parentFile?.delete() // the per-model folder, once no other estimate uses it
            }
        }

    public companion object {
        private val created = mutableMapOf<String, Pair<HubConfig, LlamaBroHub>>()

        /**
         * The hub for [config]'s cache folder (D70): one per folder. The first call for a folder creates it; a later
         * call with an equal [config] returns the same object, so a DI container can hold it as a singleton. Hubs on
         * different folders coexist, so an app can move its models without a restart. [HubConfig] compares its logger
         * and token provider by reference: create the config once, not on each call.
         *
         * Does no network, no native code and no disk I/O beyond resolving the folder: cheap on the main thread.
         *
         * @throws IllegalStateException if a hub already exists for the folder with another [config]: its endpoint,
         * progress interval, logger or token provider differ.
         */
        @JvmStatic
        @JvmOverloads
        @OptIn(InternalLlamaBroApi::class)
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

        // The key already says the folder is the same, so its spelling (null, `..`, a symlink) is not a conflict.
        private fun sameSettings(a: HubConfig, b: HubConfig): Boolean =
            a.endpoint == b.endpoint &&
                a.progressInterval == b.progressInterval &&
                a.logger === b.logger &&
                a.token === b.token

        /** Forgets the created hubs so a test can start clean. */
        @Synchronized
        internal fun resetCreatedForTest() {
            created.clear()
        }
    }
}

private fun HubConfig.effectiveLogger(default: Logger?): Logger? = logger ?: default
