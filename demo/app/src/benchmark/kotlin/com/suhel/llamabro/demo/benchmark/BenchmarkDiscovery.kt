package com.suhel.llamabro.demo.benchmark

import com.suhel.llamabro.DeviceKind
import com.suhel.llamabro.demo.models.DeviceFacts
import com.suhel.llamabro.demo.models.HfSearch
import com.suhel.llamabro.demo.models.ModelDiscovery
import com.suhel.llamabro.demo.models.RepoFile

/** Hugging Face with every repo listing three sizes: the model sheet's work is measured, not the network's. */
class BenchmarkDiscovery : ModelDiscovery(HfSearch(token = { null }), { DeviceFacts(8L shl 30, DeviceKind.Npu) }) {
    override suspend fun files(repo: String): List<RepoFile> =
        listOf(
            RepoFile("model-Q3_K_M.gguf", "Q3_K_M", 1_900_000_000),
            RepoFile("model-Q4_K_M.gguf", "Q4_K_M", 2_740_937_888),
            RepoFile("model-Q8_0.gguf", "Q8_0", 4_500_000_000),
        )
}
