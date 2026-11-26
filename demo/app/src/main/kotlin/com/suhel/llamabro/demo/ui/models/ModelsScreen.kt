package com.suhel.llamabro.demo.ui.models

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import com.suhel.llamabro.demo.data.settings.FiltersChange
import com.suhel.llamabro.demo.data.settings.SearchFilters
import com.suhel.llamabro.demo.data.settings.SizeFilter
import com.suhel.llamabro.demo.designsystem.components.DemoIcons
import com.suhel.llamabro.demo.designsystem.components.FadeThrough
import com.suhel.llamabro.demo.designsystem.components.Gap
import com.suhel.llamabro.demo.designsystem.components.GapAbove
import com.suhel.llamabro.demo.designsystem.components.IconAction
import com.suhel.llamabro.demo.designsystem.components.LazyStack
import com.suhel.llamabro.demo.designsystem.components.ListHomeScreen
import com.suhel.llamabro.demo.designsystem.components.Notice
import com.suhel.llamabro.demo.designsystem.components.SearchField
import com.suhel.llamabro.demo.designsystem.components.SectionLabel
import com.suhel.llamabro.demo.designsystem.components.SectionNote
import com.suhel.llamabro.demo.designsystem.components.StarterChips
import com.suhel.llamabro.demo.designsystem.components.SwipeToRemove
import com.suhel.llamabro.demo.designsystem.components.TopBar
import com.suhel.llamabro.demo.designsystem.components.TopBarTitle
import com.suhel.llamabro.demo.designsystem.components.Unfold
import com.suhel.llamabro.demo.designsystem.components.listItemMotion
import com.suhel.llamabro.demo.designsystem.theme.DemoShapes
import com.suhel.llamabro.demo.designsystem.theme.DemoSize
import com.suhel.llamabro.demo.models.Format
import com.suhel.llamabro.demo.models.LibraryModel
import com.suhel.llamabro.demo.ui.Screens
import com.suhel.llamabro.hub.HfModel
import kotlinx.coroutines.flow.drop

/** The fresh install's one line over the field (docs/design/models-page.md §2), verbatim. */
private const val INTRO = "intro"

internal const val MODELS_INTRO = "Pick a model to talk to. It runs on this phone, private and offline."
internal const val MODELS_SEARCH = "models_search"
internal const val NO_MATCHES = "No models match. Try a shorter name."
internal const val SEARCH_FAILED = "Couldn't search Hugging Face just now."
internal const val OFFLINE_LINE = "You're offline, so Hugging Face can't be searched."
internal const val MORE_FAILED = "Couldn't load more."
internal const val NOTHING_FITS = "No model is small enough for this phone."
internal const val MODELS_FILTERS = "models_filters"

/** What a screen reader hears on the filter action while its dot shows: some filter isn't at its default. */
internal const val FILTERS_CHANGED = "Changed"

/**
 * The Models page: the stateful end. It owns the view model and the open row's card; each row asks the view model for
 * its own fit, size and state, so a late estimate or a progress tick recomposes that row alone. [onBack] is null when
 * nothing is under the page (a fresh install starts here); [onChatWith] opens a new chat on a model on the phone (the
 * card's "Start a chat"); [onOpenChat] opens the chat the first Get on a fresh install made.
 */
@Composable
fun ModelsRoute(
    screens: Screens,
    initialQuery: String,
    onBack: (() -> Unit)?,
    onChatWith: (HfModel) -> Unit,
    onOpenChat: (Long) -> Unit,
    /** Whether the intro shows, from the settings the shell has read; null when not known. */
    initialIntro: Boolean? = null,
) {
    val viewModel =
        viewModel(key = "models") { screens.modelsViewModel(initialQuery, initialIntro, createSavedStateHandle()) }
    ModelsHost(viewModel, onBack, onOpenChat) { row ->
        ModelCardStack(
            screens,
            row,
            onStarted = viewModel::started,
            onDismiss = viewModel::dismiss,
            onChatWith = onChatWith,
            onExactFit = viewModel::exactFit,
        )
    }
}

