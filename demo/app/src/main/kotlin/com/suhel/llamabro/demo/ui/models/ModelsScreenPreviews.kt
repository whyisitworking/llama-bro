package com.suhel.llamabro.demo.ui.models

import android.content.res.Configuration
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import androidx.compose.ui.unit.dp
import com.suhel.llamabro.demo.data.settings.SearchFilters
import com.suhel.llamabro.demo.data.settings.SearchOrder
import com.suhel.llamabro.demo.data.settings.SizeFilter
import com.suhel.llamabro.demo.designsystem.components.DownloadStage
import com.suhel.llamabro.demo.designsystem.components.DownloadView
import com.suhel.llamabro.demo.designsystem.components.SheetFrame
import com.suhel.llamabro.demo.designsystem.components.gutterPadding
import com.suhel.llamabro.demo.models.Catalog
import com.suhel.llamabro.demo.models.DownloadRecord
import com.suhel.llamabro.demo.models.FitView
import com.suhel.llamabro.demo.models.Format
import com.suhel.llamabro.demo.models.LibraryModel
import com.suhel.llamabro.demo.ui.preview.DemoPreviews
import com.suhel.llamabro.demo.ui.preview.DemoScreenPreviews
import com.suhel.llamabro.demo.ui.preview.PreviewFrame
import com.suhel.llamabro.hub.HfModel

// Previews of ModelsScreen.kt, ModelsItems.kt and FilterSheet.kt: each state of the page, its row and the filter sheet,
// in light and dark, and at twice the font.

private val deepKey = HfModel.parse("unsloth/Qwen3.5-4B-GGUF:Q4_K_M")
private val swiftKey = HfModel.parse("unsloth/Qwen3.5-0.8B-GGUF:Q4_K_M")
private val gemmaKey = HfModel.parse("unsloth/gemma-3-4b-it-qat-GGUF:Q4_K_M")

private val onPhone =
    listOf(
        ModelEntry.of(LibraryModel(gemmaKey, "Gemma 3 4B", 3_300_000_000, 0, isDefault = true)),
        ModelEntry.of(LibraryModel(deepKey, "Qwen3.5 4B", 3_400_000_000, 0, isDefault = false)),
    )
private val downloading =
    ModelEntry.downloading(
        swiftKey,
        DownloadRecord(swiftKey, "Qwen3.5 0.8B", "Alibaba Qwen", 700_000_000),
    )

// suggestions are catalog models not on the phone: Gemma 3 4B is on it, so it isn't one (a model in two sections
// would be one key twice)
private val llama = ModelEntry.of(Catalog.suggested[3])
private val suggested = listOf(llama, ModelsPreviewData.nine)
private val results = listOf(ModelsPreviewData.coder, ModelsPreviewData.result, ModelsPreviewData.embedder)

private val previewFits =
    mapOf(
        llama.repo to ModelsPreviewData.well,
        ModelsPreviewData.nine.repo to ModelsPreviewData.slow,
        ModelsPreviewData.coder.repo to ModelsPreviewData.well,
        ModelsPreviewData.result.repo to ModelsPreviewData.great,
        gemmaKey.repo to ModelsPreviewData.well,
        deepKey.repo to ModelsPreviewData.well,
        swiftKey.repo to ModelsPreviewData.great,
    )

private val downloadingState =
    RowState(
        RowBadge.Downloading(0.42f),
        // the text and the ring from one value, as DownloadViews makes them
        DownloadView(
            DownloadStage.Downloading,
            "Q4_K_M",
            0.42f,
            "${Format.arrived(294_000_000, 700_000_000)} · 1 min left",
        ),
    )

private val storage = StorageInfo(6_700_000_000, 120_000_000_000)

private fun browse(
    intro: Boolean = false,
    downloads: List<ModelEntry> = listOf(downloading),
    phone: List<ModelEntry> = onPhone,
    picks: List<ModelEntry> = suggested,
    short: ModelEntry? = null,
) = Browse(intro, downloads, phone, storage.takeIf { phone.isNotEmpty() }, picks, short)

