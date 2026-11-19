package com.suhel.llamabro.demo.ui.models

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import com.suhel.llamabro.demo.designsystem.components.SheetFrame
import com.suhel.llamabro.demo.designsystem.components.gutterPadding
import com.suhel.llamabro.demo.models.RepoFile
import com.suhel.llamabro.demo.models.SizeTier
import com.suhel.llamabro.demo.ui.preview.DemoPreviews
import com.suhel.llamabro.demo.ui.preview.PreviewFrame

// Previews of ModelSheet.kt: each state of the screen or part, in light and dark.

internal data class SheetPreview(
    val name: String,
    val state: SheetState,
    val mode: SheetMode,
    val selected: RepoFile?,
    val button: String,
    val reader: Long?,
) {
    override fun toString() = name
}

private val previewTiers =
    listOf(
        SizeTier.Smaller to ModelsPreviewData.q3,
        SizeTier.Recommended to ModelsPreviewData.q4,
        SizeTier.Best to ModelsPreviewData.q8,
    )
private val previewReady =
    SheetState.Ready(
        previewTiers,
        listOf(ModelsPreviewData.q3, ModelsPreviewData.q4, ModelsPreviewData.q8, ModelsPreviewData.bf16),
        ModelsPreviewData.q4,
    )

internal class SheetStates : PreviewParameterProvider<SheetPreview> {
    override val values =
        sequenceOf(
            SheetPreview(
                "Basic",
                previewReady,
                SheetMode.Basic,
                ModelsPreviewData.q4,
                "Get Qwen3.5 4B · about 8 min",
                675_569_344,
            ),
            SheetPreview(
                "Advanced",
                previewReady,
                SheetMode.Advanced,
                ModelsPreviewData.q4,
                "Get Q4_K_M · 3.4 GB in all",
                675_569_344,
            ),
            SheetPreview("Loading", SheetState.Loading, SheetMode.Basic, null, "", null),
            SheetPreview("Failed", SheetState.Failed(retryable = true), SheetMode.Basic, null, "", null),
        )
}

@DemoPreviews
@Composable
internal fun ModelSheetPreview(
    @PreviewParameter(SheetStates::class) p: SheetPreview,
) {
    PreviewFrame {
        Column(Modifier.gutterPadding()) {
            SheetFrame {
                ModelSheetContent(
                    row = ModelsPreviewData.deep,
                    state = p.state,
                    mode = p.mode,
                    selected = p.selected,
                    button = p.button,
                    imageReaderBytes = p.reader,
                    onMode = {},
                    onSelect = {},
                    onGet = {},
                    onRetry = {},
                    fit = { ModelsPreviewData.fitOf(it) },
                )
            }
        }
    }
}