/** The page over a view model: [card] draws the open row's card; each row reads its own flows. */
@Composable
internal fun ModelsHost(
    viewModel: ModelsViewModel,
    onBack: (() -> Unit)?,
    onOpenChat: (Long) -> Unit = {},
    card: @Composable (ModelEntry) -> Unit = {},
) {
    val browse by viewModel.browse.collectAsStateWithLifecycle()
    val intro by viewModel.intro.collectAsStateWithLifecycle()
    val search by viewModel.search.collectAsStateWithLifecycle()
    // Hugging Face's rows as the Pager holds them: drawn lazily in the list, so nearing the end asks for the next page
    val hubRows = viewModel.hubPages.collectAsLazyPagingItems()
    val filters by viewModel.filters.collectAsStateWithLifecycle()
    val nothingFits by viewModel.nothingFits.collectAsStateWithLifecycle()
    // the stored count is read, not observed: the load state that makes it matter recomposes this
    val hub =
        hubAnswer(
            search.query,
            hubRows.itemCount,
            hubRows.loadState,
            search.local.size,
            nothingFits,
            viewModel.refreshStored(hubRows.loadState),
        )
    val searching by viewModel.searching.collectAsStateWithLifecycle()
    val fetching by viewModel.fetching.collectAsStateWithLifecycle()
    val pendingRemove by viewModel.pendingRemove.collectAsStateWithLifecycle()
    val pendingStopServing by viewModel.pendingStopServing.collectAsStateWithLifecycle()
    val cancelQuestion by viewModel.downloads.question.collectAsStateWithLifecycle()
    val problem by viewModel.problem.collectAsStateWithLifecycle()
    val opened by viewModel.opened.collectAsStateWithLifecycle()
    val openChat by rememberUpdatedState(onOpenChat)
    LaunchedEffect(viewModel) { viewModel.chatOpened.collect { openChat(it) } }

    val actions =
        remember(viewModel) {
            ModelsActions(
                onQuery = viewModel::setQuery,
                onFocus = viewModel::setFocused,
                onSubmit = viewModel::submit,
                onRecent = viewModel::pickRecent,
                onLeaveSearch = viewModel::leaveSearch,
                onRetry = viewModel::retry,
                onRemove = { entry -> entry.libraryModel()?.let(viewModel::askRemove) },
                onConfirmRemove = viewModel::confirmRemove,
                onDismissRemove = viewModel::dismissRemove,
                onConfirmStopServing = viewModel::confirmStopServing,
                onDismissStopServing = viewModel::dismissStopServing,
                onDismissProblem = viewModel::dismissProblem,
                onConfirmCancel = viewModel.downloads::confirm,
                onKeepDownload = viewModel.downloads::keep,
                onFilters = viewModel::setFilters,
            )
        }
    ModelsScreen(
        ModelsPage(
            browse,
            search.copy(hub = hub),
            searching,
            fetching,
            problem,
            pendingRemove,
            cancelQuestion,
            intro == true,
            filters,
            pendingStopServing,
        ),
        actions,
        onBack,
        hubRows = hubRows,
    ) { entry, fromHub, modifier -> PageRow(viewModel, entry, fromHub, modifier) }
    opened?.let { card(it) }
}

/**
 * One row over the view model: its fit, size and state on flows of their own, made in the row's own scope, so they
 * end when the row leaves the list. Each is kept for what it is figured from: the fit per file, the size per file or
 * repo, the state per repo.
 */
@Composable
private fun PageRow(viewModel: ModelsViewModel, entry: ModelEntry, fromHub: Boolean, modifier: Modifier) {
    val scope = rememberCoroutineScope()
    val fit by remember(ModelEntry.listKey(entry)) { viewModel.fit(entry, scope) }.collectAsStateWithLifecycle()
    val sizeKey = entry.key?.toString() ?: entry.repo.lowercase()
    val size by remember(sizeKey) { viewModel.size(entry, scope) }.collectAsStateWithLifecycle()
    // read whatever the row is, so the composition is one across a landing (page 11); a model on the phone says only
    // Default, and what its repo downloads is another row's (§3)
    val repoState by remember(entry.repo.lowercase()) { viewModel.state(entry, scope) }.collectAsStateWithLifecycle()
    val state = repoState.takeIf { !entry.onPhone }
    EntryRow(
        entry,
        fit,
        size,
        state,
        factOf(entry, fromHub),
        onClick = {
            // opening a Hub result keeps the query among the recent searches
            if (fromHub) viewModel.submit()
            viewModel.open(entry)
        },
        onToggle = { viewModel.downloads.toggle(entry) },
        onCancel = { viewModel.downloads.cancel(entry) },
        modifier = modifier,
    )
}

