package com.suhel.llamabro.demo.ui.models

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.height
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
import com.suhel.llamabro.demo.designsystem.components.DownloadStage
import com.suhel.llamabro.demo.designsystem.components.DownloadView
import com.suhel.llamabro.demo.designsystem.theme.DemoTheme
import com.suhel.llamabro.demo.designsystem.theme.Light
import com.suhel.llamabro.demo.designsystem.theme.ThemeMode
import com.suhel.llamabro.demo.models.Catalog
import com.suhel.llamabro.demo.models.DeviceFacts
import com.suhel.llamabro.demo.models.DownloadRecord
import com.suhel.llamabro.demo.models.LibraryModel
import com.suhel.llamabro.demo.models.SearchModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.IOException
import java.time.ZoneOffset
import kotlin.coroutines.EmptyCoroutineContext

/**
 * The Models page as drawn (docs/design/models-page.md §2, §3, §5-§7): search mode the moment the field is focused,
 * rows that move with their state as one keyed item and keep their height, the intro on a fresh install, the five fit
 * symbols taught, Remove in danger. The page is stateless here; the view model's own tests cover its flows.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ModelsScreenTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val deepEntry = Catalog.suggested[2]
    private val deep = ModelEntry.of(deepEntry)
    private val swift = ModelEntry.of(Catalog.suggested[0])
    private val downloadingDeep =
        ModelEntry.downloading(
            deepEntry.key,
            DownloadRecord(deepEntry.key, deepEntry.realName, deepEntry.maker, 3_400_000_000),
        )
    private val onPhone =
        ModelEntry.of(LibraryModel(Catalog.suggested[0].key, "Qwen3.5 0.8B", 700_000_000, null, isDefault = true))

    private fun browse(
        intro: Boolean = false,
        downloading: List<ModelEntry> = emptyList(),
        onPhone: List<ModelEntry> = emptyList(),
        suggested: List<ModelEntry> = listOf(deep),
        shortOnMemory: ModelEntry? = null,
        storage: StorageInfo? = null,
    ) = Browse(intro, downloading, onPhone, storage, suggested, shortOnMemory)

    private val noSearch = SearchView("", emptyList(), emptyList(), HubAnswer.Idle)

    private var page by mutableStateOf(ModelsPage(browse(), noSearch, searching = false, fetching = false))
    private var states by mutableStateOf<Map<String, RowState>>(emptyMap())
    private var hubRows by mutableStateOf<List<ModelEntry>>(emptyList())

    private fun show(actions: ModelsActions = ModelsActions(), onBack: (() -> Unit)? = null) {
        rule.setContent {
            DemoTheme(ThemeMode.Light) {
                ModelsScreen(page, actions, onBack, hubRows = rememberHubRows(hubRows)) { entry, _, modifier ->
                    EntryRow(
                        entry,
                        fit = null,
                        size = entry.size,
                        state = states[entry.repo],
                        fact = null,
                        onClick = {},
                        onToggle = {},
                        onCancel = {},
                        modifier = modifier,
                    )
                }
            }
        }
        rule.waitForIdle()
    }

    private fun row(name: String): SemanticsNodeInteraction =
        rule.onNode(hasTestTag(MODEL_ROW) and hasText(name, substring = true))

    private fun field() = rule.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag(MODELS_SEARCH)))

    /** Leaving search starts browse at the list's top, the On this phone label in view, never a carried offset. */
    @Test
    fun leavingSearchReturnsToBrowseAtTheTop() {
        val many = Catalog.suggested.map(ModelEntry::of)
        page =
            page.copy(
                browse = browse(onPhone = listOf(onPhone), suggested = many),
                search = SearchView("", listOf("gemma", "phi"), listOf(onPhone) + many, HubAnswer.Idle),
                searching = true,
            )
        show()
        rule.onNodeWithTag("model_list").performScrollToIndex(5)
        page = page.copy(searching = false)
        rule.waitForIdle()
        rule.onNodeWithText("On this phone").assertIsDisplayed()
        val list = rule.onNodeWithTag("model_list").getBoundsInRoot()
        val label = rule.onNodeWithText("On this phone").getBoundsInRoot()
        assertTrue("the label is at the list's top, not under the field", label.top >= list.top)
    }

    /** A problem is a line under the field with its own action, not a row's card in the list. */
    @Test
    fun aProblemIsALineUnderTheFieldNotARowInTheList() {
        var closed = false
        page = page.copy(problem = "Couldn't remove Qwen3.5 4B. Try again.")
        show(ModelsActions(onDismissProblem = { closed = true }))
        rule.onNodeWithTag("models_problem").assertIsDisplayed()
        rule.onNode(hasTestTag("models_problem") and hasAnyAncestor(hasTestTag("model_list"))).assertDoesNotExist()
        rule.onNodeWithText("OK").performClick()
        assertTrue(closed)
    }

    /** A Hub row with no maker and no size says its repo's owner on line 2, never an empty line. */
    @Test
    fun aRowWithNoMakerAndNoSizeSaysItsOwner() {
        val bare = ModelEntry("phi-4-mini", "Phi 4 mini", "someone/Phi-4-mini-GGUF", 10, null, null)
        page = page.copy(browse = browse(suggested = listOf(bare)))
        show()
        row("Phi 4 mini").assertExists()
        rule.onNodeWithText("someone").assertIsDisplayed()
    }

    /** An owner standing in for the maker reads through the one maker map. */
    @Test
    fun anOwnerStandingInForTheMakerReadsThroughTheMap() {
        val bare = ModelEntry("qwen2.5-coder-7b", "Qwen2.5 Coder 7B", "Qwen/Qwen2.5-Coder-7B-GGUF", 10, null, null)
        page = page.copy(browse = browse(suggested = listOf(bare)))
        show()
        rule.onNodeWithText("Alibaba Qwen").assertIsDisplayed()
    }

    @Test
    fun tappingTheFieldShowsSearchModeWithRecentChipsBeforeTyping() {
        val recent = SearchView("", listOf("gemma", "phi coder"), listOf(swift, deep), HubAnswer.Idle)
        page = page.copy(search = recent)
        show(ModelsActions(onFocus = { page = page.copy(searching = page.searching || it) }))
        rule.onNodeWithText("Suggested for this phone").assertIsDisplayed()
        field().requestFocus()
        rule.waitForIdle()
        rule.onNodeWithText("gemma").assertIsDisplayed()
        rule.onNodeWithText("phi coder").assertIsDisplayed()
        // one label per group in both modes, never renamed on focus
        rule.onNodeWithText("Suggested for this phone").assertIsDisplayed()
        rule.onNodeWithText("SUGGESTIONS").assertDoesNotExist()
        rule.onNodeWithText("YOUR MODELS").assertDoesNotExist()
        row("Qwen3.5 0.8B").assertExists()
    }

    @Test
    fun typingFiltersTheLocalRowsAtOnce() {
        val all = listOf(swift, deep, ModelEntry.of(Catalog.suggested[1]))
        page = page.copy(searching = true, search = SearchView("", emptyList(), all, HubAnswer.Idle))
        show(
            ModelsActions(
                onQuery = { text ->
                    val local = all.filter { it.name.contains(text, ignoreCase = true) }
                    page = page.copy(search = SearchView(text, page.search.recent, local, page.search.hub))
                },
            ),
        )
        field().performTextInput("4B")
        rule.waitForIdle()
        row("Qwen3.5 4B").assertExists()
        row("Qwen3.5 0.8B").assertDoesNotExist()
    }

    @Test
    fun aRecentSearchTappedBecomesTheQuery() {
        var picked = ""
        page = page.copy(searching = true, search = noSearch.copy(recent = listOf("gemma")))
        show(ModelsActions(onRecent = { picked = it }))
        rule.onNodeWithText("gemma").performClick()
        assertEquals("gemma", picked)
    }

    @Test
    fun getMovesTheRowFromSuggestedToDownloadingAsOneItemAndProgressKeepsItsHeight() {
        page = page.copy(browse = browse(suggested = listOf(swift, deep)))
        show()
        val before = row("Qwen3.5 4B").fetchSemanticsNode().id
        // Get: the repository lists the download and the suggestion leaves (one keyed item moving between sections)
        states = mapOf(deep.repo to downloadState(DownloadStage.Waiting, 0f))
        page = page.copy(browse = browse(downloading = listOf(downloadingDeep), suggested = listOf(swift)))
        rule.waitForIdle()
        rule.onNodeWithText("Downloading").assertIsDisplayed()
        assertEquals("the same row, moved", before, row("Qwen3.5 4B").fetchSemanticsNode().id)
        val height = row("Qwen3.5 4B").getBoundsInRoot().height
        for (fraction in listOf(0.1f, 0.35f, 0.8f)) {
            states = mapOf(deep.repo to downloadState(DownloadStage.Downloading, fraction))
            rule.waitForIdle()
            assertEquals("a tick at $fraction", height, row("Qwen3.5 4B").getBoundsInRoot().height)
        }
        states = mapOf(deep.repo to downloadState(DownloadStage.Paused, 0.8f))
        rule.waitForIdle()
        assertEquals("paused", height, row("Qwen3.5 4B").getBoundsInRoot().height)
    }

    @Test
    fun aFreshInstallShowsTheIntroAndWhatsAModelTeachesTheFiveSymbols() {
        page = page.copy(browse = browse(intro = true), intro = true)
        show()
        rule.onNodeWithText(MODELS_INTRO).assertIsDisplayed()
        rule.onNodeWithText("What's a model?").performClick()
        rule.waitForIdle()
        for (words in listOf("Runs great", "Runs well", "Slow here", "Too big", "Can't chat")) {
            rule.onNodeWithText(words).assertExists()
        }
    }

    @Test
    fun noIntroOnceFirstRunIsSeen() {
        show()
        rule.onNodeWithText(MODELS_INTRO).assertDoesNotExist()
    }

    @Test
    fun theStorageIsTheOnThisPhoneSectionsNote() {
        page =
            page.copy(
                browse = browse(onPhone = listOf(onPhone), storage = StorageInfo(6_100_000_000, 120_000_000_000)),
            )
        show()
        rule.onNodeWithText("On this phone").assertIsDisplayed()
        rule.onNodeWithText("6.1 GB · 120 GB free").assertIsDisplayed()
        row("Qwen3.5 0.8B").assertExists()
        rule.onNodeWithText("Default", substring = true, useUnmergedTree = true).assertExists()
    }

    @Test
    fun aPhoneShortOnMemoryGetsOneLineNamingTheSmallestModelAndItsRow() {
        page = page.copy(browse = browse(suggested = emptyList(), shortOnMemory = swift))
        show()
        rule.onNodeWithText("This phone is short on memory", substring = true).assertIsDisplayed()
        // the model it names is a row to open under the line, never a kneeling pick above it
        row("Qwen3.5 0.8B").assertIsDisplayed()
    }

    /** Hugging Face's paged rows are drawn in the one list, under their label and below the person's own matches. */
    @Test
    fun theHubsPagedRowsShowUnderTheirLabelBelowTheLocalMatches() {
        val hub =
            ModelEntry.of(
                SearchModel("phi-4-mini", "Phi 4 mini", "someone/Phi-4-mini-GGUF", "someone", 9, false, null),
            )
        hubRows = listOf(hub)
        page =
            page.copy(
                searching = true,
                search = SearchView("qwen", emptyList(), listOf(swift), HubAnswer.Rows(1)),
            )
        show()
        rule.onNodeWithText("From Hugging Face").assertIsDisplayed()
        row("Phi 4 mini").assertIsDisplayed()
        val local = row(swift.name).fetchSemanticsNode().boundsInRoot.top
        assertTrue("below the local matches", row("Phi 4 mini").fetchSemanticsNode().boundsInRoot.top > local)
    }

    @Test
    fun aHubAnswerEmptiedByTheLocalRowsHasNoLabel() {
        page =
            page.copy(
                searching = true,
                search = SearchView("qwen", emptyList(), listOf(swift), HubAnswer.Rows(0)),
            )
        show()
        rule.onNodeWithText("From Hugging Face").assertDoesNotExist()
        page = page.copy(search = page.search.copy(hub = HubAnswer.None))
        rule.waitForIdle()
        rule.onNodeWithText("From Hugging Face").assertIsDisplayed()
        rule.onNodeWithText("No models match. Try a shorter name.").assertIsDisplayed()
        row("Qwen3.5 0.8B").assertExists() // the person's own matches stay above
    }

    @Test
    fun aFailedSearchIsANoticeWithTryAgain() {
        var retried = 0
        page =
            page.copy(searching = true, search = SearchView("phi", emptyList(), emptyList(), HubAnswer.Failed(true)))
        show(ModelsActions(onRetry = { retried++ }))
        rule.onNodeWithText("Couldn't search Hugging Face just now.").assertIsDisplayed()
        rule.onNodeWithText("Try again").performClick()
        assertEquals(1, retried)
    }

    /**
     * A next page that fails while scrolling leaves the rows and says so under them, with Try again retrying that page;
     * the page that loads then joins the list and the line goes.
     */
    @OptIn(ExperimentalPagingApi::class)
    @Test
    fun aFailedNextPageIsAQuietLineUnderTheRowsAndTryAgainLoadsIt() {
        val source = TwoPages()
        // with a mediator that has nothing to fetch, as the app's Pager has one: the first page is then known fetched
        val pager =
            Pager(
                PagingConfig(pageSize = 2, prefetchDistance = 1, initialLoadSize = 2),
                remoteMediator = NothingToFetch(),
            ) { source }.flow
        rule.setContent {
            DemoTheme(ThemeMode.Light) {
                val rows = pager.collectAsLazyPagingItems()
                val answer = hubAnswer("qwen", rows.itemCount, rows.loadState, localMatches = 0)
                ModelsScreen(
                    ModelsPage(null, SearchView("qwen", emptyList(), emptyList(), answer), true, false),
                    ModelsActions(),
                    onBack = null,
                    hubRows = rows,
                ) { entry, _, modifier ->
                    EntryRow(entry, null, entry.size, null, null, {}, {}, {}, modifier)
                }
            }
        }
        rule.waitUntil(5_000) { rule.onAllNodesWithText(MORE_FAILED).fetchSemanticsNodes().isNotEmpty() }
        row("Hub One").assertExists()
        row("Hub Two").assertExists()
        rule.onNodeWithTag(HUB_MORE).assertExists()
        rule.onNodeWithText("Try again").performClick()
        rule.waitUntil(5_000) { rule.onAllNodesWithText(MORE_FAILED).fetchSemanticsNodes().isEmpty() }
        row("Hub Three").assertExists()
        assertEquals("the failed page was asked again", 2, source.secondAsked)
    }

    @OptIn(ExperimentalPagingApi::class)
    private class NothingToFetch : RemoteMediator<Int, ModelEntry>() {
        override suspend fun load(loadType: LoadType, state: PagingState<Int, ModelEntry>): MediatorResult =
            MediatorResult.Success(endOfPaginationReached = true)
    }

    /** Two pages of Hub rows; the second fails the first time it is asked, as offline while scrolling would. */
    private class TwoPages : PagingSource<Int, ModelEntry>() {
        var secondAsked = 0

        private fun hub(name: String) =
            ModelEntry.of(
                SearchModel(name.lowercase().replace(' ', '-'), name, "someone/$name", "someone", 1, false, null),
            )

        override suspend fun load(params: LoadParams<Int>): LoadResult<Int, ModelEntry> =
            if ((params.key ?: 0) == 0) {
                LoadResult.Page(listOf(hub("Hub One"), hub("Hub Two")), prevKey = null, nextKey = 1)
            } else if (++secondAsked == 1) {
                LoadResult.Error(IOException("offline"))
            } else {
                LoadResult.Page(listOf(hub("Hub Three")), prevKey = 0, nextKey = null)
            }

        override fun getRefreshKey(state: PagingState<Int, ModelEntry>): Int? = null
    }

    @Test
    fun backInSearchModeLeavesSearch() {
        var left = 0
        var backs = 0
        page = page.copy(searching = true, search = noSearch.copy(query = "qwen"))
        show(ModelsActions(onLeaveSearch = { left++ }), onBack = { backs++ })
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
        assertEquals(1, left)
        rule.onNode(hasContentDescriptionBack()).performClick()
        assertEquals("the top bar's Back leaves search too", 2, left)
        assertEquals(0, backs)
    }

    @Test
    fun noBackWithNothingUnderThePage() {
        show(onBack = null)
        rule.onNode(hasContentDescriptionBack()).assertDoesNotExist()
    }

    @Test
    fun theRemoveDialogsConfirmIsInTheDangerColour() {
        val model = LibraryModel(Catalog.suggested[0].key, "Qwen3.5 0.8B", 700_000_000, null, isDefault = false)
        page = page.copy(pendingRemove = model)
        show()
        val remove = rule.onNodeWithText("Remove", useUnmergedTree = true)
        remove.assertIsDisplayed()
        // the colour the text is laid out in: the button's ink, read without a pixel copy of the dialog's window
        val layouts = mutableListOf<TextLayoutResult>()
        remove
            .fetchSemanticsNode()
            .config[SemanticsActions.GetTextLayoutResult]
            .action
            ?.invoke(layouts)
        assertEquals(
            "Remove is drawn in the danger ink",
            Light.danger,
            layouts
                .single()
                .layoutInput.style.color,
        )
    }

    @Test
    fun theFieldDoesntMoveWhenTheListArrivesOnAFreshInstall() {
        // the intro is known from the settings on the first frame, before the library scan gives the list
        page = ModelsPage(null, noSearch, searching = false, fetching = false, intro = true)
        show()
        rule.onNodeWithText(MODELS_INTRO).assertIsDisplayed()
        val field = rule.onNodeWithTag(MODELS_SEARCH).getBoundsInRoot()
        page = page.copy(browse = browse(intro = true))
        rule.waitForIdle()
        rule.onNodeWithText("Suggested for this phone").assertIsDisplayed()
        assertEquals("the list arriving moved the field", field, rule.onNodeWithTag(MODELS_SEARCH).getBoundsInRoot())
    }

    @Test
    fun aDownloadThatLandedWhileItsKeyIsStillQueuedIsOneRow() {
        // the repository rescans the library before it drops the finished download's pending key: both list the file,
        // and the page must still draw (one lazy key, once), on the phone, in browse and in search
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val swiftKey = Catalog.suggested[0].key
        val models = FakeModelLibrary()
        models.shelf.value = listOf(LibraryModel(swiftKey, "Qwen3.5 0.8B", 700_000_000, null, isDefault = false))
        models.queue.value = listOf(swiftKey)
        val cache = FakeHubSearch()
        val vm =
            ModelsViewModel(
                hub = cache,
                models = models,
                facts = { DeviceFacts(budgetBytes = 8L shl 30, chip = DeviceKind.Gpu) },
                freeBytes = { 120_000_000_000L },
                firstRunSeen = flowOf(true),
                clock = { 0L },
                zone = ZoneOffset.UTC,
                io = EmptyCoroutineContext,
                savedState = SavedStateHandle(),
            )
        try {
            rule.setContent { DemoTheme(ThemeMode.Light) { ModelsHost(vm, onBack = null) } }
            rule.waitUntil(10_000) { rule.onAllNodesWithText("On this phone").fetchSemanticsNodes().isNotEmpty() }
            rule.onAllNodesWithText("Downloading").assertCountEquals(0)
            row("Qwen3.5 0.8B").assertExists()
            rule.runOnUiThread { vm.setFocused(true) }
            rule.waitUntil(10_000) {
                vm.search.value.onPhone
                    .isNotEmpty() && vm.searching.value
            }
            rule.waitForIdle()
            row("Qwen3.5 0.8B").assertExists()
        } finally {
            vm.viewModelScope.cancel()
            scope.cancel()
        }
    }

    private fun hasContentDescriptionBack() =
        androidx.compose.ui.test
            .hasContentDescription("Back")

    private fun downloadState(stage: DownloadStage, fraction: Float) =
        RowState(
            when (stage) {
                DownloadStage.Waiting -> RowBadge.Queued
                DownloadStage.Downloading -> RowBadge.Downloading(fraction)
                DownloadStage.Paused -> RowBadge.Paused
                DownloadStage.Stopped -> RowBadge.Failed("The connection dropped.")
            },
            DownloadView(stage, "Q4_K_M", fraction, "0.9 of 3.4 GB · 4 min left"),
        )
}
