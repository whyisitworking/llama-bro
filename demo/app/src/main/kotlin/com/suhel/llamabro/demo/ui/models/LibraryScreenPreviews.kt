package com.suhel.llamabro.demo.ui.models

import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import androidx.compose.ui.unit.dp
import com.suhel.llamabro.demo.models.DownloadStatus
import com.suhel.llamabro.demo.models.LibraryModel
import com.suhel.llamabro.demo.ui.preview.DemoPreviews
import com.suhel.llamabro.demo.ui.preview.DemoScreenPreviews
import com.suhel.llamabro.demo.ui.preview.PreviewFrame
import com.suhel.llamabro.hub.HfModel

// Previews of LibraryScreen.kt: each state of the screen or part, in light and dark.

private val deepKey = HfModel.parse("unsloth/Qwen3.5-4B-GGUF:Q4_K_M")
private val swiftKey = HfModel.parse("unsloth/Qwen3.5-0.8B-GGUF:Q4_K_M")

private val previewModels =
    listOf(
        LibraryModel(swiftKey, "Qwen3.5 0.8B", 739_863_648, 0, isDefault = false),
        LibraryModel(
            HfModel.parse("unsloth/gemma-3-4b-it-qat-GGUF:Q4_K_M"),
            "Gemma 3 4B",
            2_489_894_016,
            0,
            isDefault = true,
        ),
        LibraryModel(deepKey, "Qwen3.5 4B", 2_740_937_888, 0, isDefault = false),
    )

internal data class LibraryPreview(
    val name: String,
    val storage: StorageInfo?,
    val models: List<LibraryModel>?,
    val downloads: List<HfModel>,
    val pendingRemove: LibraryModel? = null,
) {
    override fun toString() = name
}

internal class LibraryStates : PreviewParameterProvider<LibraryPreview> {
    private val storage = StorageInfo(6_100_000_000, 120_000_000_000)
    override val values =
        sequenceOf(
            LibraryPreview("Models and a download", storage, previewModels, listOf(swiftKey)),
            LibraryPreview("Asking to remove", storage, previewModels, emptyList(), previewModels[2]),
            LibraryPreview("Empty", StorageInfo(0, 120_000_000_000), emptyList(), emptyList()),
            LibraryPreview("Loading", null, null, emptyList()),
        )
}

@DemoScreenPreviews
@Composable
internal fun LibraryScreenPreview(
    @PreviewParameter(LibraryStates::class) p: LibraryPreview,
) {
    PreviewFrame {
        LibraryScreen(
            storage = p.storage,
            models = p.models,
            downloads = p.downloads,
            pendingRemove = p.pendingRemove,
            lastUsed = { "Used today" },
            onAskRemove = {},
            onConfirmRemove = {},
            onDismissRemove = {},
            onGetMore = {},
            onBack = {},
            modifier = Modifier.height(780.dp),
        ) { key -> DownloadItem(key, ModelsPreviewData.downloading, {}, {}, {}) }
    }
}

internal class DownloadStates : PreviewParameterProvider<DownloadStatus> {
    override val values =
        sequenceOf(
            ModelsPreviewData.downloading,
            ModelsPreviewData.paused,
            ModelsPreviewData.failed,
            DownloadStatus.Absent,
        )
}

@DemoPreviews
@Composable
internal fun DownloadItemPreview(
    @PreviewParameter(DownloadStates::class) status: DownloadStatus,
) {
    PreviewFrame { DownloadItem(swiftKey, status, {}, {}, {}) }
}