/** What the page draws, read from the view model's flows. */
@Immutable
data class ModelsPage(
    val browse: Browse?,
    val search: SearchView,
    val searching: Boolean,
    val fetching: Boolean,
    /** A remove or a cancel that didn't happen, said in one line the person can close. */
    val problem: String? = null,
    val pendingRemove: LibraryModel? = null,
    val cancelQuestion: CancelQuestion? = null,
    /** The fresh install's intro, known from the settings before the list is. */
    val intro: Boolean = browse?.intro == true,
    /** The search's filters (models-search design §5): the field's dot and the Hub label's line name changed ones. */
    val filters: SearchFilters = SearchFilters(),
    /** The served model a swipe asked to remove: serving must stop first (docs/design/server.md §3). */
    val pendingStopServing: LibraryModel? = null,
)

/** What the page's controls do. */
@Immutable
class ModelsActions(
    val onQuery: (String) -> Unit = {},
    val onFocus: (Boolean) -> Unit = {},
    val onSubmit: () -> Unit = {},
    val onRecent: (String) -> Unit = {},
    val onLeaveSearch: () -> Unit = {},
    val onRetry: () -> Unit = {},
    val onRemove: (ModelEntry) -> Unit = {},
    val onConfirmRemove: () -> Unit = {},
    val onDismissRemove: () -> Unit = {},
    val onConfirmStopServing: () -> Unit = {},
    val onDismissStopServing: () -> Unit = {},
    val onDismissProblem: () -> Unit = {},
    val onConfirmCancel: () -> Unit = {},
    val onKeepDownload: () -> Unit = {},
    /** The search's filters changed, from the sheet, the Hub label's Reset or a notice's "Any size"; saved. */
    val onFilters: (FiltersChange) -> Unit = {},
)

/** Draws one model's row; `fromHub` for a Hugging Face result, whose opening keeps the query. */
typealias RowSlot = @Composable (entry: ModelEntry, fromHub: Boolean, modifier: Modifier) -> Unit

/**
 * The page, stateless (docs/design/models-page.md §2): the top bar, the intro on a fresh install, the field, then one
 * list across both modes ([modelsItems]): browse's groups, or search's recent searches, the same groups narrowed to the
 * query and Hugging Face's rows. One list with stable keys, so a row in both modes stays put as the mode changes and
 * only what enters or leaves animates; rows move between groups as one item,
 * keyed by their file ([ModelEntry.listKey]). Back in search mode returns to browse.
 */
@Composable
fun ModelsScreen(
    page: ModelsPage,
    actions: ModelsActions,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
    /** Hugging Face's rows, a page at a time; the search's answer says how many and what to say beside them. */
    hubRows: LazyPagingItems<ModelEntry> = rememberHubRows(emptyList()),
    row: RowSlot,
) {
    val focus = LocalFocusManager.current
    val leaveSearch = {
        focus.clearFocus()
        actions.onLeaveSearch()
    }
    BackHandler(enabled = page.searching, onBack = leaveSearch)
    val back = if (page.searching) leaveSearch else onBack
    var explaining by rememberSaveable { mutableStateOf(false) }
    var filtering by rememberSaveable { mutableStateOf(false) }
    ListHomeScreen(
        topBar = {
            TopBar(
                title = { TopBarTitle("Models", light = true) },
                // the Back slot is held whether or not there is a Back, so focusing the field on a fresh install fades
                // the arrow in without moving the title
                start = { BackSlot(back) },
            )
        },
        // the field floats with the bar and the list scrolls on under both (D148)
        header = {
            SearchField(
                page.search.query,
                actions.onQuery,
                // the field filters your own models first; where the extra rows come from, their label says (F2)
                "Search models",
                Modifier
                    .fillMaxWidth()
                    .testTag(MODELS_SEARCH)
                    .onFocusChanged { actions.onFocus(it.hasFocus) },
                onSearch = actions.onSubmit,
                loading = page.fetching,
                // the search's filters, one tap away; a dot while any isn't at its default (models-search design §5)
                trailing = {
                    IconAction(
                        DemoIcons.Filter,
                        "Search filters",
                        { filtering = true },
                        Modifier.testTag(MODELS_FILTERS),
                        muted = true,
                        mark = FILTERS_CHANGED.takeIf { !page.filters.isDefault },
                    )
                },
            )
            // a remove or a cancel that didn't happen: one plain line under the field, its failure word in danger,
            // closed by its own action, never a row's card in the list
            Unfold(page.problem) { text ->
                Notice(
                    text,
                    Modifier.testTag("models_problem"),
                    action = "OK",
                    onAction = actions.onDismissProblem,
                    failure = true,
                )
            }
        },
        modifier = modifier.testTag("models"),
        gutter = true,
    ) { bars ->
        // the intro is browse's (§2): the list's first item, so it scrolls away with the rows; it goes as search mode
        // starts and comes back with Back (page 13)
        val intro = page.intro && !page.searching
        ModelsList(modelsItems(page), hubRows, page.searching, actions, row, bars, intro) { explaining = true }
    }
    if (filtering) FilterSheet(page.filters, actions.onFilters, onDismiss = { filtering = false })
    page.pendingRemove?.let { RemoveDialog(it, actions.onConfirmRemove, actions.onDismissRemove) }
    page.pendingStopServing?.let {
        StopServingToRemoveDialog(it, actions.onConfirmStopServing, actions.onDismissStopServing)
    }
    page.cancelQuestion?.let { CancelDialog(it, actions.onConfirmCancel, actions.onKeepDownload) }
    if (explaining) WhatsAModelDialog(onDismiss = { explaining = false })
}

