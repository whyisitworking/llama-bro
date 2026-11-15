package com.suhel.llamabro.demo.benchmark

import com.suhel.llamabro.DeviceKind
import com.suhel.llamabro.DownloadProgress
import com.suhel.llamabro.FitEstimate
import com.suhel.llamabro.ModelSource
import com.suhel.llamabro.demo.models.CachedEntry
import com.suhel.llamabro.demo.models.DownloadScheduler
import com.suhel.llamabro.demo.models.DownloadStatus
import com.suhel.llamabro.demo.models.HubEstimate
import com.suhel.llamabro.demo.models.HubGateway
import com.suhel.llamabro.hub.HfModel
import com.suhel.llamabro.hub.HubPlan
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow

/** A hub with two models on the phone and nothing to download: the benchmarks never touch the network. */
class BenchmarkHub : HubGateway {
    private val onPhone =
        listOf(
            CachedEntry(HfModel.parse("unsloth/Qwen3.5-0.8B-GGUF:Q4_K_M"), 739_000_000),
            CachedEntry(HfModel.parse("unsloth/Qwen3.5-4B-GGUF:Q4_K_M"), 3_400_000_000),
        )

    override suspend fun plan(model: HfModel): HubPlan = throw UnsupportedOperationException("plan")

    override fun download(model: HfModel): Flow<DownloadProgress> = emptyFlow()

    override fun progress(model: HfModel): StateFlow<DownloadStatus> = MutableStateFlow(DownloadStatus.Complete)

    override suspend fun source(model: HfModel): ModelSource = ModelSource.file("/benchmark/${model.repo}.gguf")

    override suspend fun cached(): List<CachedEntry> = onPhone

    override suspend fun delete(model: HfModel) = Unit

    override suspend fun estimateFit(plan: HubPlan): FitEstimate = throw UnsupportedOperationException("estimateFit")

    override suspend fun estimate(model: HfModel): HubEstimate =
        HubEstimate(model.repo.length * 1_000_000L, 0, true, 32_768, 1_200_000_000, DeviceKind.Npu)
}

/** Nothing is downloaded in a benchmark. */
object NoDownloads : DownloadScheduler {
    override fun enqueue(model: HfModel) = Unit

    override fun cancel(model: HfModel) = Unit
}
