package com.suhel.llamabro.demo.ui.models

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.toSize
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.paging.ExperimentalPagingApi
import androidx.paging.LoadType
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingSource
import androidx.paging.PagingState
import androidx.paging.RemoteMediator
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.suhel.llamabro.DeviceKind
import com.suhel.llamabro.demo.designsystem.theme.DemoTheme
import com.suhel.llamabro.demo.designsystem.theme.ThemeMode
import com.suhel.llamabro.demo.models.Catalog
import com.suhel.llamabro.demo.models.DeviceFacts
import com.suhel.llamabro.demo.models.FiltersChange
import com.suhel.llamabro.demo.models.SearchFilters
import com.suhel.llamabro.demo.models.SearchModel
import com.suhel.llamabro.demo.models.SearchOrder
import com.suhel.llamabro.demo.models.SizeFilter
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.ZoneOffset
import kotlin.coroutines.EmptyCoroutineContext

/**
 * The search's filters on the page (models-search design §1, §5): the field's filter action with its dot, the sheet,
 * the "From Hugging Face" label's line naming what is on with Reset, and the Hub's rows appended below without moving
 * anything above. The page is stateless here, except where the settings' round trip is the point.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ModelsFiltersTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val swift = ModelEntry.of(Catalog.suggested[0])
    private val hub = hubEntry("Phi 4 mini")

    private var page by mutableStateOf(
        ModelsPage(
            null,
            SearchView("qwen", emptyList(), listOf(swift), HubAnswer.Rows(1)),
            searching = true,
            fetching = false,
        ),
    )
    private val chosen = mutableListOf<SearchFilters>()

    /** The page over [page]; a filter change lands back in it, as the settings would hand it back. */
    private fun show() {
        val actions =
            ModelsActions(onFilters = { change ->
                page = page.copy(filters = change(page.filters))
                chosen += page.filters
            })
        rule.setContent {
            DemoTheme(ThemeMode.Light) {
                ModelsScreen(page, actions, onBack = null, hubRows = rememberHubRows(listOf(hub))) { e, _, m ->
                    EntryRow(e, null, e.size, null, null, {}, {}, {}, m)
                }
            }
        }
        rule.waitForIdle()
    }

    private fun filterAction() = rule.onNodeWithTag(MODELS_FILTERS)

    private val marked = SemanticsMatcher.keyIsDefined(SemanticsProperties.StateDescription)

    private fun row(name: String): SemanticsNodeInteraction =
        rule.onNode(hasTestTag(MODEL_ROW) and hasText(name, substring = true))

    private fun field(label: String) = rule.onNode(hasSetTextAction() and hasContentDescription(label))

    private fun choice(text: String) = rule.onNode(isSelectable() and hasText(text))

    @Test
    fun theDefaultFiltersAreHiddenNoDotAndNoLine() {
        show()
        filterAction().assertIsDisplayed()
        rule.onNode(hasTestTag(MODELS_FILTERS) and marked).assertDoesNotExist()
        rule.onNodeWithText(FROM_HUB).assertIsDisplayed()
        rule.onNodeWithTag(HUB_FILTERS).assertDoesNotExist()
        rule.onNodeWithText("Reset").assertDoesNotExist()
    }

    @Test
    fun aChangedFilterShowsTheDotAndTheLineAndResetRestoresTheDefaults() {
        page = page.copy(filters = SearchFilters(SearchOrder.Newest, SizeFilter.Any, seesImages = true))
        show()
        rule.onNode(hasTestTag(MODELS_FILTERS) and marked).assertExists()
        rule.onNodeWithTag(HUB_FILTERS).assertIsDisplayed()
        rule.onNodeWithText("Newest · Any size · Sees images").assertIsDisplayed()
        rule.onNode(hasText("Reset") and hasAnyAncestor(hasTestTag(HUB_FILTERS))).performClick()
        rule.waitForIdle()
        assertEquals(SearchFilters(), chosen.single())
        rule.onNode(hasTestTag(MODELS_FILTERS) and marked).assertDoesNotExist()
        rule.onNodeWithTag(HUB_FILTERS).assertDoesNotExist()
    }

    @Test
    fun theSheetChangesTheSortTheSizeAndSeesImagesAndResetsThem() {
        show()
        filterAction().performClick()
        rule.waitForIdle()
        choice("Most downloaded").assertIsSelected()
        choice("Fits this phone").assertIsSelected()
        choice("Newest").performClick()
        choice("Any size").performClick()
        rule.onNodeWithText("Sees images").performClick()
        rule.waitForIdle()
        assertEquals(SearchFilters(SearchOrder.Newest, SizeFilter.Any, seesImages = true), page.filters)
        choice("Newest").assertIsSelected()
        rule.onNode(hasText("Reset") and hasAnyAncestor(hasTestTag(FILTER_SHEET))).performClick()
        rule.waitForIdle()
        assertEquals(SearchFilters(), page.filters)
        choice("Most downloaded").assertIsSelected()
    }

    @Test
    fun aCustomRangeIsInBillionsAndAnInvalidOneKeepsTheLastValidOneAndSaysWhy() {
        show()
        filterAction().performClick()
        rule.waitForIdle()
        choice("Custom").performClick()
        rule.waitForIdle()
        assertEquals(SizeFilter.Range(1_000_000_000, 4_000_000_000), page.filters.size)
        field("To").performTextReplacement("8")
        rule.waitForIdle()
        assertEquals(SizeFilter.Range(1_000_000_000, 8_000_000_000), page.filters.size)
        val asked = chosen.size
        field("To").performTextReplacement("0.5")
        rule.waitForIdle()
        rule.onNodeWithText("From must be no more than To. Still 1–8 B.").assertIsDisplayed()
        field("From").performTextReplacement("0")
        rule.waitForIdle()
        rule.onNodeWithText("Both must be more than 0. Still 1–8 B.").assertIsDisplayed()
        assertEquals("an invalid range is never applied", asked, chosen.size)
        assertEquals(SizeFilter.Range(1_000_000_000, 8_000_000_000), page.filters.size)
        field("From").performTextReplacement("0.5")
        field("To").performTextReplacement("1.5")
        rule.waitForIdle()
        assertEquals(SizeFilter.Range(500_000_000, 1_500_000_000), page.filters.size)
        assertEquals(0, rule.onAllNodesWithText("Still", substring = true).fetchSemanticsNodes().size)
    }

    @Test
    fun onAPhoneWhereNothingFitsTheSearchSaysWhyAndOffersAnySize() {
        page = page.copy(search = page.search.copy(hub = HubAnswer.NothingFits))
        show()
        rule.onNodeWithText(NOTHING_FITS).assertIsDisplayed()
        rule.onNodeWithText(NO_MATCHES).assertDoesNotExist()
        rule.onNodeWithText("Any size").performClick()
        rule.waitForIdle()
        assertEquals(SizeFilter.Any, chosen.single().size)
    }

    /** Where "Fits this phone" caps under any model (D128): the view model asks no Hub, and the page says why. */
    @Test
    fun aTinyCapIsNothingFitsFromTheViewModel() {
        val tiny = DeviceFacts(budgetBytes = 1L shl 20, chip = DeviceKind.Cpu)
        val cache = FakeHubSearch()
        val vm = viewModel(cache, MutableStateFlow(SearchFilters()), tiny)
        try {
            rule.setContent { DemoTheme(ThemeMode.Light) { ModelsHost(vm, onBack = null) } }
            rule.runOnUiThread {
                vm.setFocused(true)
                vm.setQuery("qwen")
            }
            rule.waitUntil(5_000) { rule.onAllNodesWithText(NOTHING_FITS).fetchSemanticsNodes().isNotEmpty() }
            assertEquals("no ask under the smallest model", emptyList<Any>(), cache.asked)
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    /** A choice made in the sheet is saved, and a page opened later (a new view model) shows it, dot and all. */
    @Test
    fun theFiltersPersistAcrossAViewModel() {
        val saved = MutableStateFlow(SearchFilters())
        val first = viewModel(FakeHubSearch(), saved)
        var vm by mutableStateOf(first)
        try {
            rule.setContent { DemoTheme(ThemeMode.Light) { ModelsHost(vm, onBack = null) } }
            rule.waitForIdle()
            filterAction().performClick()
            rule.waitForIdle()
            choice("Trending").performClick()
            rule.waitUntil(5_000) { saved.value.sort == SearchOrder.Trending }
            rule.onNodeWithText("Done").performClick()
            rule.waitForIdle()
            first.viewModelScope.cancel()
            vm = viewModel(FakeHubSearch(), saved)
            rule.waitUntil(5_000) {
                rule.onAllNodes(hasTestTag(MODELS_FILTERS) and marked).fetchSemanticsNodes().isNotEmpty()
            }
            filterAction().performClick()
            rule.waitForIdle()
            choice("Trending").assertIsSelected()
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    /**
     * Two quick choices while the store is still writing the first (DataStore's round trip): both survive, since each
     * is a change to whatever is stored, not a copy of the filters the sheet last showed.
     */
    @Test
    fun twoQuickChangesBothSurviveASlowStore() {
        val saved = MutableStateFlow(SearchFilters())
        val gate = CompletableDeferred<Unit>()
        val writes = Mutex()
        // as DataStore does: one write at a time, each applied to what is stored; the first held until the gate opens
        val vm =
            viewModel(FakeHubSearch(), saved) { change ->
                writes.withLock {
                    gate.await()
                    saved.update(change)
                }
            }
        try {
            rule.setContent { DemoTheme(ThemeMode.Light) { ModelsHost(vm, onBack = null) } }
            rule.waitForIdle()
            filterAction().performClick()
            rule.waitForIdle()
            choice("Newest").performClick()
            rule.onNodeWithText("Sees images").performClick()
            rule.waitForIdle()
            assertEquals("nothing written yet", SearchFilters(), saved.value)
            gate.complete(Unit)
            rule.waitUntil(5_000) { saved.value.seesImages }
            assertEquals(SearchFilters(sort = SearchOrder.Newest, seesImages = true), saved.value)
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    /** The next page lands under the rows: nothing above it, the local rows or the Hub's first, moves. */
    @OptIn(ExperimentalPagingApi::class)
    @Test
    fun anAppendDoesntMoveTheRowsAbove() {
        val source = HeldSecondPage()
        val pager =
            Pager(
                PagingConfig(pageSize = 2, prefetchDistance = 1, initialLoadSize = 2),
                remoteMediator = NothingToFetch(),
            ) { source }.flow
        rule.setContent {
            DemoTheme(ThemeMode.Light) {
                val rows = pager.collectAsLazyPagingItems()
                val answer = hubAnswer("qwen", rows.itemCount, rows.loadState, localMatches = 1)
                ModelsScreen(
                    ModelsPage(null, SearchView("qwen", emptyList(), listOf(swift), answer), true, false),
                    ModelsActions(),
                    onBack = null,
                    hubRows = rows,
                ) { e, _, m -> EntryRow(e, null, e.size, null, null, {}, {}, {}, m) }
            }
        }
        rule.waitUntil(5_000) { rule.onAllNodesWithText("Hub One").fetchSemanticsNodes().isNotEmpty() }
        rule.waitUntil(5_000) { source.secondAsked.isCompleted }
        val before = listOf(swift.name, "Hub One", "Hub Two").map { bounds(it) }
        source.second.complete(Unit)
        rule.waitUntil(5_000) { rule.onAllNodesWithText("Hub Three").fetchSemanticsNodes().isNotEmpty() }
        assertEquals("the rows above stayed put", before, listOf(swift.name, "Hub One", "Hub Two").map { bounds(it) })
    }

    private fun bounds(name: String) = row(name).fetchSemanticsNode().let { it.positionInRoot to it.size.toSize() }

    private fun viewModel(
        cache: FakeHubSearch,
        saved: MutableStateFlow<SearchFilters>,
        facts: DeviceFacts = DeviceFacts(budgetBytes = 8L shl 30, chip = DeviceKind.Gpu),
        save: (suspend (FiltersChange) -> Unit)? = null,
    ) = ModelsViewModel(
        hub = cache,
        models = FakeModelLibrary(),
        facts = { facts },
        freeBytes = { 120_000_000_000L },
        firstRunSeen = flowOf(true),
        clock = { 0L },
        zone = ZoneOffset.UTC,
        io = EmptyCoroutineContext,
        savedState = SavedStateHandle(),
        searchFilters = saved,
        saveSearchFilters = save ?: { change -> saved.update(change) },
    )

    @OptIn(ExperimentalPagingApi::class)
    private class NothingToFetch : RemoteMediator<Int, ModelEntry>() {
        override suspend fun load(loadType: LoadType, state: PagingState<Int, ModelEntry>): MediatorResult =
            MediatorResult.Success(endOfPaginationReached = true)
    }

    /** Two pages of Hub rows; the second is held until the test lets it go, as a slow network would. */
    private class HeldSecondPage : PagingSource<Int, ModelEntry>() {
        val secondAsked = CompletableDeferred<Unit>()
        val second = CompletableDeferred<Unit>()

        override suspend fun load(params: LoadParams<Int>): LoadResult<Int, ModelEntry> =
            if ((params.key ?: 0) == 0) {
                LoadResult.Page(listOf(hubEntry("Hub One"), hubEntry("Hub Two")), prevKey = null, nextKey = 1)
            } else {
                secondAsked.complete(Unit)
                second.await()
                LoadResult.Page(listOf(hubEntry("Hub Three")), prevKey = 0, nextKey = null)
            }

        override fun getRefreshKey(state: PagingState<Int, ModelEntry>): Int? = null
    }

    private companion object {
        fun hubEntry(name: String) =
            ModelEntry.of(
                SearchModel(name.lowercase().replace(' ', '-'), name, "someone/$name", "someone", 1, false, null),
            )
    }
}
