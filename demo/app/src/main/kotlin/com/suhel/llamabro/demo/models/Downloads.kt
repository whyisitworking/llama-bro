package com.suhel.llamabro.demo.models

import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.await
import com.suhel.llamabro.ModelSource
import com.suhel.llamabro.hub.CachedModel
import com.suhel.llamabro.hub.DownloadState
import com.suhel.llamabro.hub.HfModel
import com.suhel.llamabro.hub.HubException
import com.suhel.llamabro.hub.LlamaBroHub
import com.suhel.llamabro.hub.work.cancelDownload
import com.suhel.llamabro.hub.work.downloadWorkName
import com.suhel.llamabro.hub.work.enqueueDownload
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.runningFold

/**
 * What the screens read of a model's download, as plain values a preview can build: the SDK's `DownloadState` and
 * `HubException` have no public constructors. [ModelRepository.state] converts ([of]).
 */
sealed interface DownloadStatus {
    data object Absent : DownloadStatus

    data class Partial(val bytesDone: Long, val bytesTotal: Long) : DownloadStatus

    data class Downloading(
        val bytesDone: Long,
        val bytesTotal: Long,
        val bytesPerSecond: Long?,
        val etaSeconds: Long?,
    ) : DownloadStatus

    data object Complete : DownloadStatus

    /** Stopped by [failure], with what had arrived before it ([bytesDone] of [bytesTotal]; 0 when not seen). */
    data class Failed(val failure: DownloadFailure, val bytesDone: Long = 0, val bytesTotal: Long = 0) : DownloadStatus

    companion object {
        fun of(state: DownloadState): DownloadStatus =
            when (state) {
                DownloadState.Absent -> {
                    Absent
                }

                is DownloadState.Partial -> {
                    Partial(state.bytesDone, state.bytesTotal)
                }

                is DownloadState.Downloading -> {
                    state.progress.let { Downloading(it.bytesDone, it.bytesTotal, it.bytesPerSecond, it.etaSeconds) }
                }

                DownloadState.Complete -> {
                    Complete
                }

                is DownloadState.Failed -> {
                    Failed(DownloadFailure.from(state.error))
                }
            }
    }
}

/** Why a download stopped, mirroring `HubException`. [neededBytes] and [availableBytes] are set for storage. */
data class DownloadFailure(
    val kind: Kind,
    val retryable: Boolean,
    val neededBytes: Long? = null,
    val availableBytes: Long? = null,
) {
    enum class Kind { Network, RateLimited, NotFound, Corrupt, Unauthorized, IntegrityMismatch, InsufficientStorage }

    companion object {
        fun from(error: HubException): DownloadFailure =
            when (error) {
                is HubException.Network -> {
                    DownloadFailure(Kind.Network, error.retryable)
                }

                is HubException.RateLimited -> {
                    DownloadFailure(Kind.RateLimited, error.retryable)
                }

                is HubException.NotFound -> {
                    DownloadFailure(Kind.NotFound, error.retryable)
                }

                is HubException.Corrupt -> {
                    DownloadFailure(Kind.Corrupt, error.retryable)
                }

                is HubException.Unauthorized -> {
                    DownloadFailure(Kind.Unauthorized, error.retryable)
                }

                is HubException.IntegrityMismatch -> {
                    DownloadFailure(Kind.IntegrityMismatch, error.retryable)
                }

                is HubException.InsufficientStorage -> {
                    DownloadFailure(
                        Kind.InsufficientStorage,
                        error.retryable,
                        error.neededBytes,
                        error.availableBytes,
                    )
                }
            }
    }
}

/** Starts and stops a model's download, and says when its work runs and when it ends. */
interface DownloadScheduler {
    fun enqueue(model: HfModel)

    /** Stops [model]'s work and returns once it is recorded as stopped, so a later start is a new run. */
    suspend fun cancel(model: HfModel)

    /** Whether [model]'s work is queued or running now; it outlives the app's process. */
    suspend fun isScheduled(model: HfModel): Boolean

    /**
     * Emits each time [model]'s work, once seen queued or running, has ended: succeeded, failed (the worker gave up)
     * or cancelled (the notification's Cancel, say). A retry between tries is still queued, so it is not an end.
     */
    fun ends(model: HfModel): Flow<Unit>
}

/**
 * The SDK's background download (`llamabro-hub-work`, D64, D75): unique work per model, kept when one is queued or
 * running, over any network, expedited, retried while it makes progress; a cancel keeps the partial files for the next
 * `enqueue` to resume. The worker runs on the app's own hub through `LlamaBroWorkerFactory` (DemoApp).
 */
class WorkDownloadScheduler(private val manager: Lazy<WorkManager>) : DownloadScheduler {
    override fun enqueue(model: HfModel) {
        manager.value.enqueueDownload(model)
    }

    override suspend fun cancel(model: HfModel) {
        // awaited, so a Resume's enqueue after this cancel doesn't rest on WorkManager running both on its one serial
        // executor (it does today) to see the work cancelled rather than keep it (KEEP); the cancelled run's late end
        // is told from the new run's by ModelRepository's Run, not by this
        manager.value.cancelDownload(model).await()
    }

    override suspend fun isScheduled(model: HfModel): Boolean = infos(model).first().any { !it.state.isFinished }

    override fun ends(model: HfModel): Flow<Unit> = workEnds(infos(model))

    private fun infos(model: HfModel) = manager.value.getWorkInfosForUniqueWorkFlow(downloadWorkName(model))
}

/**
 * [DownloadScheduler.ends] over one unique work's states as WorkManager emits them: an end once every entry is
 * finished after one was seen queued or running. A retry goes back to queued, so it is not an end, and an entry that
 * had already finished before the first emission (an old run kept under the name) is not one either.
 */
internal fun workEnds(infos: Flow<List<WorkInfo>>): Flow<Unit> =
    infos
        .runningFold(WorkWatch()) { watch, now ->
            when {
                now.any { !it.state.isFinished } -> WorkWatch(live = true)
                watch.live && now.isNotEmpty() -> WorkWatch(ended = true)
                else -> watch.copy(ended = false)
            }
        }.filter { it.ended }
        .map { }

/** What [workEnds] has seen of a work: an entry queued or running since the last end, and whether it just ended. */
private data class WorkWatch(val live: Boolean = false, val ended: Boolean = false)

/** A load was asked for a model that is not on the phone; the app downloads only when the person says Get (D64). */
class NotOnPhone(val model: HfModel) : Exception("$model is not on the phone")

/**
 * Whether every file of [model] is on the phone, read from disk only: `hub.source` reads the installed copy and fails
 * with `NotFound` when there is none. `hub.cached()` lists the same models since D89; the check stays the one a load
 * makes ([installedModel]).
 */
suspend fun LlamaBroHub.isInstalled(model: HfModel): Boolean =
    try {
        source(model)
        true
    } catch (_: HubException.NotFound) {
        false
    }

/** The models whose every file is on the phone ([isInstalled]): what the library lists and a chat may load. */
suspend fun LlamaBroHub.installed(): List<CachedModel> = cached().filter { isInstalled(it.model) }

/**
 * [model]'s installed copy as local files (D64): `hub.source`, which reads the disk only, so a load never starts a
 * download the person didn't ask for. `hub.model` would fetch what is missing, and a check before it would leave a
 * window for a delete between the two. A model not wholly on the phone (deleted since the library was read, or still
 * arriving) fails with [NotOnPhone].
 */
suspend fun LlamaBroHub.installedModel(model: HfModel): ModelSource =
    try {
        source(model)
    } catch (_: HubException.NotFound) {
        throw NotOnPhone(model)
    }
