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
import com.suhel.llamabro.demo.designsystem.components.Gap
import com.suhel.llamabro.demo.designsystem.components.LazyStack
import com.suhel.llamabro.demo.designsystem.components.ListRow
import com.suhel.llamabro.demo.designsystem.components.ModelName
import com.suhel.llamabro.demo.designsystem.components.PrimaryButton
import com.suhel.llamabro.demo.designsystem.components.QuantHeader
import com.suhel.llamabro.demo.designsystem.components.QuantRow
import com.suhel.llamabro.demo.designsystem.components.QuietButton
import com.suhel.llamabro.demo.designsystem.components.RowCard
import com.suhel.llamabro.demo.designsystem.components.Section
import com.suhel.llamabro.demo.designsystem.components.SegmentedSwitch
import com.suhel.llamabro.demo.designsystem.components.SheetFrame
import com.suhel.llamabro.demo.designsystem.components.Stack
import com.suhel.llamabro.demo.designsystem.components.sheetListMaxHeight
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

/**
 * A model's sheet: the stateful end. Its view model lives in a store that is cleared when the sheet closes, which stops
 * its work, and kept across a rotation; Get asks about notifications, starts the download and closes.
 */
@Composable
fun ModelSheetRoute(
    container: AppContainer,
    row: ModelRow,
    onStarted: (HfModel) -> Unit,
    onDismiss: () -> Unit,
    startTier: SizeTier? = null,
) {
    val viewModel =
        viewModel(viewModelStoreOwner = rememberSheetViewModelOwner("sheet:${row.repo}")) {
            ModelSheetViewModel(
                row,
                container.discovery::files,
                container::deviceFacts,
                container.models,
                startTier = startTier,
            )
        }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val mode by viewModel.mode.collectAsStateWithLifecycle()
    val selected by viewModel.selected.collectAsStateWithLifecycle()
    val button by viewModel.button.collectAsStateWithLifecycle()
    val reader by viewModel.imageReaderBytes.collectAsStateWithLifecycle()
    val get =
        rememberAskNotificationsThen(container.asksForNotifications) {
            viewModel.get()?.let(onStarted)
            onDismiss()
        }
    DemoSheet(onDismissRequest = onDismiss) {
        ModelSheetContent(
            row = row,
            state = state,
            mode = mode,
            selected = selected,
            button = button,
            imageReaderBytes = reader,
            onMode = viewModel::setMode,
            onSelect = viewModel::select,
            onGet = get,
            onRetry = viewModel::retry,
            fit = { file -> viewModel.fit(file).collectAsStateWithLifecycle().value },
        )
    }
}

/**
 * The sheet's contents, without the window. [fit] gives a file's fit; it is read inside each row's own composable, so
 * a late fit recomposes that row alone.
 */
@Composable
fun ColumnScope.ModelSheetContent(
    row: ModelRow,
    state: SheetState,
    mode: SheetMode,
    selected: RepoFile?,
    button: String,
    imageReaderBytes: Long?,
    onMode: (SheetMode) -> Unit,
    onSelect: (RepoFile) -> Unit,
    onGet: () -> Unit,
    onRetry: () -> Unit,
    fit: @Composable (RepoFile) -> FileFit?,
) {
    SheetHeader(row)
    when (state) {
        SheetState.Loading -> {
            SheetNotice("Looking at the sizes…")
        }

        is SheetState.Failed -> {
            SheetNotice("Couldn't read this model's sizes.", if (state.retryable) "Try again" else null, onRetry)
        }

        is SheetState.Ready -> {
            if (state.tiers.isEmpty()) {
                SheetNotice("This model has no file this app can run.")
            } else {
                ModeSwitch(mode, onMode)
                when (mode) {
                    SheetMode.Basic -> BasicTiers(state.tiers, selected, onSelect, fit)
                    SheetMode.Advanced -> AdvancedQuants(state, selected, imageReaderBytes, onSelect, fit)
                }
                PrimaryButton(
                    button,
                    onGet,
                    Modifier.fillMaxWidth().testTag("get_button"),
                    enabled = button.isNotEmpty(),
                )
            }
        }
    }
}

/** The name in serif, the repo and maker under it, and what the model is like. */
@Composable
private fun SheetHeader(row: ModelRow) {
    val byLine = if (row.maker != null) "${row.repo} · by ${row.maker}" else row.repo
    Stack(Gap.Block) {
        ModelName(row.name, realName = byLine)
        Text(row.catalog?.about ?: row.line, color = LocalDemoColors.current.muted, style = DemoType.body)
    }
}

