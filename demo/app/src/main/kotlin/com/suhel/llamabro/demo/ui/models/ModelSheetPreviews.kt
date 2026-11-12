package com.suhel.llamabro.demo.ui.models

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.suhel.llamabro.demo.AppContainer
import com.suhel.llamabro.demo.designsystem.components.CardTint
import com.suhel.llamabro.demo.designsystem.components.DemoSheet
import com.suhel.llamabro.demo.designsystem.components.FitText
import com.suhel.llamabro.demo.designsystem.components.ModelName
import com.suhel.llamabro.demo.designsystem.components.PrimaryButton
import com.suhel.llamabro.demo.designsystem.components.QuantHeader
import com.suhel.llamabro.demo.designsystem.components.QuantRow
import com.suhel.llamabro.demo.designsystem.components.QuietButton
import com.suhel.llamabro.demo.designsystem.components.SectionLabel
import com.suhel.llamabro.demo.designsystem.components.SegmentedSwitch
import com.suhel.llamabro.demo.designsystem.components.SheetFrame
import com.suhel.llamabro.demo.designsystem.components.gutterPadding
import com.suhel.llamabro.demo.designsystem.testing.CountRecompositions
import com.suhel.llamabro.demo.designsystem.theme.DemoSpacing
import com.suhel.llamabro.demo.designsystem.theme.DemoType
import com.suhel.llamabro.demo.designsystem.theme.LocalDemoColors
import com.suhel.llamabro.demo.models.Format
import com.suhel.llamabro.demo.models.RepoFile
import com.suhel.llamabro.demo.models.SizeTier
import com.suhel.llamabro.demo.ui.preview.DemoPreviews
import com.suhel.llamabro.demo.ui.preview.PreviewFrame
import com.suhel.llamabro.hub.HfModel

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
