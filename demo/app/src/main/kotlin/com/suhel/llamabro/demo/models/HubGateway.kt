package com.suhel.llamabro.demo.models

import com.suhel.llamabro.DeviceKind
import com.suhel.llamabro.FitEstimate
import com.suhel.llamabro.LlamaBro
import com.suhel.llamabro.ModelSource
import com.suhel.llamabro.hub.DownloadProgress
import com.suhel.llamabro.hub.DownloadState
import com.suhel.llamabro.hub.HfModel
import com.suhel.llamabro.hub.HubException
import com.suhel.llamabro.hub.HubFile
import com.suhel.llamabro.hub.HubPlan
import com.suhel.llamabro.hub.LlamaBroHub
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.util.concurrent.ConcurrentHashMap

/** A model fully on the phone, as the hub lists it. */
data class CachedEntry(val model: HfModel, val sizeBytes: Long)

/**
 * What the app reads of a model's download. The SDK's `DownloadState`, `DownloadProgress` and `CachedModel` have no
 * public constructors, so a fake hub in a JVM test could not build them; this mirrors them with plain values
 * ([RealHubGateway] converts).
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
 * its image reader, and the SDK's memory projection. `HubPlan` and `FitEstimate` have no public constructors, so a fake
 * hub in a JVM test could not build them; [RealHubGateway] converts. [memoryBytes] is the projection at the smallest
 * context (the weights plus a little room); [chip] is the device it was projected for.
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

/** The app's one door to the SDK's hub, so ViewModels and engines can be tested against a fake. */
interface HubGateway {
    suspend fun plan(model: HfModel): HubPlan

    fun download(model: HfModel): Flow<DownloadProgress>

    fun progress(model: HfModel): StateFlow<DownloadStatus>

    suspend fun source(model: HfModel): ModelSource

    suspend fun cached(): List<CachedEntry>

    suspend fun delete(model: HfModel)

    suspend fun estimateFit(plan: HubPlan): FitEstimate

    /** The plan and the memory projection of [model] in one call (the network and the GGUF headers). */
    suspend fun estimate(model: HfModel): HubEstimate
}

class RealHubGateway(private val hub: LlamaBroHub, private val llamaBro: LlamaBro) : HubGateway {
    // one mapped state per model for the life of the process; the hub's own state flows live that long too
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val states = ConcurrentHashMap<HfModel, StateFlow<DownloadStatus>>()

    override suspend fun plan(model: HfModel): HubPlan = hub.plan(model)

    override fun download(model: HfModel): Flow<DownloadProgress> = hub.download(model)

    override fun progress(model: HfModel): StateFlow<DownloadStatus> =
        states.computeIfAbsent(model) {
            val source = hub.progress(model)
            source.map(::toStatus).stateIn(scope, SharingStarted.Eagerly, toStatus(source.value))
        }

    override suspend fun source(model: HfModel): ModelSource = hub.source(model)

    override suspend fun cached(): List<CachedEntry> = hub.cached().map { CachedEntry(it.model, it.sizeBytes) }

    override suspend fun delete(model: HfModel) {
        hub.delete(model)
    }

    override suspend fun estimateFit(plan: HubPlan): FitEstimate = hub.estimateFit(plan, llamaBro)

    override suspend fun estimate(model: HfModel): HubEstimate {
        val plan = hub.plan(model)
        val fit = hub.estimateFit(plan, llamaBro)

        fun bytes(role: HubFile.Role) = plan.files.filter { it.role == role }.sumOf { it.sizeBytes }
        return HubEstimate(
            modelBytes = bytes(HubFile.Role.Model),
            mmprojBytes = bytes(HubFile.Role.Mmproj),
            fits = fit.fits,
            contextTokens = fit.contextTokens,
            memoryBytes = fit.bytesAtMinimum,
            chip = fit.device,
        )
    }

    private fun toStatus(state: DownloadState): DownloadStatus =
        when (state) {
            DownloadState.Absent -> {
                DownloadStatus.Absent
            }

            is DownloadState.Partial -> {
                DownloadStatus.Partial(state.bytesDone, state.bytesTotal)
            }

            is DownloadState.Downloading -> {
                state.progress.let {
                    DownloadStatus.Downloading(it.bytesDone, it.bytesTotal, it.bytesPerSecond, it.etaSeconds)
                }
            }

            DownloadState.Complete -> {
                DownloadStatus.Complete
            }

            is DownloadState.Failed -> {
                DownloadStatus.Failed(DownloadFailure.from(state.error))
            }
        }
}