@Composable
private fun ModeSwitch(mode: SheetMode, onMode: (SheetMode) -> Unit) {
    SegmentedSwitch(
        options = listOf("Basic", "Advanced"),
        selected = mode.ordinal,
        onSelect = { onMode(SheetMode.entries[it]) },
        modifier = Modifier.fillMaxWidth().testTag("mode_switch"),
    )
}

/** Three sizes in plain words, no quant names. */
@Composable
private fun BasicTiers(
    tiers: List<Pair<SizeTier, RepoFile>>,
    selected: RepoFile?,
    onSelect: (RepoFile) -> Unit,
    fit: @Composable (RepoFile) -> FileFit?,
) {
    Stack(Gap.Block, Modifier.testTag("basic_tiers")) {
        for ((tier, file) in tiers) TierOption(tier, file, file == selected, { onSelect(file) }, fit)
    }
}

@Composable
private fun TierOption(
    tier: SizeTier,
    file: RepoFile,
    selected: Boolean,
    onClick: () -> Unit,
    fit: @Composable (RepoFile) -> FileFit?,
) {
    val result = fit(file)
    val tint = result?.fit?.label?.tint() ?: CardTint.Plain
    RowCard(Modifier.testTag("tier_${tier.name}"), selected = selected, onClick = onClick) {
        ListRow(
            title = tier.title(),
            subtitle = tier.line(),
            end = {
                Stack(Gap.Inline, horizontalAlignment = Alignment.End) {
                    Text(Format.sizeInGb(file.bytes), color = LocalDemoColors.current.ink, style = DemoType.bodyMedium)
                    result?.let { FitText(it.fit.label.text(), tint) }
                }
            },
        )
    }
}

private fun SizeTier.title() =
    when (this) {
        SizeTier.Smaller -> "Smaller"
        SizeTier.Recommended -> "Recommended"
        SizeTier.Best -> "Best quality"
    }

private fun SizeTier.line() =
    when (this) {
        SizeTier.Smaller -> "A little less sharp"
        SizeTier.Recommended -> "The best balance"
        SizeTier.Best -> "Near-original, slower"
    }

/** Every quant in equal rows with three aligned columns, then the image reader as a matching row. */
@Composable
private fun AdvancedQuants(
    state: SheetState.Ready,
    selected: RepoFile?,
    imageReaderBytes: Long?,
    onSelect: (RepoFile) -> Unit,
    fit: @Composable (RepoFile) -> FileFit?,
) {
    QuantHeader("Quant", "Size", "On this phone")
    // lazy, so only the rows on screen work out their fit
    LazyStack(Gap.Block, Modifier.sheetListMaxHeight().testTag("advanced_quants")) {
        items(state.files, key = { it.path }, contentType = { "quant" }) { file ->
            QuantOption(file, file == selected, file == state.recommended, { onSelect(file) }, fit)
        }
    }
    if (imageReaderBytes != null) ImageReaderRow(imageReaderBytes)
}

@Composable
private fun QuantOption(
    file: RepoFile,
    selected: Boolean,
    recommended: Boolean,
    onClick: () -> Unit,
    fit: @Composable (RepoFile) -> FileFit?,
) {
    CountRecompositions("quant-${file.quant}")
    val result = fit(file)
    val tint = result?.fit?.label?.tint() ?: CardTint.Plain
    QuantRow(
        modifier = Modifier.testTag("quant_row"),
        quant = file.quant,
        size = Format.sizeInGb(file.bytes),
        fit =
            result
                ?.fit
                ?.label
                ?.text()
                .orEmpty(),
        tint = tint,
        selected = selected,
        edgeLabel = if (recommended) "Recommended" else null,
        onClick = onClick,
    )
}

@Composable
private fun ImageReaderRow(bytes: Long) {
    Section("Image reader") {
        QuantRow("mmproj", Format.sizeInGb(bytes), "Included", CardTint.Plain, selected = false, included = true)
    }
}

@Composable
private fun SheetNotice(text: String, action: String? = null, onAction: () -> Unit = {}) {
    Stack(Gap.Item, Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text, color = LocalDemoColors.current.muted, style = DemoType.body)
        if (action != null) QuietButton(action, onAction, bordered = true)
    }
}
