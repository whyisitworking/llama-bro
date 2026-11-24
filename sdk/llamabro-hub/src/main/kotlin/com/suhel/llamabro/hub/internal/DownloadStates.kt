package com.suhel.llamabro.hub.internal

import com.suhel.llamabro.LogLevel
import com.suhel.llamabro.Logger
import com.suhel.llamabro.hub.DownloadState
import com.suhel.llamabro.hub.HfModel
import com.suhel.llamabro.hub.HubFile
import com.suhel.llamabro.hub.PlanOptions
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Owns the process's observable state of each selection: one [MutableStateFlow] per selection key, the selections seen
 * (so a delete can find the ones it affects) and the disk read that fills a state the first time it is asked for. A
 * state starts [DownloadState.Absent] and is filled on `Dispatchers.IO`, so asking for it on the main thread does no
 * disk I/O. The disk reads never throw out of the scope: a failed read leaves the state as it is and never kills the
 * app.
 *
 * What a download is doing is not decided here: the hub sets a state from the transfer's progress, and calls back
 * ([settleIdle], [refreshAfterDelete]) when the disk should say again.
 */
internal class DownloadStates(
    private val plans: PlanStore,
    private val copies: LocalCopies,
    private val lookup: CacheLookup,
    private val planner: Planner,
    logger: Logger?,
) {
    private val states = ConcurrentHashMap<String, MutableStateFlow<DownloadState>>()
    private val loads = ConcurrentHashMap<String, Job>()
    private val selections = ConcurrentHashMap<String, Pair<HfModel, PlanOptions>>()
    private val scope =
        CoroutineScope(
            SupervisorJob() + Dispatchers.IO +
                CoroutineExceptionHandler { _, error -> logger?.log(LogLevel.Warn, "hub state read failed: $error") },
        )

    /** The key of the selection, the one the plan records and transfers go by. */
    fun key(model: HfModel, options: PlanOptions): String = plans.key(model, options)

    /** The state of the selection under [key], remembering the selection; started (and read from disk) on first use. */
    fun track(key: String, model: HfModel, options: PlanOptions): MutableStateFlow<DownloadState> {
        selections.putIfAbsent(key, model to options)
        return stateFor(key, model, options)
    }

    /** Tests: waits until the state's first read from disk is done. */
    suspend fun awaitLoaded(model: HfModel, options: PlanOptions) {
        val key = key(model, options)
        // for its side effect: the first call starts the read from disk that the join waits for
        @Suppress("IgnoredReturnValue")
        stateFor(key, model, options)
        loads[key]?.join()
    }

    /** The selection first seen under [key], or null. */
    fun selectionOf(key: String): Pair<HfModel, PlanOptions>? = selections[key]

    /**
     * A download that ended without completing leaves [state] as the disk says, but only if it is still `Downloading`
     * as [current] read it then: a state something else has since moved (a delete's refresh) is left alone.
     */
    fun settleIdle(state: MutableStateFlow<DownloadState>, model: HfModel, options: PlanOptions) {
        val current = state.value
        if (current is DownloadState.Downloading) {
            scope.launch {
                state.compareAndSet(current, diskState(model, options))
            }
        }
    }

    /** After a delete of [deleted]: each selection using its repo, as a model or a draft, is read from disk again. */
    suspend fun refreshAfterDelete(deleted: HfModel) {
        for ((key, selection) in selections) {
            val (selected, options) = selection
            if (selected.repo == deleted.repo || options.draft?.from?.repo == deleted.repo) {
                loads[key]?.cancelAndJoin()
                states[key]?.value = diskState(selected, options)
            }
        }
    }

    private fun stateFor(key: String, model: HfModel, options: PlanOptions): MutableStateFlow<DownloadState> =
        states.computeIfAbsent(key) {
            MutableStateFlow<DownloadState>(DownloadState.Absent).also { flow ->
                loads[key] =
                    scope.launch {
                        val loaded = diskState(model, options)
                        ensureActive() // delete cancels a read it would otherwise race (it sets the state itself)
                        flow.compareAndSet(DownloadState.Absent, loaded)
                    }
            }
        }

    /** What the disk holds of the selection, with no network request. */
    private fun diskState(model: HfModel, options: PlanOptions): DownloadState {
        // What loads is complete, whatever a later plan or an unfinished update left in the plan record (D64).
        if (copies.installedSource(model, options) != null) return DownloadState.Complete
        val record =
            plans.read(model, options)
                ?: return if (planner.isCachedComplete(model, options)) DownloadState.Complete else DownloadState.Absent
        if (record.files.isEmpty()) return DownloadState.Absent
        val alternate = options.draft?.from
        val alternateCommit = alternate?.let { planner.cachedListing(it.repo)?.commit }
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
}
