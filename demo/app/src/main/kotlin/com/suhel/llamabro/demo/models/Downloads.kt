package com.suhel.llamabro.demo.models

import androidx.work.WorkManager
import androidx.work.await
import com.suhel.llamabro.DeviceKind
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow

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

    data class Failed(val failure: DownloadFailure) : DownloadStatus

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
    enum class Kind { Network, RateLimited, NotFound, Unauthorized, IntegrityMismatch, InsufficientStorage }

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

/**
 * What the model sheet and the first-run picks read of one model file set, as plain values: the sizes of the model and
 * its image reader, and the SDK's memory projection. `HubPlan` and `FitEstimate` have no public constructors, so a
 * preview or a view model test could not build them; [ModelDiscovery.estimate] converts. [memoryBytes] is the
 * projection at the smallest context (the weights plus a little room); [chip] is the device it was projected for.
 */
data class HubEstimate(
    val modelBytes: Long,
    val mmprojBytes: Long,
    val fits: Boolean?,
    val contextTokens: Int?,
    val memoryBytes: Long,
    val chip: DeviceKind,
) {
    /** What the download fetches. */
    val totalBytes: Long get() = modelBytes + mmprojBytes
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
        // awaited: WorkManager keeps work that is still running (KEEP), so a Resume enqueued before the cancel is
        // recorded would be dropped, and the cancelled run's end would read as the new run's
        manager.value.cancelDownload(model).await()
    }

    override suspend fun isScheduled(model: HfModel): Boolean = infos(model).first().any { !it.state.isFinished }

    override fun ends(model: HfModel): Flow<Unit> =
        flow {
            var live = false
            infos(model).collect { infos ->
                if (infos.any { !it.state.isFinished }) {
                    live = true
                } else if (live && infos.isNotEmpty()) {
                    live = false
                    emit(Unit)
                }
            }
        }

    private fun infos(model: HfModel) = manager.value.getWorkInfosForUniqueWorkFlow(downloadWorkName(model))
}

/** A load was asked for a model that is not on the phone; the app downloads only when the person says Get (D64). */
class NotOnPhone(val model: HfModel) : Exception("$model is not on the phone")

/**
 * Whether every file of [model] is on the phone, read from disk only. `hub.cached()` alone is not enough: it also lists
 * a model whose first file has arrived while its image reader or later split parts are still missing, and `hub.model`
 * would fetch those in the foreground. `hub.source` reads the installed copy and fails with `NotFound` for such a
 * model.
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
 * [model]'s installed copy, as `hub.model` gives it to `llama.loadModel` (D64): a complete copy loads with no network
 * request. `hub.model` downloads what is missing, so a model not wholly on the phone (deleted since the library was
 * read, or still arriving) fails here with [NotOnPhone] instead: the app never starts a download the person didn't
 * ask for.
 */
suspend fun LlamaBroHub.installedModel(model: HfModel): ModelSource {
    if (!isInstalled(model)) throw NotOnPhone(model)
    return model(model)
}