private val idle = SearchView("", emptyList(), emptyList(), HubAnswer.Idle)

internal data class ModelsPreview(
    val name: String,
    val page: ModelsPage,
    val back: Boolean = true,
    /** Hugging Face's rows the page's answer counts. */
    val hub: List<ModelEntry> = emptyList(),
) {
    override fun toString() = name
}

internal class ModelsStates : PreviewParameterProvider<ModelsPreview> {
    override val values =
        sequenceOf(
            ModelsPreview("Browse", ModelsPage(browse(), idle, searching = false, fetching = false)),
            ModelsPreview(
                "Fresh install",
                ModelsPage(browse(intro = true, downloads = emptyList(), phone = emptyList()), idle, false, false),
                back = false,
            ),
            ModelsPreview(
                "Short on memory",
                ModelsPage(browse(picks = emptyList(), short = ModelsPreviewData.swift), idle, false, false),
            ),
            ModelsPreview(
                "A problem and asking to remove",
                ModelsPage(
                    browse(),
                    idle,
                    searching = false,
                    fetching = false,
                    problem = "Couldn't cancel the download of Qwen3.5 0.8B. Try again.",
                    pendingRemove = LibraryModel(deepKey, "Qwen3.5 4B", 3_400_000_000, 0, isDefault = false),
                ),
            ),
            ModelsPreview("Not read yet", ModelsPage(null, idle, searching = false, fetching = false)),
            ModelsPreview(
                "Search, before typing",
                ModelsPage(
                    browse(),
                    SearchView("", listOf("qwen coder", "gemma", "phi 4"), onPhone + suggested, HubAnswer.Idle),
                    searching = true,
                    fetching = false,
                ),
            ),
            ModelsPreview(
                "Search, fetching",
                ModelsPage(
                    browse(),
                    SearchView("qwen", emptyList(), onPhone.takeLast(1), HubAnswer.Loading(0)),
                    searching = true,
                    fetching = true,
                ),
            ),
            ModelsPreview(
                "Search, results",
                ModelsPage(
                    browse(),
                    SearchView("qwen", emptyList(), onPhone.takeLast(1), HubAnswer.Rows(results.size)),
                    searching = true,
                    fetching = false,
                ),
                hub = results,
            ),
            ModelsPreview(
                "Search, offline",
                ModelsPage(browse(), SearchView("qwen", emptyList(), suggested, HubAnswer.Offline), true, false),
            ),
            ModelsPreview(
                "Search, no results",
                ModelsPage(browse(), SearchView("zzzz", emptyList(), emptyList(), HubAnswer.None), true, false),
            ),
            ModelsPreview(
                "Search, filtered",
                ModelsPage(
                    browse(),
                    SearchView("qwen", emptyList(), onPhone.takeLast(1), HubAnswer.Rows(results.size)),
                    searching = true,
                    fetching = false,
                    filters = SearchFilters(SearchOrder.Newest, SizeFilter.Any, seesImages = true),
                ),
                hub = results,
            ),
            ModelsPreview(
                "Search, nothing fits this phone",
                ModelsPage(browse(), SearchView("qwen", emptyList(), emptyList(), HubAnswer.NothingFits), true, false),
            ),
            ModelsPreview(
                "Search, failed",
                ModelsPage(browse(), SearchView("phi", emptyList(), emptyList(), HubAnswer.Failed(true)), true, false),
            ),
        )
}

@DemoScreenPreviews
@Composable
internal fun ModelsScreenPreview(
    @PreviewParameter(ModelsStates::class) p: ModelsPreview,
) {
    PreviewFrame {
        ModelsScreen(
            p.page,
            ModelsActions(),
            onBack = if (p.back) ({}) else null,
            Modifier.height(780.dp),
            hubRows = rememberHubRows(p.hub),
        ) { e, hub, m ->
            EntryRow(
                e,
                previewFits[e.repo],
                e.size,
                if (e.key == swiftKey) downloadingState else null,
                factOf(e, hub),
                onClick = {},
                onToggle = {},
                onCancel = {},
                modifier = m,
            )
        }
    }
}

