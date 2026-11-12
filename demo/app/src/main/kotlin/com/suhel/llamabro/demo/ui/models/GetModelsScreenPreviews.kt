package com.suhel.llamabro.demo.ui.models

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
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
import com.suhel.llamabro.demo.designsystem.components.DemoIcons
import com.suhel.llamabro.demo.designsystem.components.EmptyLine
import com.suhel.llamabro.demo.designsystem.components.IconAction
import com.suhel.llamabro.demo.designsystem.components.QuietButton
import com.suhel.llamabro.demo.designsystem.components.SearchField
import com.suhel.llamabro.demo.designsystem.components.SectionLabel
import com.suhel.llamabro.demo.designsystem.components.TopBar
import com.suhel.llamabro.demo.designsystem.components.TopBarTitle
import com.suhel.llamabro.demo.designsystem.theme.DemoType
import com.suhel.llamabro.demo.designsystem.theme.LocalDemoColors
import com.suhel.llamabro.demo.ui.preview.DemoScreenPreviews
import com.suhel.llamabro.demo.ui.preview.PreviewFrame

// Previews of GetModelsScreen.kt: each state of the screen or part, in light and dark.

private val previewRows =
    listOf(ModelsPreviewData.swift, ModelsPreviewData.writer, ModelsPreviewData.deep, ModelsPreviewData.nine)

private val previewFits =
    mapOf(
        ModelsPreviewData.swift.groupKey to ModelsPreviewData.great,
        ModelsPreviewData.writer.groupKey to ModelsPreviewData.well,
        ModelsPreviewData.deep.groupKey to ModelsPreviewData.well,
        ModelsPreviewData.nine.groupKey to ModelsPreviewData.slow,
    )

internal data class GetModelsPreview(val name: String, val query: String, val state: GetModelsState) {
    override fun toString() = name
}

internal class GetModelsStates : PreviewParameterProvider<GetModelsPreview> {
    override val values =
        sequenceOf(
            GetModelsPreview("Suggestions", "", GetModelsState.Suggestions(previewRows)),
            GetModelsPreview(
                "Offline",
                "qwen",
                GetModelsState.Suggestions(previewRows, GetModelsViewModel.OFFLINE_LINE),
            ),
            GetModelsPreview(
                "Results",
                "phi",
                GetModelsState.Results(listOf(ModelsPreviewData.result, ModelsPreviewData.swift)),
            ),
            GetModelsPreview("Searching", "phi", GetModelsState.Searching),
            GetModelsPreview("No results", "zzzz", GetModelsState.NoResults),
            GetModelsPreview("Error, retryable", "phi", GetModelsState.Failed(retryable = true)),
        )
}

@DemoScreenPreviews
@Composable
internal fun GetModelsScreenPreview(
    @PreviewParameter(GetModelsStates::class) state: GetModelsPreview,
) {
    PreviewFrame {
        GetModelsScreen(state.query, state.state, onQueryChange = {
        }, onRetry = {}, onBack = {}, modifier = Modifier.height(780.dp)) { row ->
            ModelRowItem(row, previewFits[row.groupKey], null, onPhone = false, onClick = {})
        }
    }
}