/** The top bar's Back, in a slot held whether or not there is one; the arrow fades in and out in place. */
@Composable
private fun BackSlot(back: (() -> Unit)?) {
    val go by rememberUpdatedState(back)
    FadeThrough(back != null, Modifier.size(DemoSize.target)) { shown ->
        if (shown) IconAction(DemoIcons.Back, "Back", onClick = { go?.invoke() })
    }
}

/** One item of the page's list, keyed so it keeps its place and motion across both modes. */
@Immutable
internal sealed interface ModelsItem {
    val key: String

    /** An item drawn as one lazy item of its own: every kind but [HubRows], which stands for many. */
    sealed interface Single : ModelsItem

    data class Recent(val queries: List<String>) : Single {
        override val key get() = "recent"
    }

    data class Label(val text: String, val note: String? = null) : Single {
        override val key get() = "label:$text"
    }

    data class Row(val entry: ModelEntry, val fromHub: Boolean = false) : Single {
        override val key get() = (if (fromHub) "hub:" else "") + ModelEntry.listKey(entry)
    }

    /** Where Hugging Face's paged rows go, each drawn lazily as the list nears it (models-search design §1). */
    data object HubRows : ModelsItem {
        override val key get() = "hub_rows"
    }

    /** One plain line where content would be (§5, "Notices"), with its [action]'s words when it has one. */
    data class Line(
        override val key: String,
        val text: String,
        val action: String? = null,
        val does: LineAction = LineAction.None,
    ) : Single

    /** The filters that aren't at their defaults, named under the Hub's label, with Reset (models-search design §5). */
    data class Filters(val text: String) : Single {
        override val key get() = HUB_FILTERS
    }
}

/** What a [ModelsItem.Line]'s action does. */
internal enum class LineAction {
    None,

    /** Asks the Hub again for the search. */
    Retry,

    /** Retries the next page that failed under the rows. */
    More,

    /** Drops "Fits this phone" for any size, where nothing fits this phone. */
    AnySize,
}

/**
 * The page's list in order, a pure function of [page]: in browse the downloads, the models on the phone with what they
 * take, and the suggestions; in search the recent searches before typing, the same three groups narrowed to the query
 * under the same labels, and Hugging Face's part. A model listed twice by overlapping data
 * keeps its first place, so the list never has one key twice (A8).
 */