internal data class RowPreview(val name: String, val row: ModelEntry, val fit: FitView?, val state: RowState?) {
    override fun toString() = name
}

internal class EntryRowStates : PreviewParameterProvider<RowPreview> {
    override val values =
        sequenceOf(
            RowPreview("Great, a suggestion", ModelsPreviewData.swift, ModelsPreviewData.great, null),
            RowPreview("Slow", ModelsPreviewData.nine, ModelsPreviewData.slow, null),
            RowPreview("Too big", ModelsPreviewData.nine, ModelsPreviewData.tooBig, null),
            RowPreview("Coding, a result", ModelsPreviewData.coder, ModelsPreviewData.well, null),
            RowPreview("Can't chat", ModelsPreviewData.embedder, null, null),
            RowPreview("Fit not known yet", ModelsPreviewData.result, null, null),
            RowPreview("On the phone, default", onPhone[0], ModelsPreviewData.well, null),
            RowPreview("Downloading", downloading, ModelsPreviewData.great, downloadingState),
            RowPreview(
                "Waiting",
                downloading,
                ModelsPreviewData.great,
                RowState(RowBadge.Queued, DownloadView(DownloadStage.Waiting, "Q4_K_M", 0f, "after Gemma 3 4B")),
            ),
            RowPreview(
                "Stopped",
                downloading,
                ModelsPreviewData.great,
                RowState(
                    RowBadge.Failed("The connection dropped."),
                    DownloadView(DownloadStage.Stopped, "Q4_K_M", 0f, "The connection dropped."),
                ),
            ),
        )
}

@DemoPreviews
@Composable
internal fun EntryRowPreview(
    @PreviewParameter(EntryRowStates::class) p: RowPreview,
) {
    PreviewFrame {
        EntryRow(
            p.row,
            p.fit,
            p.row.size,
            p.state,
            factOf(p.row, fromHub = p.name.endsWith("a result")),
            onClick = {},
            onToggle = {},
            onCancel = {},
            modifier = Modifier.gutterPadding(),
        )
    }
}

internal class FilterStates : PreviewParameterProvider<Pair<String, SearchFilters>> {
    override val values =
        sequenceOf(
            "The defaults" to SearchFilters(),
            "Newest, any size, sees images" to SearchFilters(SearchOrder.Newest, SizeFilter.Any, seesImages = true),
            "A custom range" to SearchFilters(size = SizeFilter.Range(1_000_000_000, 4_000_000_000)),
        )
}

@Composable
private fun FilterSheetInFrame(filters: SearchFilters) {
    PreviewFrame {
        Column(Modifier.gutterPadding()) {
            SheetFrame { FilterSheetContent(filters, onFilters = {}, onDone = {}) }
        }
    }
}

@DemoPreviews
@Composable
internal fun FilterSheetPreview(
    @PreviewParameter(FilterStates::class) p: Pair<String, SearchFilters>,
) = FilterSheetInFrame(p.second)

@Preview(name = "Light, 2x", widthDp = 360, fontScale = 2f)
@Preview(name = "Dark, 2x", widthDp = 360, fontScale = 2f, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
internal fun FilterSheetLargeTextPreview(
    @PreviewParameter(FilterStates::class) p: Pair<String, SearchFilters>,
) = FilterSheetInFrame(p.second)

@Preview(name = "Light, 2x", device = "spec:width=360dp,height=780dp,dpi=420", fontScale = 2f)
@Preview(
    name = "Dark, 2x",
    device = "spec:width=360dp,height=780dp,dpi=420",
    fontScale = 2f,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
internal fun ModelsScreenLargeTextPreview() =
    ModelsScreenPreview(ModelsStates().values.first { it.name == "Search, filtered" })
