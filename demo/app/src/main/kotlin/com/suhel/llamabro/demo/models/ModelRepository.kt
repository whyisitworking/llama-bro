package com.suhel.llamabro.demo.models

import com.suhel.llamabro.demo.chat.ModelHost
import com.suhel.llamabro.demo.data.SettingsRepository
import com.suhel.llamabro.demo.models.DownloadFailure.Kind
import com.suhel.llamabro.hub.CachedModel
import com.suhel.llamabro.hub.DownloadState
import com.suhel.llamabro.hub.HfModel
import com.suhel.llamabro.hub.LlamaBroHub
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

/** A model on the phone. [name] is its own name (the catalog's, else tidied from its repo); never a nickname. */
data class LibraryModel(
    val key: HfModel,
    val name: String,
    val sizeBytes: Long,
    val lastUsed: Long?,
    val isDefault: Boolean,
)

/**
 * The models on the phone and their downloads, over the SDK's hub (D70). `pause` stops the work and keeps the hub's
 * partial; `get` starts it again and the hub resumes; `cancel` stops it and deletes the partial. [host], when given, is
 * released before a model it holds is removed. [lastUsed] is when each model (by key) last answered in a chat; a model
 * absent from it was never used. A download runs in the SDK's worker (`llamabro-hub-work`, D75) on this same hub, so
 * its progress is the hub's, and the library lists the model once the hub says it is complete.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ModelRepository(
    private val hub: LlamaBroHub,
    private val settings: SettingsRepository,
    private val scheduler: DownloadScheduler,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val host: ModelHost? = null,
    lastUsed: Flow<Map<String, Long>> = flowOf(emptyMap()),
) {
    private val changes = MutableStateFlow(0)

    private class Scan(val version: Int, val entries: List<CachedModel>)

    /**
     * The cache, scanned once per [changed] for every collector (the shell, each open chat, the library screen), not
     * once per collector. Tagged with the version it read, so a collector after [changed] waits for the new scan.
     */
    private val scan: StateFlow<Scan?> =
        changes
            .map { version -> Scan(version, hub.installed()) }
            .stateIn(scope, SharingStarted.Eagerly, null)

    val library: Flow<List<LibraryModel>> =
        combine(
            scan.filterNotNull().filter { it.version == changes.value },
            settings.settings.map { it.defaultModel }.distinctUntilChanged(),
            lastUsed.distinctUntilChanged(),
        ) { scanned, defaultKey, used ->
            scanned.entries
                .map { entry ->
                    LibraryModel(
                        key = entry.model,
                        name = Catalog.byRepo(entry.model.repo)?.realName ?: tidy(entry.model),
                        sizeBytes = entry.sizeBytes,
                        lastUsed = used[entry.model.toString()],
                        isDefault = entry.model.toString() == defaultKey,
                    )
                }.sortedWith(compareByDescending<LibraryModel> { it.lastUsed ?: 0L }.thenBy { it.name })
        }.distinctUntilChanged()

    /**
     * The downloads under way or paused, started here and not yet finished or cancelled, oldest first. The hub keeps a
     * partial file but has no list of them, so the app keeps the keys (settings), and each one's state is [state].
     */
    val downloading: Flow<List<HfModel>> =
        settings.settings.map { it.pendingDownloads }.distinctUntilChanged().map { keys ->
            keys.sorted().mapNotNull { runCatching { HfModel.parse(it) }.getOrNull() }
        }

    /**
     * One download at a time (F89: one transfer ran about twice as fast as four on the phone; demo design §6). The
     * SDK's worker runs each Get as work of its own, so the queue is kept here: [active] is the download handed to the
     * worker, and [waiting] the ones after it, oldest first. Every change to the queue is one step on [steps], run in
     * the order asked, so a Pause and a Resume tapped quickly can't both start a download. At start the queue is
     * rebuilt from the pending downloads and WorkManager's work ([restore]), so it carries on after a restart.
     */
    private val steps = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    private val active = MutableStateFlow<Run?>(null)
    private var runs = 0L

    /**
     * One hand-over of [model] to the worker. An end is tied to the run it came from, so the late end of a run a Pause
     * stopped can't end the run a Resume started after it (both are the same model's work).
     */
    private data class Run(val model: HfModel, val number: Long)

    private val waiting = mutableListOf<HfModel>()

    private fun step(block: suspend () -> Unit) {
        steps.trySend(block)
    }

    private suspend fun stepAndWait(block: suspend () -> Unit) {
        val done = CompletableDeferred<Unit>()
        steps.send {
            try {
                block()
            } finally {
                done.complete(Unit)
            }
        }
        done.await()
    }

    /**
     * Starts the download, or queues it behind the one running; does nothing when every file of the model is on the
     * phone. A model only partly there (its image reader still missing, say) is downloaded, which resumes it.
     */
    fun get(model: HfModel) {
        step {
            if (!hub.isInstalled(model)) {
                settings.addPendingDownload(model.toString())
                settings.setDownloadPaused(model.toString(), false)
                when (active.value?.model) {
                    null, model -> start(model)

                    // again for the one running: a Retry after a failed try

                    else -> if (model !in waiting) waiting += model
                }
            }
        }
    }

    /** [model] no longer downloads: it leaves the queue, and when it was the one running the next one starts. */
    private fun finish(model: HfModel) {
        waiting -= model
        if (active.value?.model == model) {
            active.value = null
            waiting.removeFirstOrNull()?.let(::start)
        }
    }

    private fun start(model: HfModel) {
        active.value = Run(model, ++runs)
        scheduler.enqueue(model)
    }

    /**
     * [run]'s work ended, or the hub has it complete ([completed]). An end that is not a completion (the notification's
     * Cancel, the worker giving up) leaves the download paused, as the person saw it stop, so the next launch does not
     * start it again unasked (D64); Resume or Retry does. An end of a run since replaced changes nothing.
     */
    private suspend fun ended(run: Run, completed: Boolean) {
        if (active.value != run) return
        if (!completed && !hub.isInstalled(run.model)) settings.setDownloadPaused(run.model.toString(), true)
        finish(run.model)
    }

    /**
     * The queue as the last process left it: of the pending downloads not paused and not yet on the phone, the one
     * whose work WorkManager still runs is [active] (another found running is stopped and queued, one at a time), and
     * the rest wait, in the order [downloading] lists them; with none running the first of them starts.
     */
    private suspend fun restore() {
        val saved = settings.settings.first()
        val keys = saved.pendingDownloads.sorted().mapNotNull { runCatching { HfModel.parse(it) }.getOrNull() }
        for (key in keys) {
            if (key.toString() in saved.pausedDownloads || hub.isInstalled(key)) continue
            val scheduled = scheduler.isScheduled(key)
            when {
                scheduled && active.value == null -> {
                    active.value = Run(key, ++runs)
                }

                scheduled -> {
                    scheduler.cancel(key)
                    waiting += key
                }

                else -> {
                    waiting += key
                }
            }
        }
        if (active.value == null) waiting.removeFirstOrNull()?.let(::start)
    }

    /** A token was saved: the downloads Hugging Face refused for want of one start again. */
    fun tokenSaved() {
        scope.launch {
            val refused =
                settings.settings
                    .first()
                    .pendingDownloads
                    .mapNotNull { runCatching { HfModel.parse(it) }.getOrNull() }
                    .filter { (state(it).value as? DownloadStatus.Failed)?.failure?.kind == Kind.Unauthorized }
            refused.forEach(::get)
        }
    }

    /**
     * Stops the work and keeps the partial: the state reads `Partial` (a download that hadn't started too, with no
     * bytes), it stays paused across a restart, and `get` resumes it. The next one waiting starts.
     */
    fun pause(model: HfModel) {
        step {
            scheduler.cancel(model)
            settings.setDownloadPaused(model.toString(), true)
            finish(model)
        }
    }

    /** Stops the work and deletes what had arrived. */
    fun cancel(model: HfModel) {
        scope.launch {
            stepAndWait {
                scheduler.cancel(model)
                finish(model)
            }
            awaitStopped(model)
            delete(model)
            settings.removePendingDownloads(listOf(model.toString()))
            changed()
        }
    }

    /**
     * Deletes a model from the phone: closes it first when it is in memory (`ModelHost.close`), and unpins it as the
     * default. Deleting a model a reply is streaming from waits for that reply to end.
     */
    suspend fun remove(model: HfModel) {
        stepAndWait {
            scheduler.cancel(model)
            finish(model)
        }
        host?.close(model)
        awaitStopped(model)
        if (settings.settings.first().defaultModel == model.toString()) settings.pinDefault(null)
        delete(model)
        settings.removePendingDownloads(listOf(model.toString()))
        changed()
    }

    /** One mapped state per model for the life of the repository; the hub's own state flows live that long too. */
    private val states = ConcurrentHashMap<HfModel, StateFlow<DownloadStatus>>()

    /** [model]'s download as the screens read it; one paused before any byte arrived reads `Partial` with none. */
    fun state(model: HfModel): StateFlow<DownloadStatus> =
        states.computeIfAbsent(model) {
            val source = hub.progress(model)
            val paused = settings.settings.map { model.toString() in it.pausedDownloads }.distinctUntilChanged()
            combine(source.map(DownloadStatus::of), paused) { status, isPaused ->
                if (isPaused && status == DownloadStatus.Absent) DownloadStatus.Partial(0, 0) else status
            }.stateIn(scope, SharingStarted.Eagerly, DownloadStatus.of(source.value))
        }

    init {
        // the worker tells no one when it ends; the hub's state does, in this process: a download started here that
        // completes puts its model in the library (a worker that ends while the app is gone is found by the next scan)
        scope.launch {
            downloading
                .flatMapLatest { keys ->
                    if (keys.isEmpty()) {
                        emptyFlow()
                    } else {
                        keys.map { key -> hub.progress(key).filter { it == DownloadState.Complete } }.merge()
                    }
                }.collect { changed() }
        }
        // one step at a time, in the order asked; a step that fails leaves the queue as it was and the next one runs
        scope.launch { for (next in steps) attempt { next() } }
        step(::restore)
        // the download running ends when its work does (succeeded, failed for good, or cancelled from outside the app:
        // the notification's Cancel) or when the hub has it complete, whichever is seen first, including a completion
        // already there when the watch starts; a try the worker retries is still queued, so it keeps its place
        scope.launch {
            active
                .filterNotNull()
                .flatMapLatest { run ->
                    merge(
                        scheduler.ends(run.model).map { false },
                        hub.progress(run.model).filter { it == DownloadState.Complete }.map { true },
                    ).map { completed -> run to completed }
                }.collect { (run, completed) -> step { ended(run, completed) } }
        }
    }

    /** A model arrived or went: the library scans the hub again. */
    internal fun changed() {
        changes.update { it + 1 }
        scope.launch {
            val onPhone = hub.installed().map { it.model.toString() }.toSet()
            val finished =
                settings.settings
                    .first()
                    .pendingDownloads
                    .filter { it in onPhone }
            settings.removePendingDownloads(finished)
        }
    }

    /**
     * One deletion at a time: two removes of one model (tapped twice, or Cancel beside Remove) would otherwise delete
     * the same files at once, and the second meets them gone mid-way.
     */
    private val deletions = Mutex()

    private suspend fun delete(model: HfModel) {
        deletions.withLock { hub.delete(model) }
    }

    /** After the work is told to stop, a running transfer takes a moment to let go of its files. */
    private suspend fun awaitStopped(model: HfModel) {
        withTimeoutOrNull(STOP_WAIT_MILLIS) { hub.progress(model).first { it !is DownloadState.Downloading } }
    }

    private fun tidy(model: HfModel): String = model.repo.substringAfter('/').removeSuffix("-GGUF")

    private companion object {
        /** Builder's call, settled here: a cancelled transfer stops within a second or two. */
        const val STOP_WAIT_MILLIS = 5_000L
    }
}
