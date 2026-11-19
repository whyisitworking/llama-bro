package com.suhel.llamabro.demo.ui.models

import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import androidx.compose.ui.unit.dp
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
