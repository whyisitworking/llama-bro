package com.suhel.llamabro.demo.ui.models

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.suhel.llamabro.demo.AppContainer
import com.suhel.llamabro.demo.designsystem.components.DemoIcons
import com.suhel.llamabro.demo.designsystem.components.EmptyLine
import com.suhel.llamabro.demo.designsystem.components.Gap
import com.suhel.llamabro.demo.designsystem.components.IconAction
import com.suhel.llamabro.demo.designsystem.components.LazyStack
import com.suhel.llamabro.demo.designsystem.components.QuietButton
import com.suhel.llamabro.demo.designsystem.components.Screen
import com.suhel.llamabro.demo.designsystem.components.SearchField
import com.suhel.llamabro.demo.designsystem.components.SectionLabel
import com.suhel.llamabro.demo.designsystem.components.Stack
import com.suhel.llamabro.demo.designsystem.components.TopBar
import com.suhel.llamabro.demo.designsystem.components.TopBarTitle
import com.suhel.llamabro.demo.designsystem.theme.DemoType
import com.suhel.llamabro.demo.designsystem.theme.LocalDemoColors
import com.suhel.llamabro.demo.ui.preview.DemoScreenPreviews
import com.suhel.llamabro.demo.ui.preview.PreviewFrame

/**
 * Get models: the stateful end. It owns the view model and the open row's sheet; each row asks the view model for its
 * own fit and download, so a late estimate or a progress tick recomposes that row alone.
 */
@Composable
fun GetModelsRoute(container: AppContainer, initialQuery: String, onBack: () -> Unit) {
    val viewModel =
        viewModel(key = "get_models") {
            GetModelsViewModel(
                search = container.discovery::search,
                rowFit = { row -> container.discovery.quickFit(row.repo, row.catalog) },
                models = container.models,
                markFirstRunSeen = container.settings::markFirstRunSeen,
                initialQuery = initialQuery,
            )
        }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val opened by viewModel.opened.collectAsStateWithLifecycle()
    val onPhone by viewModel.onPhone.collectAsStateWithLifecycle()

    GetModelsScreen(
        query = query,
        state = state,
        onQueryChange = viewModel::setQuery,
        onRetry = viewModel::retry,
        onBack = onBack,
    ) { row ->
        val fit by viewModel.fit(row).collectAsStateWithLifecycle()
        val download by viewModel.progress(row).collectAsStateWithLifecycle()
        ModelRowItem(row, fit, download, row.repo.lowercase() in onPhone, onClick = { viewModel.open(row) })
    }
    opened?.let { row ->
        ModelSheetRoute(container, row, onStarted = { key ->
            viewModel.started(row, key)
        }, onDismiss = viewModel::dismiss)
    }
}

/**
 * The screen, stateless. [rowItem] draws one model row: the route's draws it from the row's own flows, a preview draws
 * a fixed one.
 */
@Composable
fun GetModelsScreen(
    query: String,
    state: GetModelsState,
    onQueryChange: (String) -> Unit,
    onRetry: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    rowItem: @Composable (ModelRow) -> Unit,
) {
    Screen(
        topBar = {
            TopBar(
                title = { TopBarTitle("Get models") },
                start = { IconAction(DemoIcons.Back, "Back", onClick = onBack) },
            )
        },
        modifier = modifier.testTag("get_models").statusBarsPadding().imePadding(),
        scrolls = false,
    ) {
        SearchField(query, onQueryChange, "Search Hugging Face", Modifier.fillMaxWidth().testTag("models_search"))
        GetModelsBody(state, onRetry, rowItem)
    }
}

/** What is under the search bar, by state: the suggestions, a wait, the results, or why there are none. */
@Composable
private fun ColumnScope.GetModelsBody(
    state: GetModelsState,
    onRetry: () -> Unit,
    rowItem: @Composable (ModelRow) -> Unit,
) {
    when (state) {
        is GetModelsState.Suggestions -> {
            ModelList("Suggested for this phone", state.rows, rowItem, notice = state.offlineLine)
        }

        is GetModelsState.Results -> {
            ModelList(null, state.rows, rowItem)
        }

        GetModelsState.Searching -> {
            Notice("Searching…")
        }

        GetModelsState.NoResults -> {
            Notice("No models match. Try a shorter name.")
        }

        is GetModelsState.Failed -> {
            Notice(
                "Couldn't search Hugging Face just now.",
                action = if (state.retryable) "Try again" else null,
                onAction = onRetry,
            )
        }
    }
}

/** Rows keyed by model, so a result keeps its place and its own state while others change. */
@Composable
private fun ColumnScope.ModelList(
    label: String?,
    rows: List<ModelRow>,
    rowItem: @Composable (ModelRow) -> Unit,
    notice: String? = null,
) {
    // a notice that arrives later is inserted above the item the list is anchored to and would stay out of sight: a
    // list that starts afresh when the notice comes or goes shows it
    key(notice != null) {
        LazyStack(Gap.Block, Modifier.weight(1f).testTag("model_list")) {
            if (notice != null) item(key = "notice") { InlineNotice(notice) }
            if (label != null) item(key = "label") { SectionLabel(label) }
            items(rows, key = { it.groupKey }, contentType = { "model" }) { row -> rowItem(row) }
        }
    }
}

@Composable
private fun InlineNotice(text: String) {
    Text(
        text,
        Modifier.fillMaxWidth().testTag("offline_line"),
        color = LocalDemoColors.current.muted,
        style = DemoType.bodyMedium,
    )
}

/** One plain line in the middle, with a button when trying again can help. */
@Composable
private fun Notice(text: String, action: String? = null, onAction: () -> Unit = {}) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Stack(Gap.Item, horizontalAlignment = Alignment.CenterHorizontally) {
            EmptyLine(text)
            if (action != null) QuietButton(action, onAction, bordered = true)
        }
    }
}
