package com.suhel.llamabro.hub

import android.content.Context
import com.suhel.llamabro.LogLevel
import com.suhel.llamabro.ModelSource
import com.suhel.llamabro.LlamaBro
import com.suhel.llamabro.ModelConfig
import com.suhel.llamabro.FitEstimate
import com.suhel.llamabro.hub.internal.AndroidStorage
import com.suhel.llamabro.hub.internal.BlobDownloader
import com.suhel.llamabro.hub.internal.HttpTransport
import com.suhel.llamabro.hub.internal.PlanStore
import com.suhel.llamabro.hub.internal.Cache
import com.suhel.llamabro.hub.internal.ProgressMeter
import com.suhel.llamabro.hub.internal.HeaderFetch
import com.suhel.llamabro.hub.internal.Transfers
import com.suhel.llamabro.hub.internal.RepoFile
import com.suhel.llamabro.hub.internal.Resolver
import com.suhel.llamabro.hub.internal.repoFolder
import com.suhel.llamabro.hub.internal.Resolved
import com.suhel.llamabro.hub.internal.resolvePlan
import com.suhel.llamabro.hub.internal.splitInfo
import com.suhel.llamabro.hub.internal.Storage
import com.suhel.llamabro.hub.internal.Transport
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.Path
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.ConcurrentHashMap
import java.util.UUID
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
) {
    private val degradedSymlinks = AtomicBoolean(false)
    private val transfers = Transfers()
    private val states = ConcurrentHashMap<String, MutableStateFlow<DownloadState>>()
    private val stateLoads = ConcurrentHashMap<String, Job>()
    // The disk reads below never throw out of the scope: a failed read leaves the state as it is, never kills the app.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO +
        CoroutineExceptionHandler { _, error -> config.logger?.log(LogLevel.Warn, "hub state read failed: $error") })
    private val selections = ConcurrentHashMap<String, Pair<HfModel, PlanOptions>>()
    private val plans: PlanStore = PlanStore(cacheDir, storage)

    private suspend fun operationTransport(): Transport = config.token?.invoke()?.let {
        HttpTransport(config.endpoint, it, config.logger)
    } ?: transport

    /** Resolves [model] online, falling back to the cache when the Hub is unavailable. */
    public suspend fun plan(model: HfModel, configure: PlanOptions.() -> Unit = {}): HubPlan =
        planInternal(model, PlanOptions().apply(configure), operationTransport())

    private suspend fun planInternal(model: HfModel, options: PlanOptions, operationTransport: Transport): HubPlan =
        withContext(Dispatchers.IO) {
            val resolver = Resolver(config.endpoint, cacheDir, operationTransport, storage)
            val listing = resolver.list(model.repo)
            val selected = resolvePlan(listing.files, model.tag.orEmpty(), model.file.orEmpty(),
                options.mmproj, options.draft?.from == null && options.draft != null)
            val available = listing.files.map { it.path }.filter { it.endsWith(".gguf") }
            if (selected.primary == null) {
                // Offline, the cache only lacks it: the Hub may have it, so a worker should retry.
                if (listing.offline) throw HubException.Network("offline, and no cached GGUF matches $model", null, true)
                throw HubException.NotFound("no GGUF matches $model", available)
            }
            val chosen = mutableListOf<HubFile>()
            fun add(files: List<RepoFile>, role: HubFile.Role, repo: String, commit: String) {
                for (file in files) {
                    val repoDir = File(cacheDir, repoFolder(repo))
                    val snapshot = File(repoDir, "snapshots/$commit/${file.path}")
                    chosen += HubFile(file.path, role, file.sizeBytes, file.oid, snapshot.exists()).also {
                        it.sourceRepo = repo
                        it.sourceCommit = commit
                    }
                }
            }
            add(selected.modelFiles, HubFile.Role.Model, model.repo, listing.commit)
            selected.mmproj?.let { add(listOf(it), HubFile.Role.Mmproj, model.repo, listing.commit) }
            var offline = listing.offline
            if (options.draft != null) {
                val from = options.draft!!.from
                if (from == null) {
                    val draft = selected.dflash ?: throw HubException.NotFound(
                        "no dflash- file in ${model.repo} for ${model.tag.orEmpty()}", available)
                    add(listOf(draft), HubFile.Role.Draft, model.repo, listing.commit)
                } else {
                    val draftListing = resolver.list(from.repo)
                    offline = offline || draftListing.offline
                    val draftSelection = resolvePlan(draftListing.files, from.tag.orEmpty(), from.file.orEmpty(),
                        false, true)
                    val draftFiles = draftSelection.dflash?.let { listOf(it) }
                        ?: draftSelection.modelFiles.takeIf { it.isNotEmpty() }
                        ?: throw HubException.NotFound("no DFlash draft or model in $from", emptyList())
                    add(draftFiles, HubFile.Role.Draft, from.repo, draftListing.commit)
                }
            }
            val notes = if (listing.files.any { it.path == "preset.ini" })
                listOf("preset.ini ignored: llama-server router presets aren't supported") else emptyList()
            val plan = HubPlan(model, listing.commit, chosen, offline, notes)
            plan.partialBytes = chosen.filterNot { it.cached }.sumOf { file ->
                // A verified blob not yet linked (killed before the link) is held in full; it only needs its link.
                val blob = File(cacheDir, "${repoFolder(file.sourceRepo)}/blobs/${file.oid}")
                val partial = File(cacheDir, "${repoFolder(file.sourceRepo)}/blobs/${file.oid}.downloadInProgress")
                if (file.oid != null && blob.isFile && blob.length() == file.sizeBytes) file.sizeBytes
                else partial.length().coerceAtMost(file.sizeBytes)
            }
            if (!offline) PlanStore(cacheDir, storage).write(plan, options)
            plan
        }

    /** Shares one transfer per selection; collecting again after cancellation resumes partials. */
    public fun download(model: HfModel, configure: PlanOptions.() -> Unit = {}): Flow<DownloadProgress> {
        val options = PlanOptions().apply(configure)
        val key = plans.key(model, options)
        selections.putIfAbsent(key, model to options)
        val state = stateFor(key, model, options)
        return transfers.join(key, run = { emit -> transfer(model, options, emit) }, onIdle = {
            val current = state.value
            if (current is DownloadState.Downloading) scope.launch {
                state.compareAndSet(current, diskState(model, options))
            }
        }).onEach { update ->
            state.value = if (update.phase == DownloadPhase.Complete) DownloadState.Complete
                else DownloadState.Downloading(update)
        }.catch { error ->
            // Only the transfer's own failures: a collector's exceptions pass through untouched.
            if (error is HubException) state.value = DownloadState.Failed(error)
            throw error
        }
    }

    /**
     * Shared process state. A selection first seen starts as [DownloadState.Absent] and is filled from the local plan and
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
                stateLoads[key] = scope.launch {
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
        val record = plans.read(model, options) ?: return if (offlineComplete(model, options))
            DownloadState.Complete else DownloadState.Absent
        if (record.files.isEmpty()) return DownloadState.Absent
        val alternate = options.draft?.from
        val alternateCommit = alternate?.let {
            Resolver(config.endpoint, cacheDir, transport, storage).cachedListing(it.repo)?.commit
        }
        var done = 0L
        var allComplete = true
        val total = record.files.sumOf { it.size }
        for (file in record.files) {
            val repo = if (file.role == HubFile.Role.Draft && alternate != null) alternate.repo else model.repo
            val commit = if (file.role == HubFile.Role.Draft && alternate != null) alternateCommit else record.commit
            val snapshot = commit?.let { File(cacheDir, "${repoFolder(repo)}/snapshots/$it/${file.path}") }
            if (snapshot?.isFile == true && snapshot.length() == file.size) {
                done += file.size
            } else {
                allComplete = false
                file.oid?.let { oid ->
                    // C.5: a verified blob not yet linked (killed before the link) counts in full.
                    val blob = File(cacheDir, "${repoFolder(repo)}/blobs/$oid")
                    val partial = File(cacheDir, "${repoFolder(repo)}/blobs/$oid.downloadInProgress")
                    done += if (blob.isFile && blob.length() == file.size) file.size
                        else partial.length().coerceAtMost(file.size)
                }
            }
        }
        return when {
            allComplete -> DownloadState.Complete
            done > 0 -> DownloadState.Partial(done, total)
            else -> DownloadState.Absent
        }
    }

    private fun offlineComplete(model: HfModel, options: PlanOptions): Boolean {
        val resolver = Resolver(config.endpoint, cacheDir, transport, storage)
        val listing = resolver.cachedListing(model.repo) ?: return false
        val selected = resolvePlan(listing.files, model.tag.orEmpty(), model.file.orEmpty(),
            options.mmproj, options.draft?.from == null && options.draft != null)
        if (!completeSplit(selected)) return false
        if (options.draft == null) return true
        if (options.draft?.from == null) return selected.dflash != null
        val from = options.draft!!.from!!
        val draftListing = resolver.cachedListing(from.repo) ?: return false
        val draft = resolvePlan(draftListing.files, from.tag.orEmpty(), from.file.orEmpty(), false, true)
        return draft.dflash != null || draft.modelFiles.isNotEmpty()
    }

    private suspend fun transfer(model: HfModel, options: PlanOptions,
                                 emit: suspend (DownloadProgress) -> Unit) {
        val meter = ProgressMeter(config.progressInterval, emit)
        meter.update(DownloadPhase.Resolving, 0, 0, null, 0, 0)
        val requestTransport = operationTransport()
        val plan = planInternal(model, options, requestTransport)
        cacheDir.mkdirs()
        val needed = plan.bytesToDownload
        val available = storage.allocatableBytes(cacheDir)
        if (needed > available) throw HubException.InsufficientStorage(needed, available)
        try { storage.allocate(cacheDir, needed) }
        catch (e: IOException) { throw HubException.InsufficientStorage(needed, storage.allocatableBytes(cacheDir)) }
        val done = AtomicLong(plan.bytesTotal - needed)
        meter.update(DownloadPhase.Downloading, done.get(), plan.bytesTotal,
            null, 0, plan.files.size)
        val downloader = BlobDownloader(config.endpoint, cacheDir, requestTransport, storage,
            config.logger, degradedSymlinks, openOutput, createSymlink)
        val permits = Semaphore(parallelDownloads)
        coroutineScope {
            plan.files.mapIndexed { index, file -> async(Dispatchers.IO) {
                permits.withPermit {
                    downloader.download(file) { delta, verifying ->
                        val count = done.addAndGet(delta)
                        meter.update(if (verifying) DownloadPhase.Verifying else DownloadPhase.Downloading,
                            count, plan.bytesTotal, file.path, index + 1, plan.files.size)
                    }
                }
            } }.awaitAll()
        }
        meter.update(DownloadPhase.Complete, plan.bytesTotal, plan.bytesTotal,
            null, 0, plan.files.size)
    }

    /** Lists quantized models already present in the local cache, without a network request. */
    public suspend fun cached(): List<CachedModel> = withContext(Dispatchers.IO) {
        Cache(cacheDir, Resolver(config.endpoint, cacheDir, transport, storage)).list()
    }

    /** Removes [model] from the local cache while keeping files selected by other tags. */
    public suspend fun delete(model: HfModel): Boolean = withContext(Dispatchers.IO) {
        val removed = Cache(cacheDir, Resolver(config.endpoint, cacheDir, transport, storage)).delete(model)
        if (removed) for ((key, selection) in selections) {
            val (selected, options) = selection
            if (selected.repo == model.repo || options.draft?.from?.repo == model.repo) {
                stateLoads[key]?.cancelAndJoin()
                states[key]?.value = diskState(selected, options)
            }
        }
        removed
    }

    /** Returns cached files as a core source without contacting the network. */
    public suspend fun source(model: HfModel, configure: PlanOptions.() -> Unit = {}): ModelSource =
        withContext(Dispatchers.IO) {
            val options = PlanOptions().apply(configure)
            val resolver = Resolver(config.endpoint, cacheDir, transport, storage)
            val listing = resolver.cachedListing(model.repo)
                ?: throw HubException.NotFound("model is not cached: $model", emptyList())
            plans.read(model, options)?.let { record ->
                val alternate = options.draft?.from
                val draftCommit = alternate?.let { resolver.cachedListing(it.repo)?.commit }
                for (file in record.files) {
                    val repo = if (file.role == HubFile.Role.Draft && alternate != null) alternate.repo else model.repo
                    val commit = if (file.role == HubFile.Role.Draft && alternate != null) draftCommit else record.commit
                    val cached = commit?.let { File(cacheDir, "${repoFolder(repo)}/snapshots/$it/${file.path}") }
                    if (cached?.isFile != true || cached.length() != file.size) {
                        throw HubException.NotFound("file is not cached: ${file.path}", emptyList())
                    }
                }
            }
            val selected = resolvePlan(listing.files, model.tag.orEmpty(), model.file.orEmpty(),
                options.mmproj, options.draft?.from == null && options.draft != null)
            val primary = selected.modelFiles.firstOrNull()?.takeIf { completeSplit(selected) }
                ?: throw HubException.NotFound("model is not cached: $model", emptyList())
            fun snapshot(repo: String, commit: String, file: RepoFile): String {
                val path = File(cacheDir, "${repoFolder(repo)}/snapshots/$commit/${file.path}")
                if (!path.isFile) throw HubException.NotFound("file is not cached: ${file.path}", emptyList())
                return path.absolutePath
            }
            selected.modelFiles.forEach { snapshot(model.repo, listing.commit, it) }
            val mmproj = selected.mmproj?.let { snapshot(model.repo, listing.commit, it) }
            val draft = options.draft?.let { request ->
                if (request.from == null) {
                    val file = selected.dflash ?: throw HubException.NotFound("draft is not cached: $model", emptyList())
                    snapshot(model.repo, listing.commit, file)
                } else {
                    val draftListing = resolver.cachedListing(request.from!!.repo)
                        ?: throw HubException.NotFound("draft is not cached: ${request.from}", emptyList())
                    val choice = resolvePlan(draftListing.files, request.from!!.tag.orEmpty(),
                        request.from!!.file.orEmpty(), false, true)
                    val files = choice.dflash?.let { listOf(it) } ?: choice.modelFiles
                    if (files.isEmpty()) throw HubException.NotFound("draft is not cached: ${request.from}", emptyList())
                    files.forEach { snapshot(request.from!!.repo, draftListing.commit, it) }
                    snapshot(request.from!!.repo, draftListing.commit, files.first())
                }
            }
            ModelSource.file(snapshot(model.repo, listing.commit, primary)) {
                this.mmproj = mmproj
                this.draft = draft
            }
        }

    /** Offline listings hold only the parts on disk: every part of a split model must be there. */
    private fun completeSplit(selected: Resolved): Boolean {
        val first = selected.modelFiles.firstOrNull() ?: return false
        val count = splitInfo(first.path).count
        return count <= 1 || selected.modelFiles.size == count
    }

    /** Estimates the memory and context for [plan] from bounded GGUF headers before downloading weights. */
    public suspend fun estimateFit(plan: HubPlan, llama: LlamaBro,
                                   configure: ModelConfig.() -> Unit = {}): FitEstimate = withContext(Dispatchers.IO) {
        val models = plan.files.filter { it.role == HubFile.Role.Model }
        val primary = models.firstOrNull()
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
            val mmproj = projector?.let { file ->
                val target = destination("mmproj", file)
                requireNotNull(target.parentFile).mkdirs()
                RandomAccessFile(target, "rw").use { it.setLength(file.sizeBytes) }
                target.absolutePath
            }
            val source = ModelSource.file(destination("model", primary).absolutePath) {
                this.mmproj = mmproj
                this.draft = drafts.firstOrNull()?.let { destination("draft", it).absolutePath }
            }
            llama.estimateFit(source, configure)
        } finally {
            root.deleteRecursively()
            root.parentFile?.delete() // the per-model folder, once no other estimate uses it
        }
    }

    /** Implement on your Application to configure the process's Hub instance. */
    public interface ConfigProvider {
        /** Read once, by the first [getInstance]. */
        public val llamaBroHubConfig: HubConfig
    }

    public companion object {
        @Volatile
        private var instance: LlamaBroHub? = null

        /** Uses the application's [ConfigProvider] or [HubConfig.Default] and its no-backup cache directory. */
        @JvmStatic
        public fun getInstance(context: Context): LlamaBroHub = instance ?: synchronized(this) {
            instance ?: run {
                val app = context.applicationContext
                val config = (app as? ConfigProvider)?.llamaBroHubConfig ?: HubConfig.Default
                val cacheDir = config.cacheDir ?: File(app.noBackupFilesDir, "llamabro/hub")
                LlamaBroHub(
                    config,
                    cacheDir,
                    HttpTransport(config.endpoint, token = null, logger = config.logger),
                    AndroidStorage(app),
                )
            }.also { instance = it }
        }
    }
}
