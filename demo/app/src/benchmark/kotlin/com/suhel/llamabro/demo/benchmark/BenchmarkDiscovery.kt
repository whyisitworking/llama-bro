package com.suhel.llamabro.demo.benchmark

import com.suhel.llamabro.demo.models.ModelDiscovery
import com.suhel.llamabro.demo.models.RepoFile
import com.suhel.llamabro.hub.LlamaBroHub

/**
 * Hugging Face with every repo listing three sizes, each fitting: the model sheet's work is measured, not the network's
 * (the fake hub keeps its files a few KB, so real sizes are stood in here).
 */
class BenchmarkDiscovery(hub: LlamaBroHub) : ModelDiscovery(hub) {
    override suspend fun files(repo: String): List<RepoFile> =
        listOf(
            RepoFile("model-Q3_K_M.gguf", "Q3_K_M", 1_900_000_000),
            RepoFile("model-Q4_K_M.gguf", "Q4_K_M", 2_740_937_888),
            RepoFile("model-Q8_0.gguf", "Q8_0", 4_500_000_000),
        )
}
