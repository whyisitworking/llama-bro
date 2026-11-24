package com.suhel.llamabro.demo.ui.models

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.height
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.suhel.llamabro.DeviceKind
import com.suhel.llamabro.demo.designsystem.components.DownloadStage
import com.suhel.llamabro.demo.designsystem.components.DownloadView
import com.suhel.llamabro.demo.designsystem.theme.DemoTheme
import com.suhel.llamabro.demo.designsystem.theme.LightDemoColors
import com.suhel.llamabro.demo.designsystem.theme.ThemeMode
import com.suhel.llamabro.demo.models.Capability
import com.suhel.llamabro.demo.models.Catalog
import com.suhel.llamabro.demo.models.DeviceFacts
import com.suhel.llamabro.demo.models.LibraryModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.ZoneOffset
import kotlin.coroutines.EmptyCoroutineContext

/**
 * The Models page's list (D119): one list across both modes
 * with stable keys, one label per group, the intro and Back that keep the title still, the row's words, and the first
 * paint from local data alone.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ModelsPageListTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val swiftEntry = Catalog.suggested[0]
    private val deep = ModelEntry.of(Catalog.suggested[2])
    private val onPhone = ModelEntry.of(LibraryModel(swiftEntry.key, "Qwen3.5 0.8B", 700_000_000, null, false))
    private val browse = Browse(false, emptyList(), listOf(onPhone), null, listOf(deep), null)
    private val noSearch = SearchView("", emptyList(), emptyList(), HubAnswer.Idle)
    private var page by mutableStateOf(ModelsPage(browse, noSearch, searching = false, fetching = false))

    private fun show(onBack: (() -> Unit)? = null) {
        rule.setContent {
            DemoTheme(ThemeMode.Light) {
                ModelsScreen(page, ModelsActions(), onBack) { entry, _, modifier ->
                    EntryRow(entry, null, entry.size, null, null, {}, {}, {}, modifier)
                }
            }
        }
        rule.waitForIdle()
    }

    private fun rows(name: String) =
        rule.onAllNodes(hasTestTag(MODEL_ROW) and hasText(name, substring = true)).fetchSemanticsNodes().size

    private fun SemanticsNodeInteraction.layout(): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        fetchSemanticsNode()
            .config
            .getOrNull(SemanticsActions.GetTextLayoutResult)
            ?.action
            ?.invoke(results)
        return results.single()
    }

    // overlapping data never crashes the page

    @Test
    fun aModelListedTwiceKeepsItsFirstPlaceAndTheListHasNoKeyTwice() {
        val twice = Browse(false, emptyList(), listOf(onPhone), null, listOf(onPhone, deep), null)
        val items = modelsItems(ModelsPage(twice, noSearch, searching = false, fetching = false))
        assertEquals(items.size, items.map { it.key }.toSet().size)
        assertEquals(1, items.count { it is ModelsItem.Row && it.entry == onPhone })
        page = ModelsPage(twice, noSearch, searching = false, fetching = false)
        show()
        assertEquals(1, rows("Qwen3.5 0.8B"))
    }

    // one label per group in both modes

    @Test
    fun searchNarrowsBrowsesGroupsUnderTheSameLabels() {
        val search = SearchView("", listOf("gemma"), emptyList(), listOf(onPhone), listOf(deep), HubAnswer.Idle)
        val labels = { searching: Boolean ->
            modelsItems(ModelsPage(browse, search, searching, false))
                .filterIsInstance<ModelsItem.Label>()
                .map { it.text }
        }
        assertEquals(listOf(ON_THIS_PHONE, SUGGESTED), labels(false))
        assertEquals(listOf(ON_THIS_PHONE, SUGGESTED), labels(true))
    }

    // the same rows stay one item as the mode changes; nothing is drawn twice

    @Test
    fun aRowInBothModesIsOneRowInEveryFrameOfTheModeChange() {
        page =
            page.copy(search = SearchView("", emptyList(), emptyList(), listOf(onPhone), listOf(deep), HubAnswer.Idle))
        show()
        val top = rule.onNode(hasTestTag(MODEL_ROW) and hasText("Qwen3.5 0.8B", substring = true)).getBoundsInRoot()
        rule.mainClock.autoAdvance = false
        page = page.copy(searching = true)
        repeat(FRAMES) {
            rule.mainClock.advanceTimeBy(FRAME_MILLIS)
            rule.waitForIdle()
            assertEquals("frame $it", 1, rows("Qwen3.5 0.8B"))
            assertEquals("frame $it", 1, rows("Qwen3.5 4B"))
        }
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()
        val after = rule.onNode(hasTestTag(MODEL_ROW) and hasText("Qwen3.5 0.8B", substring = true)).getBoundsInRoot()
        assertEquals("the row stayed in place", top.top, after.top)
    }

    // the intro is browse's

    @Test
    fun theIntroFoldsAwayInSearchModeAndComesBack() {
        page = page.copy(browse = browse.copy(intro = true), intro = true)
        show()
        rule.onNodeWithText(MODELS_INTRO).assertExists()
        page = page.copy(searching = true)
        rule.waitForIdle()
        rule.onNodeWithText(MODELS_INTRO).assertDoesNotExist()
        page = page.copy(searching = false)
        rule.waitForIdle()
        rule.onNodeWithText(MODELS_INTRO).assertExists()
    }

    // Back appearing never moves the title

    @Test
    fun focusingTheFieldOnAFreshInstallFadesBackInWithoutMovingTheTitle() {
        page = page.copy(browse = browse.copy(intro = true), intro = true)
        show(onBack = null)
        val before = rule.onNodeWithText("Models").getBoundsInRoot().left
        page = page.copy(searching = true)
        rule.waitForIdle()
        rule.onNode(hasContentDescription("Back")).assertExists()
        assertEquals(before, rule.onNodeWithText("Models").getBoundsInRoot().left)
    }

    // the field's words

    @Test
    fun theFieldSaysSearchModels() {
        show()
        rule.onNodeWithText("Search models").assertExists()
        rule.onNodeWithText("Search Hugging Face").assertDoesNotExist()
    }

    // one state word, said once

    @Test
    fun aRowOnThePhoneSaysOnlyDefaultAndADownloadsRowHasNoPill() {
        assertNull(stateWord(onPhone, null))
        assertEquals("Default", stateWord(onPhone.copy(isDefault = true), null))
        for (stage in DownloadStage.entries) {
            val state = RowState(RowBadge.Paused, DownloadView(stage, "Q4_K_M", 0.3f, "1.0 of 3.4 GB"))
            assertNull("the footer says $stage; no pill repeats it", stateWord(deep, state))
        }
        assertEquals("On phone", stateWord(deep, RowState(RowBadge.OnPhone, null)))
    }

    // a model that can't chat

    @Test
    fun aRowThatCantChatHasItsNameInTheInkAndNoEmbeddingsGlyph() {
        val embedder =
            deep.copy(
                name = "Qwen3 Embedding 0.6B",
                canChat = false,
                capabilities = listOf(Capability.Embeddings),
            )
        assertTrue(capabilities(embedder).isEmpty())
        page = page.copy(browse = browse.copy(suggested = listOf(embedder)))
        show()
        val name = rule.onNodeWithText("Qwen3 Embedding 0.6B")
        assertEquals(
            LightDemoColors.ink,
            name
                .layout()
                .layoutInput.style.color,
        )
        assertEquals(0, rule.onAllNodes(hasContentDescription("Embeddings")).fetchSemanticsNodes().size)
    }

    // the first paint is from local data; facts landing move nothing

    @Test
    fun theLibraryShowsBeforeThePhonesFactsAndTheirLandingMovesNoRow() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val models = FakeModelLibrary()
        models.shelf.value = listOf(LibraryModel(swiftEntry.key, "Qwen3.5 0.8B", 700_000_000, null, isDefault = true))
        val facts = CompletableDeferred<DeviceFacts>()
        val cache = FakeHubSearch()
        val vm =
            ModelsViewModel(
                hub = cache,
                models = models,
                facts = { facts.await() },
                freeBytes = { 120_000_000_000L },
                firstRunSeen = flowOf(true),
                clock = { 0L },
                zone = ZoneOffset.UTC,
                io = EmptyCoroutineContext,
                savedState = SavedStateHandle(),
            )
        try {
            rule.setContent { DemoTheme(ThemeMode.Light) { ModelsHost(vm, onBack = null) } }
            rule.waitUntil(WAIT_MILLIS) { rows("Qwen3.5 0.8B") == 1 }
            val top = rule.onNode(hasTestTag(MODEL_ROW) and hasText("Qwen3.5 0.8B", substring = true)).getBoundsInRoot()
            facts.complete(DeviceFacts(budgetBytes = 8L shl 30, chip = DeviceKind.Gpu))
            rule.waitUntil(WAIT_MILLIS) {
                rule.onAllNodesWithText(SUGGESTED).fetchSemanticsNodes().isNotEmpty()
            }
            val after =
                rule
                    .onNode(
                        hasTestTag(MODEL_ROW) and hasText("Qwen3.5 0.8B", substring = true),
                    ).getBoundsInRoot()
            assertEquals(top, after)
        } finally {
            vm.viewModelScope.cancel()
            scope.cancel()
        }
    }

    // a landing folds the footer away with the row's height; the row is never redrawn

    @Test
    fun aLandingDownloadsRowFoldsItsFooterAwayRatherThanSnapping() {
        val key = swiftEntry.key
        val downloading = ModelEntry.downloading(key, null)
        val landed = ModelEntry.of(LibraryModel(key, "Qwen3.5 0.8B", 700_000_000, null, isDefault = true))
        val view = DownloadView(DownloadStage.Downloading, "Q4_K_M", 0.99f, "690 of 700 MB")
        var state by mutableStateOf<RowState?>(RowState(RowBadge.Downloading(0.99f), view))
        page =
            ModelsPage(Browse(false, listOf(downloading), emptyList(), null, emptyList(), null), noSearch, false, false)
        rule.setContent {
            DemoTheme(ThemeMode.Light) {
                ModelsScreen(page, ModelsActions(), null) { entry, _, modifier ->
                    EntryRow(entry, null, entry.size, state.takeIf { !entry.onPhone }, null, {}, {}, {}, modifier)
                }
            }
        }
        rule.waitForIdle()
        val row = rule.onNode(hasTestTag(MODEL_ROW) and hasText("Qwen3.5 0.8B", substring = true))
        val before = row.getBoundsInRoot().height
        rule.mainClock.autoAdvance = false
        page = ModelsPage(Browse(false, emptyList(), listOf(landed), null, emptyList(), null), noSearch, false, false)
        state = RowState(RowBadge.OnPhone, null)
        val heights = mutableListOf<Dp>()
        repeat(FRAMES) {
            rule.mainClock.advanceTimeBy(FRAME_MILLIS)
            rule.waitForIdle()
            val height = row.getBoundsInRoot().height
            heights += height
            val footer = rule.onAllNodesWithText("Downloading · Q4_K_M", useUnmergedTree = true).fetchSemanticsNodes()
            // never the footer gone with the row still its old height: an empty band, then a snap
            assertTrue("frame $it: footer gone at the old height", footer.isNotEmpty() || height < before)
        }
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()
        val after = row.getBoundsInRoot().height
        assertTrue("$before > $after", before > after)
        assertTrue("it eased through heights between: $heights", heights.any { it < before && it > after })
        rule.onNodeWithText("Default").assertExists()
    }

    // a row that lands in place swipes to remove the model it is now, not the one it was composed as

    @Test
    fun aSuggestionThatIsGotAndLandsSwipesToRemoveTheModelOnThePhone() {
        val key = swiftEntry.key
        val suggestion = ModelEntry.of(swiftEntry)
        val downloading = ModelEntry.downloading(key, null)
        val landed = ModelEntry.of(LibraryModel(key, "Qwen3.5 0.8B", 812_000_000, null, isDefault = true))
        var removed: LibraryModel? = null
        page =
            ModelsPage(Browse(false, emptyList(), emptyList(), null, listOf(suggestion), null), noSearch, false, false)
        rule.setContent {
            DemoTheme(ThemeMode.Light) {
                ModelsScreen(page, ModelsActions(onRemove = { removed = it.libraryModel() }), null) { e, _, m ->
                    EntryRow(e, null, e.size, null, null, {}, {}, {}, m)
                }
            }
        }
        rule.waitForIdle()
        page =
            ModelsPage(Browse(false, listOf(downloading), emptyList(), null, emptyList(), null), noSearch, false, false)
        rule.waitForIdle()
        page = ModelsPage(Browse(false, emptyList(), listOf(landed), null, emptyList(), null), noSearch, false, false)
        rule.waitForIdle()
        rule.onNode(hasTestTag("library_model")).performTouchInput { swipeLeft() }
        rule.waitForIdle()
        assertEquals(LibraryModel(key, "Qwen3.5 0.8B", 812_000_000, null, isDefault = true), removed)
    }

    private companion object {
        const val FRAMES = 20
        const val FRAME_MILLIS = 16L
        const val WAIT_MILLIS = 10_000L
    }
}
