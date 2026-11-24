package com.suhel.llamabro.demo.ui

import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.accessibility.disableAccessibilityChecks
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.percentOffset
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.lifecycle.SavedStateHandle
import androidx.paging.testing.asSnapshot
import com.suhel.llamabro.demo.data.settings.SearchFilters
import com.suhel.llamabro.demo.data.settings.SizeFilter
import com.suhel.llamabro.demo.designsystem.testing.LocalRecompositionCounter
import com.suhel.llamabro.demo.designsystem.testing.RecompositionCounter
import com.suhel.llamabro.demo.designsystem.theme.DemoTheme
import com.suhel.llamabro.demo.designsystem.theme.LocalDemoColors
import com.suhel.llamabro.demo.designsystem.theme.ThemeMode
import com.suhel.llamabro.demo.models.Catalog
import com.suhel.llamabro.demo.models.FakeScheduler
import com.suhel.llamabro.demo.models.FitLabel
import com.suhel.llamabro.demo.models.FitView
import com.suhel.llamabro.demo.models.Fits
import com.suhel.llamabro.demo.models.Format
import com.suhel.llamabro.demo.models.RepoFile
import com.suhel.llamabro.demo.models.TestHub
import com.suhel.llamabro.demo.testing.FakeDiscovery
import com.suhel.llamabro.demo.testing.ScreenshotRule
import com.suhel.llamabro.demo.testing.TestAppRule
import com.suhel.llamabro.demo.testing.TestConfig
import com.suhel.llamabro.demo.testing.demoRules
import com.suhel.llamabro.demo.testing.settleWindow
import com.suhel.llamabro.demo.testing.withAccessibilityChecks
import com.suhel.llamabro.demo.ui.models.EntryRow
import com.suhel.llamabro.demo.ui.models.HubAnswer
import com.suhel.llamabro.demo.ui.models.ModelEntry
import com.suhel.llamabro.demo.ui.models.ModelSheetRoute
import com.suhel.llamabro.demo.ui.models.ModelsActions
import com.suhel.llamabro.demo.ui.models.ModelsHost
import com.suhel.llamabro.demo.ui.models.ModelsPage
import com.suhel.llamabro.demo.ui.models.ModelsRoute
import com.suhel.llamabro.demo.ui.models.ModelsScreen
import com.suhel.llamabro.demo.ui.models.ModelsViewModel
import com.suhel.llamabro.demo.ui.models.OFFLINE_LINE
import com.suhel.llamabro.demo.ui.models.SearchView
import com.suhel.llamabro.hub.DownloadState
import com.suhel.llamabro.hub.HfModel
import com.suhel.llamabro.testing.FakeLlamaBro
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import java.time.ZoneId

/**
 * Finding, fetching and keeping models: the Models page (its sections, its search and its rows), a model's card and
 * the downloads on their rows. The screens run bare in an activity, over the SDK's fake hub (search, downloads and what
 * is on the phone are its own, D70) with real file sizes stood in for the sheet, so a test can supply the recomposition
 * counter and stays fast and exact.
 */
@OptIn(ExperimentalTestApi::class)
class ModelsTest {
    private val hub = TestHub()
    private val scheduler = FakeScheduler()
    private val engine = FakeLlamaBro()
    private val discovery = FakeDiscovery(hub)
    private val app =
        TestAppRule(
            config = {
                TestConfig.fresh(
                    llamaBro = engine.llamaBro,
                    hub = hub.hub,
                    scheduler = scheduler,
                    discovery = discovery,
                )
            },
        )
    private val compose = createAndroidComposeRule<ComponentActivity>()
    private val shots = ScreenshotRule(compose)

    @get:Rule val rules: RuleChain = RuleChain.outerRule(demoRules(app)).around(compose).around(shots)

    private val q3 = RepoFile("Qwen3.5-4B-Q3_K_M.gguf", "Q3_K_M", 1_900_000_000)
    private val q4 = RepoFile("Qwen3.5-4B-Q4_K_M.gguf", "Q4_K_M", 2_740_937_888)
    private val q8 = RepoFile("Qwen3.5-4B-Q8_0.gguf", "Q8_0", 4_500_000_000)

    // alone it leaves the fake phone's 8 GiB a little room; with the encoder the hub fetches with it, none (D101)
    private val bf16 = RepoFile("Qwen3.5-4B-BF16.gguf", "BF16", 8_400_000_000, encoderBytes = 675_569_344)

    @After fun closeHub() = hub.close()

    /** Phi 4 mini on the fake Hub, as search finds it. */
    private fun listPhi() = hub.list("bartowski/phi-4-mini-GGUF", downloads = 1_200_000)

    /** Llava 7B, which takes pictures. */
    private fun listVision() = hub.list("someone/llava-7b-GGUF", downloads = 412_000, takesImages = true)

