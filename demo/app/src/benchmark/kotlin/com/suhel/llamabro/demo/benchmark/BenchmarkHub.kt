package com.suhel.llamabro.demo.benchmark

import com.suhel.llamabro.demo.models.DownloadScheduler
import com.suhel.llamabro.hub.HfModel
import com.suhel.llamabro.hub.LlamaBroHub
import com.suhel.llamabro.hub.testing.FakeLlamaBroHub
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.emptyFlow

/**
 * The SDK's fake hub (D70, D75) with two models on the phone and nothing else to download: the benchmarks never touch
 * the network. [install] puts the two in its folder (a few KB each) once per process, before the seeding.
 */
class BenchmarkHub {
    private val fake =
        FakeLlamaBroHub {
            for (model in onPhone) {
                repo(model.repo) { gguf("${model.repo.substringAfter('/').removeSuffix("-GGUF")}-${model.tag}.gguf") }
            }
        }

    val hub: LlamaBroHub get() = fake.hub

    suspend fun install() {
        for (model in onPhone) hub.download(model).collect()
    }

    private companion object {
        val onPhone =
            listOf(
                HfModel.parse("unsloth/Qwen3.5-0.8B-GGUF:Q4_K_M"),
                HfModel.parse("unsloth/Qwen3.5-4B-GGUF:Q4_K_M"),
            )
    }
}

/** Nothing is downloaded in a benchmark. */
object NoDownloads : DownloadScheduler {
    override fun enqueue(model: HfModel) = Unit

    override suspend fun cancel(model: HfModel) = Unit

    override suspend fun isScheduled(model: HfModel): Boolean = false

    override fun ends(model: HfModel, seenRunning: Boolean): Flow<Unit> = emptyFlow()
}
