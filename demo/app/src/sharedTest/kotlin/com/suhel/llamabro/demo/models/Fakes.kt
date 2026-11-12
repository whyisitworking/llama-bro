package com.suhel.llamabro.demo.models

import com.suhel.llamabro.FitEstimate
import com.suhel.llamabro.ModelSource
import com.suhel.llamabro.hub.DownloadProgress
import com.suhel.llamabro.hub.HfModel
import com.suhel.llamabro.hub.HubPlan
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import java.io.IOException

/** A hub with nothing on the network: what is "on the phone" and each model's status are set by the test. */
class FakeHubGateway : HubGateway {
    val cachedEntries = mutableListOf<CachedEntry>()
    val deleted = mutableListOf<HfModel>()
    private val states = mutableMapOf<HfModel, MutableStateFlow<DownloadStatus>>()

    /** What `source` does for a model; the default is a path that no one reads (the fake engine ignores files). */
    var onSource: suspend (HfModel) -> ModelSource = { ModelSource.file("/fake/${it.repo}.gguf") }

    fun status(model: HfModel): MutableStateFlow<DownloadStatus> =
        states.getOrPut(model) { MutableStateFlow(DownloadStatus.Absent) }

    override suspend fun plan(model: HfModel): HubPlan = throw UnsupportedOperationException("plan")

    override fun download(model: HfModel): Flow<DownloadProgress> = emptyFlow()

    override fun progress(model: HfModel): StateFlow<DownloadStatus> = status(model)

    override suspend fun source(model: HfModel): ModelSource = onSource(model)

    override suspend fun cached(): List<CachedEntry> = cachedEntries.toList()

    override suspend fun delete(model: HfModel) {
        deleted += model
        cachedEntries.removeAll { it.model == model }
        status(model).value = DownloadStatus.Absent
    }

    override suspend fun estimateFit(plan: HubPlan): FitEstimate = throw UnsupportedOperationException("estimateFit")

    /** What `estimate` answers per model; a test sets it, an unlisted model fails as an offline phone would. */
    val estimates = mutableMapOf<HfModel, HubEstimate>()

    /** Runs before every `estimate`: a test delays or counts here. */
    var onEstimate: suspend (HfModel) -> Unit = {}

    override suspend fun estimate(model: HfModel): HubEstimate {
        onEstimate(model)
        return estimates[model] ?: throw IOException("offline")
    }
}

class FakeScheduler : DownloadScheduler {
    val enqueued = mutableListOf<HfModel>()
    val cancelled = mutableListOf<HfModel>()

    override fun enqueue(model: HfModel) {
        enqueued += model
    }

    override fun cancel(model: HfModel) {
        cancelled += model
    }
}
