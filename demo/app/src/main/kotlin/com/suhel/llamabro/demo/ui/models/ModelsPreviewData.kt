package com.suhel.llamabro.demo.ui.models

import com.suhel.llamabro.demo.models.Catalog
import com.suhel.llamabro.demo.models.DownloadFailure
import com.suhel.llamabro.demo.models.DownloadStatus
import com.suhel.llamabro.demo.models.FitLabel
import com.suhel.llamabro.demo.models.FitView
import com.suhel.llamabro.demo.models.RepoFile

/** Fixed values the previews of the model screens share, so each preview shows the same believable phone. */
internal object ModelsPreviewData {
    val swift = ModelRow.of(Catalog.suggested[0])
    val writer = ModelRow.of(Catalog.suggested[1])
    val deep = ModelRow.of(Catalog.suggested[2])
    val nine = ModelRow.of(Catalog.suggested[4])
    val result =
        ModelRow(
            groupKey = "phi-4-mini",
            name = "Phi 4 mini",
            repo = "bartowski/phi-4-mini-GGUF",
            line = "Balanced, a good all-rounder",
            sees = false,
            thinks = false,
            downloads = 412_000,
            maker = null,
            catalog = null,
        )

    val great = FitView(FitLabel.RunsGreat, 59, real = false)
    val well = FitView(FitLabel.RunsWell, 8, real = true)
    val slow = FitView(FitLabel.SlowHere, 4, real = true)
    val tooBig = FitView(FitLabel.TooBig, 0, real = true)

    val downloading = DownloadStatus.Downloading(310_000_000, 740_000_000, 9_800_000, 44)
    val paused = DownloadStatus.Partial(310_000_000, 740_000_000)
    val failed = DownloadStatus.Failed(DownloadFailure(DownloadFailure.Kind.Network, retryable = true))

    val q3 = RepoFile("Qwen3.5-4B-Q3_K_M.gguf", "Q3_K_M", 1_900_000_000)
    val q4 = RepoFile("Qwen3.5-4B-Q4_K_M.gguf", "Q4_K_M", 2_740_937_888)
    val q8 = RepoFile("Qwen3.5-4B-Q8_0.gguf", "Q8_0", 4_500_000_000)
    val bf16 = RepoFile("Qwen3.5-4B-BF16.gguf", "BF16", 8_400_000_000)

    fun fitOf(file: RepoFile): FileFit? =
        when (file.quant) {
            "Q3_K_M" -> FileFit(great, file.bytes + 675_569_344, 675_569_344, null)
            "Q4_K_M" -> FileFit(well, file.bytes + 675_569_344, 675_569_344, null)
            "Q8_0" -> FileFit(slow, file.bytes + 675_569_344, 675_569_344, null)
            "BF16" -> FileFit(tooBig, file.bytes + 675_569_344, 675_569_344, null)
            else -> null
        }
}