    private fun listBoth() {
        listPhi()
        listVision()
    }

    private fun seedRepo(repo: String = "bartowski/phi-4-mini-GGUF") {
        discovery.repoFiles[repo] = listOf(q3, q4, q8, bf16)
    }

    /** The models a card's "Chat with" asked to chat with. */
    private val chattedWith = mutableListOf<HfModel>()

    private fun showModels(
        counter: RecompositionCounter? = null,
        query: String = "",
        theme: ThemeMode = ThemeMode.Light,
    ) {
        show(counter, theme) {
            ModelsRoute(app.container, query, onBack = {}, onChatWith = { chattedWith += it }, onOpenChat = {})
        }
    }

    private fun show(counter: RecompositionCounter?, theme: ThemeMode, content: @Composable () -> Unit) {
        compose.withAccessibilityChecks()
        compose.runOnUiThread { compose.activity.enableEdgeToEdge() }
        compose.setContent {
            DemoTheme(theme) {
                CompositionLocalProvider(LocalRecompositionCounter provides counter) {
                    Box(Modifier.background(LocalDemoColors.current.canvas), content = { content() })
                }
            }
        }
        compose.settleWindow()
    }

    private fun search(text: String) {
        compose.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag("models_search"))).performTextInput(text)
    }

    /** Waits until the searches the page started on its own (its suggestions' facts) have been answered. */
    private fun settleFactRefreshes() {
        compose.waitUntil(15_000) { discovery.searches.isNotEmpty() && discovery.searching.get() == 0 }
        compose.waitForIdle()
    }

    private fun waitFor(text: String, substring: Boolean = false) =
        compose.waitUntilAtLeastOneExists(hasText(text, substring = substring), 15_000)

    /** The card is open on [name]: its header, which only the card has, names the model. */
    private fun waitForCardOf(name: String) =
        compose.waitUntilAtLeastOneExists(hasText(name) and hasAnyAncestor(hasTestTag("card_header")), 15_000)

    private fun openPhi() {
        listBoth()
        seedRepo()
        showModels()
        search("phi")
        waitFor("Phi 4 mini")
        compose.onNodeWithText("Phi 4 mini").performClick()
        waitFor("Smaller")
        compose.settleWindow()
    }

    // ---- the Models screen ----

    @Test fun modelsSuggestsTheCatalogModelsThatRunWellHere() {
        showModels()
        compose.onNodeWithText("Suggested for this phone").assertIsDisplayed()
        // Suggested lists only the catalog's models that run great or well on this phone (models-page design §2):
        // the test phone's own facts say which; every one is called by its own name, the later ones a scroll away
        val facts = runBlocking { app.container.deviceFacts() }
        val good = setOf(FitLabel.RunsGreat, FitLabel.RunsWell)
        val (runWell, dont) = Catalog.suggested.partition { Fits.quick(it, facts).label in good }
        assertTrue("this phone runs some of the catalog well", runWell.isNotEmpty())
        for (entry in runWell) {
            compose.onNodeWithTag("model_list").performScrollToNode(hasText(entry.realName, substring = true))
            compose.onNode(hasTestTag("model_row") and hasText(entry.realName, substring = true)).assertIsDisplayed()
        }
        for (entry in dont) {
            compose
                .onAllNodes(
                    hasTestTag("model_row") and hasText(entry.realName, substring = true),
                ).assertCountEquals(0)
        }
        shots.capture("suggestions")
    }

    @Test fun emptyPhoneSaysNoModelsYet() {
        showModels()
        waitFor("No models on this phone yet.")
        compose.onNodeWithText("Suggested for this phone").assertIsDisplayed()
        compose.onAllNodesWithText("On this phone").assertCountEquals(0)
        compose.onAllNodesWithText("Downloading").assertCountEquals(0)
    }

    @Test fun searchFieldThenStorageThenGroupsInOrder() {
        val swift = HfModel.parse("unsloth/Qwen3.5-0.8B-GGUF:Q4_K_M")
        val gemma = HfModel.parse("unsloth/gemma-3-4b-it-qat-GGUF:Q4_K_M")
        onPhone(swift.toString())
        app.container.models.get(gemma)
        compose.waitUntil(10_000) { scheduler.enqueued.isNotEmpty() }
        hub.advance(gemma)
        showModels()
        waitFor("Downloading")
        waitFor("On this phone")
        val top = { tag: String ->
            compose
                .onNodeWithTag(tag)
                .fetchSemanticsNode()
                .boundsInRoot.top
        }
        val topOf = { text: String ->
            compose
                .onNodeWithText(text)
                .fetchSemanticsNode()
                .boundsInRoot.top
        }
        val order = listOf(top("models_search"), topOf("Downloading"), topOf("On this phone"))
        assertEquals("the field, Downloading, On this phone, in that order: $order", order.sorted(), order)
        assertTrue("each is below the one before: $order", order.zipWithNext().all { (a, b) -> a < b })
        // storage is On this phone's note, on its line (models-page design §2), not a bar under the field
        val note = compose.onNodeWithText(" free", substring = true).fetchSemanticsNode().boundsInRoot
        val label = compose.onNodeWithText("On this phone").fetchSemanticsNode().boundsInRoot
        assertTrue("the storage note shares the label's line", note.top < label.bottom && note.left > label.left)
        compose.onNodeWithTag("model_list").performScrollToNode(hasText("Suggested for this phone"))
        compose.onNodeWithText("Suggested for this phone").assertIsDisplayed()
        // what is taken is not suggested again: Qwen3.5 0.8B (on the phone) and Gemma 3 4B (downloading)
        compose.onNode(hasTestTag("model_row") and hasAnyDescendant(hasText("Gemma 3 4B"))).assertDoesNotExist()
        shots.capture("groups")
    }

    @Test fun theLoadingSlotDoesNotMoveTheList() {
        val fetching = mutableStateOf(false)
        val rows = listOf(ModelEntry.suggestions[0], ModelEntry.suggestions[1])
        compose.withAccessibilityChecks()
        compose.setContent {
            DemoTheme(ThemeMode.Light) {
                Box(Modifier.background(LocalDemoColors.current.canvas)) {
                    ModelsScreen(
                        ModelsPage(
                            browse = null,
                            search = SearchView("qw", emptyList(), rows, HubAnswer.Loading(0)),
                            searching = true,
                            fetching = fetching.value,
                        ),
                        ModelsActions(),
                        onBack = {},
                    ) { row, _, modifier ->
                        EntryRow(row, null, row.size, null, null, {}, {}, {}, modifier)
                    }
                }
            }
        }
        compose.settleWindow()
        val firstRow = { compose.onAllNodesWithTag("model_row")[0].fetchSemanticsNode().boundsInRoot }
        // the one loading signal is the line in the field's own edge, which says "Loading"
        val loading =
            hasTestTag("models_search") and SemanticsMatcher.keyIsDefined(SemanticsProperties.StateDescription)
        val idle = firstRow()
        compose.onAllNodes(loading).assertCountEquals(0)
        compose.runOnIdle { fetching.value = true }
        compose.waitForIdle()
        compose.onAllNodes(loading).assertCountEquals(1)
        assertEquals("the first row does not move when the line runs", idle, firstRow())
        compose.runOnIdle { fetching.value = false }
        compose.waitForIdle()
        assertEquals("nor when it goes", idle, firstRow())
    }

    @Test fun typingFiltersTheLocalModelsAtOnceThenAddsTheHubsRows() {
        onPhone("unsloth/Qwen3.5-0.8B-GGUF:Q4_K_M")
        hub.list("someone/Qwen-Coder-1B-GGUF", downloads = 9_000)
        showModels()
        waitFor("On this phone")
        val answer = CompletableDeferred<Unit>()
        discovery.searchGate = answer
        search("qwen")
        // search mode at once: browse's groups narrowed under the same labels (D119), the local matches before
        // Hugging Face answers
        waitFor("On this phone")
        compose.onAllNodesWithText("YOUR MODELS").assertCountEquals(0)
        compose.onNode(hasTestTag("model_row") and hasText("Qwen3.5 0.8B", substring = true)).assertIsDisplayed()
        compose.waitUntil(15_000) { discovery.searches.contains("qwen") }
        answer.complete(Unit)
        // Hugging Face's part is under the local matches: a scroll down the list once it has answered
        compose.waitUntil(15_000) {
            runCatching {
                compose.onNodeWithTag("model_list").performScrollToNode(hasText("Coder", substring = true))
            }.isSuccess
        }
        compose.onNodeWithText("From Hugging Face").assertIsDisplayed()
    }

    @Test fun resultsCollapseByModel() {
        listBoth()
        // a second upload of Llava 7B: the SDK groups it under the original, so it is still one row
        hub.list("someone/llava-7b-GGUF", downloads = 412_000, takesImages = true, baseModel = "someone/llava-7b")
        hub.list("other/llava-7b-GGUF", downloads = 100, baseModel = "someone/llava-7b")
        showModels()
        search("gguf")
        waitFor("Phi 4 mini")
        compose.onAllNodesWithTag("model_row").assertCountEquals(2)
        // each row's capability line speaks its icons and its count as one phrase
        compose.onNode(hasContentDescription("Sees images", substring = true)).assertIsDisplayed()
        compose.onNode(hasContentDescription("1.2M", substring = true)).assertIsDisplayed()
        shots.capture("results")
    }

    @Test fun noResultsLine() {
        showModels()
        search("zzzz")
        waitFor("No models match. Try a shorter name.")
    }

    @Test fun searchErrorShowsRetry() {
        listPhi()
        showModels()
        // the page refreshes its suggestions' facts as it opens: the rate limit is for the search, so it comes after
        settleFactRefreshes()
        hub.fake.rateLimit() // Hugging Face asks the next request to wait: a failure worth retrying
        search("phi")
        waitFor("Try again")
        compose.onNodeWithText("Try again").performClick()
        waitFor("Phi 4 mini")
    }

    @Test fun offlineKeepsSuggestionsAndSaysSo() {
        hub.fake.offline = true
        showModels()
        search("qwen")
        // the line is Hugging Face's part, under the local matches (models-page design §2): a scroll down the list
        // the scroll is the test finding the line, not a person's tap: it starts while the keyboard still settles, when
        // a frame can clip the field's targets short of 48 dp. The checks come back for the settled screen.
        compose.disableAccessibilityChecks()
        compose.waitUntil(15_000) {
            runCatching { compose.onNodeWithTag("model_list").performScrollToNode(hasText(OFFLINE_LINE)) }.isSuccess
        }
        compose.waitForIdle()
        compose.withAccessibilityChecks()
        waitFor(OFFLINE_LINE)
        // the suggestions are kept: the rows above the keyboard are on screen (the rest are a scroll away)
        assertTrue(
            "suggestions are listed",
            compose.onAllNodesWithTag("model_row").fetchSemanticsNodes().size >= 3,
        )
        shots.capture("offline")
    }

    @Test fun aQueryGivenOnArrivalIsSearched() {
        listPhi()
        showModels(query = "phi")
        waitFor("Phi 4 mini")
        assertTrue(discovery.searches.contains("phi"))
    }

    // ---- the model's sheet ----

    @Test fun basicTitlesEachSizeAndNamesItsQuant() {
        openPhi()
        for (word in listOf("Smaller", "Recommended", "Best quality")) compose.onNodeWithText(word).assertIsDisplayed()
        // labels name the quant under the tier's title (models-page design §1)
        compose
            .onNode(
                hasText("Q4_K_M ·", substring = true) and hasAnyAncestor(hasTestTag("tier_Recommended")),
                useUnmergedTree = true,
            ).assertIsDisplayed()
        // the header names the model; the repo is the page's source at its end, shown only with a page (D123)
        compose.onNode(hasText("Phi 4 mini") and hasAnyAncestor(hasTestTag("card_header"))).assertIsDisplayed()
        shots.capture("basic")
    }

    @Test fun getNamesTheQuantInBothModes() {
        openPhi()
        waitFor("Get Q4_K_M · ", substring = true)
        compose.onNodeWithText("Advanced").performClick()
        waitFor("Get Q4_K_M · ", substring = true)
    }

    @Test fun advancedRowsHaveEqualHeight() {
        openPhi()
        compose.onNodeWithText("Advanced").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("quant_row").fetchSemanticsNodes().size >= 3 }
        // the rows themselves: every file of the model is as tall as the others, the recommended one too
        val heights = compose.onAllNodesWithTag("quant_row").fetchSemanticsNodes().map { it.size.height }
        assertTrue("there are rows to compare: $heights", heights.size >= 3)
        assertTrue("every row is as tall as the others: $heights", heights.distinct().size == 1)
        shots.capture("advanced")
    }

    @Test fun advancedColumnsAlign() {
        openPhi()
        compose.onNodeWithText("Advanced").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("quant_row").fetchSemanticsNodes().size >= 3 }
        // the size column starts at the same x in every row: read each row's "GB" text
        val sizes =
            compose
                .onAllNodes(
                    hasText(" GB", substring = true) and hasAnyAncestor(hasTestTag("quant_row")),
                    useUnmergedTree = true,
                ).fetchSemanticsNodes()
                .map { it.boundsInRoot.left }
        assertTrue("there are sizes to compare", sizes.size >= 3)
        assertTrue("sizes share one x: $sizes", sizes.distinct().size == 1)
    }

    @Test fun aFileTooBigWithItsEncoderShowsTooBig() {
        openPhi()
        compose.onNodeWithText("Advanced").performClick()
        // the fit is a symbol in the sizes; its words are what a screen reader hears
        compose.waitUntilAtLeastOneExists(hasContentDescription("Too big"), 15_000)
        compose.onAllNodesWithText("Too big").assertCountEquals(0)
    }

    @Test fun theCardShowsTheOriginalModelsPage() {
        discovery.pages["Qwen/Qwen3.5-0.8B"] = "# Qwen3.5-0.8B\n\n<p align=\"center\"><b>Small and quick.</b></p>"
        // the catalog names the upload's original, whose page the card shows, with no request for it
        discovery.repoFiles["unsloth/Qwen3.5-0.8B-GGUF"] = listOf(q3, q4, q8)
        showModels()
        waitFor("Qwen3.5 0.8B")
        compose.onNode(hasTestTag("model_row") and hasText("Qwen3.5 0.8B", substring = true)).performClick()
        waitFor("Small and quick.")
        compose.onNodeWithTag("get_button").assertIsDisplayed() // pinned under the page, whatever its length
        shots.capture("page")
    }

    @Test fun aModelThatCantChatHasNoGet() {
        discovery.repoFiles["Qwen/Qwen3-Embedding-0.6B-GGUF"] = listOf(q3, q4, q8)
        val embedder =
            ModelEntry(
                groupKey = "qwen3-embedding-0.6b",
                name = "Qwen3 Embedding 0.6B",
                repo = "Qwen/Qwen3-Embedding-0.6B-GGUF",
                downloads = 220_000,
                maker = "Qwen",
                catalog = null,
                canChat = false,
            )
        show(null, ThemeMode.Light) { ModelSheetRoute(app.container, embedder, {}, {}, onChatWith = {}) }
        // one statement on the state line, no second sentence and no "Embeddings, not chat" word
        waitFor("Not for chat: this model can't hold a conversation")
        compose.onAllNodesWithText("This app chats only; this model can't chat.").assertCountEquals(0)
        // no size choice for a model that can't chat
        compose.onAllNodesWithText("Smaller").assertCountEquals(0)
        compose.onAllNodesWithTag("get_button").assertCountEquals(0)
    }

    @Test fun sheetFailureOffersRetry() {
        listPhi()
        showModels()
        search("phi")
        waitFor("Phi 4 mini")
        compose.onNodeWithText("Phi 4 mini").performClick()
        waitFor("Try again") // no files for this repo: the fake fails
        seedRepo()
        compose.onNodeWithText("Try again").performClick()
        waitFor("Smaller")
    }

    // ---- downloads ----

    @Test fun downloadProgressShowsOnTheRow() {
        openPhi()
        compose.waitUntilAtLeastOneExists(hasTestTag("get_button"), 15_000)
        compose.onNodeWithTag("get_button").performClick()
        compose.waitUntil(10_000) { scheduler.enqueued.isNotEmpty() }
        val key = scheduler.enqueued.single()
        hub.advance(key) // the worker, here the fake hub: some bytes arrive, and the download goes on
        // the row's ring panel says how far, from the repository: a download started from the card shows on the row,
        // which the search now lists among the person's own (the Hub's row for it is left out)
        compose.waitUntilAtLeastOneExists(hasTestTag("download_row"), 15_000)
        compose.waitUntil(15_000) {
            compose
                .onAllNodes(hasText(" of ", substring = true) and hasAnyAncestor(hasTestTag("download_row")), true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
    }

    @Test fun progressTicksRecomposeOnlyTheDownloadingRow() {
        listBoth()
        seedRepo()
        val counter = RecompositionCounter()
        showModels(counter)
        search("gguf")
        waitFor("Phi 4 mini")
        compose.onNodeWithText("Phi 4 mini").performClick()
        waitFor("Smaller")
        compose.settleWindow()
        compose.onNodeWithTag("get_button").performClick()
        compose.waitUntil(10_000) { scheduler.enqueued.isNotEmpty() }
        val key = scheduler.enqueued.single()
        compose.waitForIdle()
        counter.reset()
        ticks(key)
        assertEquals("the other row never recomposed", 0, counter.counts["model-llava-7b"] ?: 0)
        assertTrue("the downloading row did: ${counter.counts}", (counter.counts["model-phi-4-mini"] ?: 0) > 0)
    }

    // ---- what is downloading and on the phone ----

    @Test fun aFitArrivingRecomposesOnlyItsRow() {
        listBoth()
        val viewModel =
            ModelsViewModel(
                hub = app.container.hubCache,
                models = app.container.models,
                facts = app.container::deviceFacts,
                freeBytes = { 100_000_000_000 },
                firstRunSeen = flowOf(true),
                initialQuery = "gguf",
                clock = app.container.clock,
                zone = ZoneId.systemDefault(),
                io = Dispatchers.IO,
                savedState = SavedStateHandle(),
                // the fake Hub lists these repos without a parameter count, which a range leaves out (F176)
                searchFilters = flowOf(SearchFilters(size = SizeFilter.Any)),
            )
        val counter = RecompositionCounter()
        show(counter, ThemeMode.Light) { ModelsHost(viewModel, onBack = {}) }
        waitFor("Phi 4 mini")
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("model_row").fetchSemanticsNodes().size == 2 }
        compose.waitForIdle()
        counter.reset()
        // the card has sized Phi's real files: its row's symbol appears (the fake hub gave no parameter count, so
        // the row had none), and Llava's row is not touched
        val hubRows = runBlocking { viewModel.hubPages.asSnapshot() }
        val phi = hubRows.single { it.groupKey == "phi-4-mini" }
        compose.runOnUiThread { viewModel.exactFit(phi, FitView(FitLabel.RunsGreat, 30)) }
        compose.waitUntil(10_000) { (counter.counts["model-phi-4-mini"] ?: 0) >= 1 }
        compose.waitForIdle()
        assertEquals("the row whose fit arrived did not touch the other", 0, counter.counts["model-llava-7b"] ?: 0)
    }

    /**
     * The download of [key] moves on in its four steps, as the hub reports them (each one a tick on screen), then stops
     * short of the end, so nothing but its progress changes.
     */
    private fun ticks(key: HfModel) {
        hub.progressing(key, millis = TICK_MILLIS * 4)
        compose.waitUntil(TICK_MILLIS * 8) {
            compose.waitForIdle()
            val running = hub.hub.progress(key).value as? DownloadState.Downloading ?: return@waitUntil false
            // the size is known once the first bytes have come
            running.progress.bytesTotal > 0 && running.progress.bytesDone * 2 >= running.progress.bytesTotal
        }
        hub.stop(key)
        compose.waitForIdle()
    }

    /** These models are on the phone, downloaded through the fake hub; their sizes are the hub's. */
    private fun onPhone(vararg refs: String) {
        hub.install(*refs.map(HfModel::parse).toTypedArray())
        app.container.models.changed()
    }

    /**
     * The one size of a model on the phone (models-page design §7): the hub's model file and the catalog's image
     * reader beside it, what the row, the storage note and the remove dialog each say.
     */
    private fun sizeOf(ref: String): Long {
        val key = HfModel.parse(ref)
        val model =
            runBlocking {
                hub.hub
                    .cached()
                    .single { it.model == key }
                    .sizeBytes
            }
        return model + (Catalog.byRepo(key.repo)?.mmprojBytes ?: 0L)
    }

    @Test fun storageBarShowsUsedAndFree() {
        onPhone("unsloth/Qwen3.5-0.8B-GGUF:Q4_K_M", "unsloth/Qwen3.5-4B-GGUF:Q4_K_M")
        val used = sizeOf("unsloth/Qwen3.5-0.8B-GGUF:Q4_K_M") + sizeOf("unsloth/Qwen3.5-4B-GGUF:Q4_K_M")
        showModels()
        // On this phone's note: "6.1 GB · 120 GB free"
        waitFor("${Format.storageGb(used)} · ", substring = true)
        compose.onNodeWithText(" free", substring = true).assertIsDisplayed()
        shots.capture("storage")
    }

    @Test fun onThisPhoneShowsModelsWithNamesAndSizes() {
        onPhone("unsloth/Qwen3.5-0.8B-GGUF:Q4_K_M")
        showModels()
        waitFor("Qwen3.5 0.8B")
        // line 2 of the row, "maker · size" (On this phone's note says what all of them take, so the row is asked)
        val size = Format.sizeInGb(sizeOf("unsloth/Qwen3.5-0.8B-GGUF:Q4_K_M"))
        compose.onNode(hasTestTag("model_row") and hasText(size, substring = true)).assertIsDisplayed()
    }

    @Test fun downloadingShowsItsControls() {
        val key = HfModel.parse("unsloth/Qwen3.5-0.8B-GGUF:Q4_K_M")
        app.container.models.get(key)
        compose.waitUntil(10_000) { scheduler.enqueued.isNotEmpty() }
        hub.advance(key)
        showModels()
        waitFor("Downloading")
        // the row's ring panel: Pause in the ring, the bytes done of the total under "Downloading · Q4_K_M"
        compose
            .onNode(
                hasContentDescription("Pause") and hasAnyAncestor(hasTestTag("download_row")),
            ).assertIsDisplayed()
        compose.onNodeWithText(" of ", substring = true, useUnmergedTree = true).assertIsDisplayed()
        shots.capture("downloading")
    }

    @Test fun tappingAModelOnThePhoneOpensItsCard() {
        onPhone("unsloth/Qwen3.5-0.8B-GGUF:Q4_K_M")
        showModels()
        waitFor("Qwen3.5 0.8B")
        compose.onNodeWithTag("library_model").performClick()
        // the card opens on its header; the repo is the page's source at its end, shown only with a page (D123)
        waitForCardOf("Qwen3.5 0.8B")
    }

    @Test fun aModelOnThePhoneOffersAChat() {
        onPhone("unsloth/Qwen3.5-0.8B-GGUF:Q4_K_M")
        discovery.repoFiles["unsloth/Qwen3.5-0.8B-GGUF"] = listOf(q3, q4, q8)
        showModels()
        waitFor("Qwen3.5 0.8B")
        compose.onNodeWithTag("library_model").performClick()
        waitFor("On this phone · Q4_K_M", substring = true)
        compose.onAllNodesWithTag("get_button").assertCountEquals(0)
        compose.onNodeWithTag("another_size").assertIsDisplayed()
        compose.onNodeWithTag("chat_with").performClick()
        compose.waitUntil(5_000) { chattedWith.isNotEmpty() }
        assertEquals(listOf(HfModel.parse("unsloth/Qwen3.5-0.8B-GGUF:Q4_K_M")), chattedWith)
    }

    @Test fun aModelOnThePhoneOffersAnotherSize() {
        onPhone("unsloth/Qwen3.5-0.8B-GGUF:Q4_K_M")
        discovery.repoFiles["unsloth/Qwen3.5-0.8B-GGUF"] = listOf(q3, q4, q8)
        showModels()
        waitFor("Qwen3.5 0.8B")
        compose.onNodeWithTag("library_model").performClick()
        compose.waitUntilAtLeastOneExists(hasTestTag("another_size"), 15_000)
        compose.onNodeWithTag("another_size").performClick()
        waitFor("Smaller")
        // the file on the phone is marked and can't be picked; another size is offered instead
        compose
            .onNode(
                hasText("On this phone", substring = true) and hasAnyAncestor(hasTestTag("tier_Recommended")),
                useUnmergedTree = true,
            ).assertIsDisplayed()
        compose.onNodeWithTag("get_button").assertIsDisplayed()
        shots.capture("another-size")
    }

    @Test fun aDownloadsCardFollowsItsDownloadAndOffersNoGet() {
        val gemma = HfModel.parse("unsloth/gemma-3-4b-it-qat-GGUF:Q4_K_M")
        app.container.models.get(gemma)
        compose.waitUntil(10_000) { scheduler.enqueued.isNotEmpty() }
        hub.advance(gemma)
        showModels()
        waitFor("Downloading")
        compose.onNodeWithTag("download_row").performTouchInput { click(percentOffset(0.5f, 0.1f)) }
        compose.waitUntilAtLeastOneExists(hasTestTag("sheet_download"), 15_000)
        // the ring panel alone describes the download: no state line
        waitFor("Downloading · Q4_K_M", substring = true)
        compose.onAllNodesWithTag("sheet_state").assertCountEquals(0)
        compose.onAllNodesWithTag("get_button").assertCountEquals(0)
        compose
            .onNode(hasContentDescription("Pause") and hasAnyAncestor(hasTestTag("sheet_download")))
            .assertIsDisplayed()
        shots.capture("card-downloading")
    }

    @Test fun aFailedDownloadsCardSaysWhyWithoutADownloadingHeading() {
        val gemma = HfModel.parse("unsloth/gemma-3-4b-it-qat-GGUF:Q4_K_M")
        app.container.models.get(gemma)
        compose.waitUntil(10_000) { scheduler.enqueued.isNotEmpty() }
        hub.refuse(gemma, TestHub.Refusal.Unauthorized)
        showModels()
        waitFor("This model needs a Hugging Face token.")
        compose.onNodeWithTag("download_row").performTouchInput { click(percentOffset(0.5f, 0.1f)) }
        compose.waitUntilAtLeastOneExists(hasTestTag("sheet_download"), 15_000)
        // the ring panel's Stopped: what stopped and why, with Try again and Cancel
        val panel = hasAnyAncestor(hasTestTag("sheet_download"))
        compose.onNode(hasText("Stopped · Q4_K_M") and panel, useUnmergedTree = true).assertIsDisplayed()
        compose.onNode(hasContentDescription("Try again") and panel).assertIsDisplayed()
        compose.onNode(hasContentDescription("Cancel") and panel).assertIsDisplayed()
        shots.capture("card-failed")
    }

    @Test fun aCancelThatCantStopTheWorkSaysSoOnTheCard() {
        val gemma = HfModel.parse("unsloth/gemma-3-4b-it-qat-GGUF:Q4_K_M")
        app.container.models.get(gemma)
        compose.waitUntil(10_000) { scheduler.enqueued.isNotEmpty() }
        hub.advance(gemma)
        showModels()
        waitFor("Downloading")
        compose.onNodeWithTag("download_row").performTouchInput { click(percentOffset(0.5f, 0.1f)) }
        compose.waitUntilAtLeastOneExists(hasTestTag("sheet_download"), 15_000)
        scheduler.cancelFailure = IllegalStateException("WorkManager's database is gone")
        compose.onNode(hasContentDescription("Cancel") and hasAnyAncestor(hasTestTag("sheet_download"))).performClick()
        // past 300 MB the card asks first
        if (compose.onAllNodesWithText("Cancel download").fetchSemanticsNodes().isNotEmpty()) {
            compose.onNodeWithText("Cancel download").performClick()
        }
        compose.waitUntilAtLeastOneExists(hasTestTag("sheet_problem"), 15_000)
        waitFor("Couldn't cancel the download of", substring = true)
    }

    @Test fun aCardFromThePhoneShowsTheOriginalModelsPageAndMaker() {
        hub.list("someone/Foo-1B-GGUF", downloads = 10, baseModel = "maker/Foo-1B")
        discovery.pages["maker/Foo-1B"] = "# Foo 1B\n\nThe original's own page."
        val key = HfModel.parse("someone/Foo-1B-GGUF:Q4_K_M")
        onPhone(key.toString())
        // the row "On this phone" opens: the model's key and name, no original and no maker
        val row = ModelEntry.ofKey(key, "Foo 1B")
        show(null, ThemeMode.Light) { ModelSheetRoute(app.container, row, {}, {}, onChatWith = {}) }
        waitFor("The original's own page.")
        // the repo is the page's source, at its end; the maker opens the quiet line under the name (D123)
        waitFor("Source: someone/Foo-1B-GGUF")
        compose.waitUntilAtLeastOneExists(hasContentDescription("maker", substring = true), 10_000)
    }

    @Test fun tappingADownloadOpensItsCard() {
        val gemma = HfModel.parse("unsloth/gemma-3-4b-it-qat-GGUF:Q4_K_M")
        app.container.models.get(gemma)
        compose.waitUntil(10_000) { scheduler.enqueued.isNotEmpty() }
        hub.advance(gemma)
        showModels()
        waitFor("Downloading")
        // the name's line: the ring's Pause and the ✕ in the footer keep their own taps
        compose.onNodeWithTag("download_row").performTouchInput { click(percentOffset(0.5f, 0.1f)) }
        compose.waitUntilAtLeastOneExists(hasTestTag("card_header"), 15_000)
    }

    @Test fun swipeToRemoveAsksFirst() {
        val swift = HfModel.parse("unsloth/Qwen3.5-0.8B-GGUF:Q4_K_M")
        onPhone(swift.toString())
        showModels()
        waitFor("Qwen3.5 0.8B")
        compose.onNodeWithTag("library_model").performTouchInput { swipeLeft() }
        waitFor("Remove Qwen3.5 0.8B?")
        val frees = "This frees ${Format.sizeInGb(sizeOf(swift.toString()))}"
        compose.onNodeWithText(frees, substring = true).assertIsDisplayed()
        assertTrue("nothing is deleted until confirmed", hub.onPhone(swift))
        compose.onNodeWithText("Keep").performClick()
        assertTrue(hub.onPhone(swift))
        compose.onNodeWithTag("library_model").performTouchInput { swipeLeft() }
        waitFor("Remove Qwen3.5 0.8B?")
        compose.onNodeWithText("Remove").performClick()
        compose.waitUntil(10_000) { !hub.onPhone(swift) }
    }

    @Test fun downloadTicksRecomposeOnlyTheDownloadingRowInTheirGroup() {
        val a = HfModel.parse("unsloth/Qwen3.5-0.8B-GGUF:Q4_K_M")
        val b = HfModel.parse("unsloth/Llama-3.2-3B-Instruct-GGUF:Q4_K_M")
        app.container.models.get(a)
        app.container.models.get(b)
        // one download at a time (D44): A runs, B waits its turn; both are listed under Downloading
        compose.waitUntil(10_000) { scheduler.enqueued == listOf(a) }
        hub.downloading(a)
        val counter = RecompositionCounter()
        showModels(counter)
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("download_row").fetchSemanticsNodes().size == 2 }
        // A starts moving (the fake restarts its worker, so A stops for a moment and B's "after" line follows it);
        // what is counted is A's ticks from there on, while A stays the one that runs
        hub.progressing(a, millis = TICK_MILLIS * 4)
        val done = { (hub.hub.progress(a).value as? DownloadState.Downloading)?.progress }
        compose.waitUntil(TICK_MILLIS * 8) { (done()?.bytesDone ?: 0L) > 0L }
        compose.waitForIdle()
        counter.reset()
        compose.waitUntil(TICK_MILLIS * 8) {
            compose.waitForIdle()
            val now = done() ?: return@waitUntil false
            now.bytesTotal > 0 && now.bytesDone * 2 >= now.bytesTotal
        }
        compose.waitForIdle()
        val rowOf = { key: HfModel -> "model-" + ModelEntry.downloading(key, null).groupKey }
        val waiting = counter.counts[rowOf(b)] ?: 0
        val running = counter.counts[rowOf(a)] ?: 0
        hub.stop(a)
        assertEquals("the waiting row never recomposed for A's ticks", 0, waiting)
        assertTrue("the downloading row did", running > 0)
    }

    private companion object {
        /** One step of a download the tests watch move: long enough for the screen to show each. */
        const val TICK_MILLIS = 600L
    }
}
