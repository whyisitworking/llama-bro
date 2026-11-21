package com.suhel.llamabro.demo.ui.models

import com.suhel.llamabro.demo.models.Capability
import com.suhel.llamabro.demo.models.Catalog
import com.suhel.llamabro.demo.models.DownloadFailure
import com.suhel.llamabro.demo.models.DownloadStatus
import com.suhel.llamabro.demo.models.FitLabel
import com.suhel.llamabro.demo.models.FitView
import com.suhel.llamabro.demo.models.RepoFile

/** Fixed values the previews of the model screens share, so each preview shows the same believable phone. */
internal object ModelsPreviewData {
    val swift = ModelEntry.of(Catalog.suggested[0])
    val writer = ModelEntry.of(Catalog.suggested[1])
    val deep = ModelEntry.of(Catalog.suggested[2])
    val nine = ModelEntry.of(Catalog.suggested[4])
    val result =
        ModelEntry(
            groupKey = "phi-4-mini",
            name = "Phi 4 mini",
            repo = "bartowski/phi-4-mini-GGUF",
            downloads = 412_000,
            maker = null,
            catalog = null,
        )

    val embedder =
        ModelEntry(
            groupKey = "qwen3-embedding-0.6b",
            name = "Qwen3 Embedding 0.6B",
            repo = "Qwen/Qwen3-Embedding-0.6B-GGUF",
            downloads = 220_000,
            maker = "Alibaba Qwen",
            catalog = null,
            capabilities = listOf(Capability.Embeddings),
            canChat = false,
        )
    val coder =
        ModelEntry(
            groupKey = "qwen2.5-coder-7b",
            name = "Qwen2.5 Coder 7B",
            repo = "bartowski/Qwen2.5-Coder-7B-GGUF",
            downloads = 1_200_000,
            maker = "Alibaba Qwen",
            catalog = null,
            capabilities = listOf(Capability.Coding),
        )

    val great = FitView(FitLabel.RunsGreat, 59)
    val well = FitView(FitLabel.RunsWell, 8)
    val slow = FitView(FitLabel.SlowHere, 4)
    val tooBig = FitView(FitLabel.TooBig, 0)

    val downloading = DownloadStatus.Downloading(310_000_000, 740_000_000, 9_800_000, 44)
    val paused = DownloadStatus.Partial(310_000_000, 740_000_000)
    val failed = DownloadStatus.Failed(DownloadFailure(DownloadFailure.Kind.Network, retryable = true))

    val q3 = RepoFile("Qwen3.5-4B-Q3_K_M.gguf", "Q3_K_M", 1_900_000_000)
    val q4 = RepoFile("Qwen3.5-4B-Q4_K_M.gguf", "Q4_K_M", 2_740_937_888)
    val q8 = RepoFile("Qwen3.5-4B-Q8_0.gguf", "Q8_0", 4_500_000_000)
    val bf16 = RepoFile("Qwen3.5-4B-BF16.gguf", "BF16", 8_400_000_000)
}