internal fun modelsItems(page: ModelsPage): List<ModelsItem> {
    val items = mutableListOf<ModelsItem>()
    val browse = page.browse
    if (page.searching) {
        val search = page.search
        if (search.query.isBlank() && search.recent.isNotEmpty()) items += ModelsItem.Recent(search.recent)
        items.group(DOWNLOADING, null, search.downloading)
        items.group(ON_THIS_PHONE, browse?.storage?.let(::storageNote), search.onPhone)
        items.group(SUGGESTED, null, search.suggested)
        items.hubPart(search.hub, page.filters)
    } else if (browse != null) {
        if (browse.downloading.isEmpty() && browse.onPhone.isEmpty() && !page.intro) {
            items += ModelsItem.Line("empty", "No models on this phone yet.")
        }
        items.group(DOWNLOADING, null, browse.downloading)
        items.group(ON_THIS_PHONE, browse.storage?.let(::storageNote), browse.onPhone)
        val short = browse.shortOnMemory
        if (browse.suggested.isEmpty() && short != null) {
            items += ModelsItem.Label(SUGGESTED)
            items += ModelsItem.Line("short", shortOnMemoryLine(short))
            // the model it names, as a row to open, under the line
            items += ModelsItem.Row(short)
        } else {
            items.group(SUGGESTED, null, browse.suggested)
        }
    }
    return items.distinctBy { it.key }
}

private fun MutableList<ModelsItem>.group(label: String, note: String?, entries: List<ModelEntry>) {
    if (entries.isEmpty()) return
    add(ModelsItem.Label(label, note))
    entries.forEach { add(ModelsItem.Row(it)) }
}

/**
 * Hugging Face's part under its label: its rows, or one line saying why there are none, with Try again where it helps.
 * Rows the local part already lists are left out (§7); when that leaves none, nothing is said, label and all, unless
 * a filter isn't at its default: then the label stays with its line naming the filters, so Reset is always at hand.
 */
private fun MutableList<ModelsItem>.hubPart(answer: HubAnswer, filters: SearchFilters) {
    val rows =
        when (answer) {
            is HubAnswer.Loading -> answer.rows
            is HubAnswer.Rows -> answer.rows
            else -> 0
        }
    val line =
        when (answer) {
            HubAnswer.None -> {
                ModelsItem.Line(HUB_NOTICE, NO_MATCHES)
            }

            is HubAnswer.Failed -> {
                ModelsItem.Line(HUB_NOTICE, SEARCH_FAILED, "Try again".takeIf { answer.retryable }, LineAction.Retry)
            }

            HubAnswer.Offline -> {
                ModelsItem.Line(HUB_NOTICE, OFFLINE_LINE, "Try again", LineAction.Retry)
            }

            // the phone's cap is under any model (F176, D128): "No models match" would blame the name; this says why
            HubAnswer.NothingFits -> {
                ModelsItem.Line(HUB_NOTICE, NOTHING_FITS, "Any size", LineAction.AnySize)
            }

            // a later page failed (offline or the Hub refused while scrolling): the rows stay, the line asks again
            is HubAnswer.Rows if answer.moreFailed -> {
                ModelsItem.Line(HUB_MORE, MORE_FAILED, "Try again", LineAction.More)
            }

            else -> {
                null
            }
        }
    val named = filtersLine(filters).takeIf { answer != HubAnswer.Idle }
    if (rows == 0 && line == null && named == null) return
    add(ModelsItem.Label(FROM_HUB))
    named?.let { add(ModelsItem.Filters(it)) }
    if (rows > 0) add(ModelsItem.HubRows)
    line?.let(::add)
}

/**
 * The page's one list: every item enters, leaves and moves with [listItemMotion]. Hugging Face's rows are drawn where
 * [ModelsItem.HubRows] stands, one lazy item each, keyed by the model's group (unique in a search's stored rows).
 */
