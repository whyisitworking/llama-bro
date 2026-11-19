package com.suhel.llamabro.demo.ui.models

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import com.suhel.llamabro.demo.designsystem.components.gutterPadding
import com.suhel.llamabro.demo.models.DownloadStatus
import com.suhel.llamabro.demo.models.FitView
import com.suhel.llamabro.demo.ui.preview.DemoPreviews
import com.suhel.llamabro.demo.ui.preview.PreviewFrame

// Previews of ModelRowItem.kt: each state of the screen or part, in light and dark.

internal class RowStates : PreviewParameterProvider<RowPreview> {
    override val values =
        sequenceOf(
            RowPreview("Great, with facts", ModelsPreviewData.swift, ModelsPreviewData.great, null, false),
            RowPreview("Slow, a result", ModelsPreviewData.nine, ModelsPreviewData.slow, null, false),
            RowPreview("Too big", ModelsPreviewData.nine, ModelsPreviewData.tooBig, null, false),
            RowPreview("Fit not known yet", ModelsPreviewData.result, null, null, false),
            RowPreview(
                "Downloading",
                ModelsPreviewData.swift,
                ModelsPreviewData.great,
                ModelsPreviewData.downloading,
                false,
            ),
            RowPreview("Paused", ModelsPreviewData.swift, ModelsPreviewData.great, ModelsPreviewData.paused, false),
            RowPreview("On this phone", ModelsPreviewData.swift, ModelsPreviewData.great, null, true),
            RowPreview(
                "Download failed",
                ModelsPreviewData.swift,
                ModelsPreviewData.great,
                ModelsPreviewData.failed,
                false,
            ),
        )
}

internal data class RowPreview(
    val name: String,
    val row: ModelRow,
    val fit: FitView?,
    val download: DownloadStatus?,
    val onPhone: Boolean,
) {
    override fun toString() = name
}

@DemoPreviews
@Composable
internal fun ModelRowItemPreview(
    @PreviewParameter(RowStates::class) state: RowPreview,
) {
    PreviewFrame {
        ModelRowItem(
            state.row,
            state.fit,
            state.download,
            state.onPhone,
            onClick = {},
            modifier = Modifier.gutterPadding(),
        )
    }
}