@Composable
private fun ModelsList(
    items: List<ModelsItem>,
    hubRows: LazyPagingItems<ModelEntry>,
    searching: Boolean,
    actions: ModelsActions,
    row: RowSlot,
    bars: PaddingValues,
    intro: Boolean,
    onWhatsAModel: () -> Unit,
) {
    val state = rememberLazyListState()
    // a mode change starts the list at its top: the lazy list keeps its first visible item by key, so leaving search
    // came back scrolled by the recent searches' height, the On this phone label under the field. The change is read
    // from the mode itself (its first value, as after a rotation, moves nothing), and asked before the next layout
    // pass, so the frame that lays the new items out lays them from the top
    val mode by rememberUpdatedState(searching)
    LaunchedEffect(state) { snapshotFlow { mode }.drop(1).collect { state.requestScrollToItem(0) } }
    LazyStack(Gap.Compact, Modifier.fillMaxSize().testTag("model_list"), state = state, padding = bars) {
        if (intro) {
            item(key = INTRO, contentType = INTRO) {
                Notice(
                    MODELS_INTRO,
                    Modifier.listItemMotion(this),
                    action = "What's a model?",
                    onAction = onWhatsAModel,
                )
            }
        }
        for (entry in items) {
            when (entry) {
                ModelsItem.HubRows -> {
                    items(
                        hubRows.itemCount,
                        key = hubRows.itemKey { "hub:${it.groupKey}" },
                        contentType = { ModelsItem.Row::class },
                    ) { index ->
                        // placeholders are off, so a loaded index always has its row
                        hubRows[index]?.let { RowItem(it, actions, row, fromHub = true) }
                    }
                }

                is ModelsItem.Single -> {
                    item(key = entry.key, contentType = entry::class) {
                        ListItem(entry, actions, row, hubRows::retry)
                    }
                }
            }
        }
    }
}

/** One item of the list other than Hugging Face's paged rows. */
@Composable
private fun LazyItemScope.ListItem(item: ModelsItem.Single, actions: ModelsActions, row: RowSlot, onMore: () -> Unit) {
    when (item) {
        is ModelsItem.Recent -> {
            StarterChips(item.queries, actions.onRecent, Modifier.listItemMotion(this), centered = false)
        }

        is ModelsItem.Label -> {
            Column(Modifier.listItemMotion(this, label = true)) {
                GapAbove(Gap.Item)
                SectionLabel(item.text, note = item.note, quiet = true)
            }
        }

        is ModelsItem.Line -> {
            Notice(
                item.text,
                Modifier.listItemMotion(this).then(
                    if (item.key == HUB_NOTICE || item.key == HUB_MORE) Modifier.testTag(item.key) else Modifier,
                ),
                action = item.action,
                onAction =
                    when (item.does) {
                        LineAction.Retry -> actions.onRetry
                        LineAction.More -> onMore
                        LineAction.AnySize -> ({ actions.onFilters { it.copy(size = SizeFilter.Any) } })
                        LineAction.None -> ({})
                    },
            )
        }

        is ModelsItem.Filters -> {
            SectionNote(
                item.text,
                Modifier.listItemMotion(this).testTag(HUB_FILTERS),
                action = "Reset",
                onAction = { actions.onFilters { SearchFilters() } },
            )
        }

        is ModelsItem.Row -> {
            RowItem(item.entry, actions, row, item.fromHub)
        }
    }
}

internal const val DOWNLOADING = "Downloading"
internal const val ON_THIS_PHONE = "On this phone"
internal const val SUGGESTED = "Suggested for this phone"
internal const val FROM_HUB = "From Hugging Face"
private const val HUB_NOTICE = "hub_notice"
internal const val HUB_MORE = "hub_more"
internal const val HUB_FILTERS = "hub_filters"

/** What the models take and what is left, the On this phone section's note: "6.1 GB · 120 GB free". */
internal fun storageNote(storage: StorageInfo): String =
    "${Format.storageGb(storage.usedBytes)} · ${Format.storageGb(storage.freeBytes)} free"

/** On a phone where no catalog model runs well: one line naming the smallest, never a kneeling pick (§2). */
internal fun shortOnMemoryLine(smallest: ModelEntry): String =
    "This phone is short on memory for the suggested models. The smallest is ${smallest.name}" +
        (smallest.size?.let { ", ${it.text()}." } ?: ".")

@Composable
private fun LazyItemScope.RowItem(entry: ModelEntry, actions: ModelsActions, row: RowSlot, fromHub: Boolean) {
    // one call site for every row: a suggestion, its download and the model it lands as are the same composition, so
    // Get moves the same row, and a landing folds the footer away and fades "Default" in rather than redrawing it
    // only a model on the phone swipes to remove
    val tag =
        when {
            entry.onPhone -> Modifier.testTag("library_model")
            entry.key != null -> Modifier.testTag("download_row")
            else -> Modifier
        }
    SwipeToRemove(
        "Remove",
        { actions.onRemove(entry) },
        Modifier.listItemMotion(this).then(tag),
        enabled = entry.onPhone,
        shape = DemoShapes.row,
    ) { row(entry, fromHub && !entry.onPhone, Modifier) }
}
