package com.suhel.llamabro.demo.ui

import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import com.suhel.llamabro.DeviceKind
import com.suhel.llamabro.demo.designsystem.testing.LocalRecompositionCounter
import com.suhel.llamabro.demo.designsystem.testing.RecompositionCounter
import com.suhel.llamabro.demo.designsystem.theme.DemoTheme
import com.suhel.llamabro.demo.designsystem.theme.LocalDemoColors
import com.suhel.llamabro.demo.designsystem.theme.ThemeMode
import com.suhel.llamabro.demo.models.CachedEntry
import com.suhel.llamabro.demo.models.DownloadStatus
import com.suhel.llamabro.demo.models.FakeHubGateway
import com.suhel.llamabro.demo.models.FakeScheduler
import com.suhel.llamabro.demo.models.HubEstimate
import com.suhel.llamabro.demo.models.RepoFile
import com.suhel.llamabro.demo.models.SearchFailed
import com.suhel.llamabro.demo.models.SearchModel
import com.suhel.llamabro.demo.testing.FakeDiscovery
import com.suhel.llamabro.demo.testing.ScreenshotRule
import com.suhel.llamabro.demo.testing.TestAppRule
import com.suhel.llamabro.demo.testing.TestConfig
import com.suhel.llamabro.demo.testing.demoRules
import com.suhel.llamabro.demo.testing.withAccessibilityChecks
import com.suhel.llamabro.demo.ui.models.GetModelsRoute
import com.suhel.llamabro.demo.ui.models.LibraryRoute
import com.suhel.llamabro.hub.HfModel
import com.suhel.llamabro.testing.FakeLlamaBro
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain

/**
 * Finding models and the library on the phone (T10): Get models, a model's sheet, downloads on their rows, and the
 * library. The screens run bare in an activity, over a fake hub and a fake Hugging Face, so a test can supply the
 * recomposition counter and stays fast and exact.
 */
@OptIn(ExperimentalTestApi::class)
class ModelsTest {
    private val hub = FakeHubGateway()
    private val scheduler = FakeScheduler()
    private val discovery = FakeDiscovery()
    private val app =
        TestAppRule(
            config = {
                TestConfig.fresh(
                    llamaBro = FakeLlamaBro().llamaBro,
                    hub = hub,
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
    private val bf16 = RepoFile("Qwen3.5-4B-BF16.gguf", "BF16", 8_400_000_000)
    private val phi =
        SearchModel(
            "phi-4-mini",
            "Phi 4 mini",
            "bartowski/phi-4-mini-GGUF",
            "bartowski",
            1_200_000,
            false,
            "Balanced, a good all-rounder",
            null,
        )
    private val vision =
        SearchModel(
            "llava-7b",
            "Llava 7B",
            "someone/llava-7b-GGUF",
            "someone",
            412_000,
            true,
            "Smarter, but heavy for a phone",
            null,
        )

    private fun fitFor(file: RepoFile, repo: String, fits: Boolean = true) =
        hub.estimates.put(
            HfModel.parse("$repo:${file.quant}"),
            HubEstimate(
                file.bytes,
                0,
                fits,
                if (fits) 32_768 else null,
                file.bytes + 300_000_000,
                DeviceKind.Npu,
            ),
        )

    private fun seedRepo(repo: String = "bartowski/phi-4-mini-GGUF") {
        discovery.repoFiles[repo] = listOf(q3, q4, q8, bf16)
        for (f in listOf(q3, q4, q8)) fitFor(f, repo)
        fitFor(bf16, repo, fits = false)
    }

    private fun showGetModels(
        counter: RecompositionCounter? = null,
        query: String = "",
        theme: ThemeMode = ThemeMode.Light,
    ) {
        compose.withAccessibilityChecks()
        compose.runOnUiThread { compose.activity.enableEdgeToEdge() }
        compose.setContent {
            DemoTheme(theme) {
                CompositionLocalProvider(LocalRecompositionCounter provides counter) {
                    Box(
                        Modifier.background(LocalDemoColors.current.canvas),
                    ) { GetModelsRoute(app.container, query, onBack = {}) }
                }
            }
        }
        settleWindow()
    }

    private fun showLibrary(counter: RecompositionCounter? = null, theme: ThemeMode = ThemeMode.Light) {
        compose.withAccessibilityChecks()
        compose.runOnUiThread { compose.activity.enableEdgeToEdge() }
        compose.setContent {
            DemoTheme(theme) {
                CompositionLocalProvider(LocalRecompositionCounter provides counter) {
                    Box(
                        Modifier.background(LocalDemoColors.current.canvas),
                    ) { LibraryRoute(app.container, onBack = {}, onGetMore = {}) }
                }
            }
        }
        settleWindow()
    }

    /** The window's open animation makes the accessibility check read buttons smaller than they are (F101). */
    private fun settleWindow() {
        compose.waitForIdle()
        Thread.sleep(900)
    }

    private fun settleSheet() {
        compose.waitForIdle()
        Thread.sleep(700)
    }

    private fun search(text: String) {
        compose.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag("models_search"))).performTextInput(text)
    }

    private fun waitFor(text: String, substring: Boolean = false) =
        compose.waitUntilAtLeastOneExists(hasText(text, substring = substring), 15_000)

    private fun openPhi() {
        discovery.results = { listOf(phi, vision) }
        seedRepo()
        showGetModels()
        search("phi")
        waitFor("Phi 4 mini")
        compose.onNodeWithText("Phi 4 mini").performClick()
        waitFor("Smaller")
        settleSheet()
    }

    // ---- Get models ----

    @Test fun getModelsShowsFiveSuggestions() {
        showGetModels()
        compose.onNodeWithText("SUGGESTED FOR THIS PHONE").assertIsDisplayed()
        compose.onAllNodesWithTag("model_row").assertCountEquals(5)
        // every model is called by its own name
        for (name in listOf("Qwen3.5 0.8B", "Gemma 3 4B", "Qwen3.5 4B", "Llama 3.2 3B", "Qwen3.5 9B")) {
            compose.onNodeWithText(name).assertIsDisplayed()
        }
        shots.capture("suggestions")
    }

    @Test fun resultsCollapseByModel() {
        discovery.results = { listOf(phi, vision) }
        showGetModels()
        search("model")
        waitFor("Phi 4 mini")
        compose.onAllNodesWithTag("model_row").assertCountEquals(2)
        compose.onNodeWithText("Sees images", substring = true).assertIsDisplayed()
        compose.onNodeWithText("↓ 1.2M", substring = true).assertIsDisplayed()
        shots.capture("results")
    }

    @Test fun noResultsLine() {
        showGetModels()
        search("zzzz")
        waitFor("No models match. Try a shorter name.")
    }

    @Test fun searchErrorShowsRetry() {
        discovery.searchFailure = SearchFailed("Hugging Face answered 503", retryable = true)
        showGetModels()
        search("qwen")
        waitFor("Try again")
        discovery.searchFailure = null
        discovery.results = { listOf(phi) }
        compose.onNodeWithText("Try again").performClick()
        waitFor("Phi 4 mini")
    }

    @Test fun offlineKeepsSuggestionsAndSaysSo() {
        discovery.searchFailure = FakeDiscovery.offline()
        showGetModels()
        search("qwen")
        waitFor("You're offline. These are suggested for this phone.")
        // the suggestions are kept: the rows above the keyboard are on screen (the rest are a scroll away)
        assertTrue(
            "suggestions are listed",
            compose.onAllNodesWithTag("model_row").fetchSemanticsNodes().size >= 3,
        )
        shots.capture("offline")
    }

    @Test fun aQueryFromFirstRunIsSearchedOnArrival() {
        discovery.results = { listOf(phi) }
        showGetModels(query = "phi")
        waitFor("Phi 4 mini")
        assertTrue(discovery.searches.contains("phi"))
    }

    // ---- the model's sheet ----

    @Test fun basicHasNoQuantNames() {
        openPhi()
        for (word in listOf(
            "Smaller",
            "Recommended",
            "Best quality",
            "A little less sharp",
            "The best balance",
            "Near-original, slower",
        )) {
            compose.onNodeWithText(word).assertIsDisplayed()
        }
        for (quant in listOf("Q3_K_M", "Q4_K_M", "Q8_0")) compose.onAllNodesWithText(quant).assertCountEquals(0)
        compose.onNodeWithText("bartowski/phi-4-mini-GGUF").assertIsDisplayed() // the repo under the name
        shots.capture("basic")
    }

    @Test fun buttonNamesModelOrQuant() {
        openPhi()
        waitFor("Get Phi 4 mini · about", substring = true)
        compose.onNodeWithText("Advanced").performClick()
        waitFor("Get Q4_K_M · 2.7 GB")
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

    @Test fun anEstimateThatDoesNotFitTintsTooBig() {
        openPhi()
        compose.onNodeWithText("Advanced").performClick()
        waitFor("Too big")
    }

    @Test fun sheetFailureOffersRetry() {
        discovery.results = { listOf(phi) }
        showGetModels()
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
        waitFor("Get Phi 4 mini", substring = true)
        compose.onNodeWithTag("get_button").performClick()
        compose.waitUntil(10_000) { scheduler.enqueued.isNotEmpty() }
        val key = scheduler.enqueued.single()
        hub.status(key).value = DownloadStatus.Downloading(310_000_000, 740_000_000, 9_800_000, 40)
        waitFor("Downloading · 41%")
        waitFor("about 40 s")
    }

    @Test fun progressTicksRecomposeOnlyTheDownloadingRow() {
        discovery.results = { listOf(phi, vision) }
        seedRepo()
        val counter = RecompositionCounter()
        showGetModels(counter)
        search("model")
        waitFor("Phi 4 mini")
        compose.onNodeWithText("Phi 4 mini").performClick()
        waitFor("Smaller")
        settleSheet()
        compose.onNodeWithTag("get_button").performClick()
        compose.waitUntil(10_000) { scheduler.enqueued.isNotEmpty() }
        val key = scheduler.enqueued.single()
        compose.waitForIdle()
        counter.reset()
        for (i in 1..30) {
            hub.status(key).value = DownloadStatus.Downloading(i * 20_000_000L, 740_000_000, 9_800_000, 40)
            compose.waitForIdle()
        }
        assertEquals("the other row never recomposed", 0, counter.counts["model-llava-7b"] ?: 0)
        assertTrue("the downloading row did", (counter.counts["model-phi-4-mini"] ?: 0) > 0)
    }

    @Test fun aLateEstimateRecomposesOnlyItsRow() {
        discovery.results = { listOf(phi, vision) }
        discovery.repoFiles["bartowski/phi-4-mini-GGUF"] = listOf(q3, q4, q8)
        discovery.repoFiles["someone/llava-7b-GGUF"] = listOf(q3, q4, q8)
        val gate = CompletableDeferred<Unit>()
        discovery.fitGates["someone/llava-7b-GGUF"] = gate
        val counter = RecompositionCounter()
        showGetModels(counter)
        search("model")
        waitFor("Phi 4 mini")
        compose.waitUntil(10_000) { (counter.counts["model-phi-4-mini"] ?: 0) >= 1 }
        compose.waitForIdle()
        counter.reset()
        gate.complete(Unit)
        compose.waitUntil(10_000) { (counter.counts["model-llava-7b"] ?: 0) >= 1 }
        compose.waitForIdle()
        assertEquals(
            "the row whose estimate was waiting did not touch the other",
            0,
            counter.counts["model-phi-4-mini"] ?: 0,
        )
    }

    // ---- the library ----

    private fun onPhone(vararg entries: Pair<String, Long>) {
        for ((ref, size) in entries) hub.cachedEntries += CachedEntry(HfModel.parse(ref), size)
        app.container.models.changed()
    }

    @Test fun storageBarShowsUsedAndFree() {
        onPhone("unsloth/Qwen3.5-0.8B-GGUF:Q4_K_M" to 740_000_000L, "unsloth/Qwen3.5-4B-GGUF:Q4_K_M" to 3_400_000_000L)
        showLibrary()
        waitFor("4.1 GB used", substring = true)
        compose.onNodeWithText(" free", substring = true).assertIsDisplayed()
        compose.onNodeWithTag("storage").assertIsDisplayed()
        shots.capture("library")
    }

    @Test fun libraryShowsModelsWithNamesAndSizes() {
        onPhone("unsloth/Qwen3.5-0.8B-GGUF:Q4_K_M" to 740_000_000L)
        showLibrary()
        waitFor("Qwen3.5 0.8B")
        compose.onNodeWithText("0.7 GB").assertIsDisplayed()
        compose.onNodeWithText("Get more models").assertIsDisplayed()
    }

    @Test fun libraryShowsDownloadingSection() {
        val key = HfModel.parse("unsloth/Qwen3.5-0.8B-GGUF:Q4_K_M")
        hub.status(key).value = DownloadStatus.Downloading(310_000_000, 740_000_000, 9_800_000, 40)
        app.container.models.get(key)
        showLibrary()
        waitFor("DOWNLOADING")
        compose.onNodeWithText("Pause").assertIsDisplayed()
        compose.onNodeWithText("310 MB of 740 MB", substring = true).assertIsDisplayed()
        shots.capture("downloading")
    }

    @Test fun swipeToRemoveAsksFirst() {
        onPhone("unsloth/Qwen3.5-0.8B-GGUF:Q4_K_M" to 740_000_000L)
        showLibrary()
        waitFor("Qwen3.5 0.8B")
        compose.onNodeWithTag("library_model").performTouchInput { swipeLeft() }
        waitFor("Remove Qwen3.5 0.8B?")
        compose.onNodeWithText("This frees 0.7 GB", substring = true).assertIsDisplayed()
        assertTrue("nothing is deleted until confirmed", hub.deleted.isEmpty())
        compose.onNodeWithText("Keep").performClick()
        assertTrue(hub.deleted.isEmpty())
        compose.onNodeWithTag("library_model").performTouchInput { swipeLeft() }
        waitFor("Remove Qwen3.5 0.8B?")
        compose.onNodeWithText("Remove").performClick()
        compose.waitUntil(10_000) { hub.deleted.isNotEmpty() }
        assertEquals(listOf(HfModel.parse("unsloth/Qwen3.5-0.8B-GGUF:Q4_K_M")), hub.deleted)
    }

    @Test fun emptyLibrarySaysSo() {
        showLibrary()
        waitFor("No models yet")
    }

    @Test fun downloadTicksRecomposeOnlyTheDownloadingRowInTheLibrary() {
        val a = HfModel.parse("unsloth/Qwen3.5-0.8B-GGUF:Q4_K_M")
        val b = HfModel.parse("unsloth/Llama-3.2-3B-Instruct-GGUF:Q4_K_M")
        hub.status(a).value = DownloadStatus.Downloading(1, 740_000_000, 9_800_000, 40)
        hub.status(b).value = DownloadStatus.Downloading(1, 2_000_000_000, 9_800_000, 200)
        app.container.models.get(a)
        app.container.models.get(b)
        val counter = RecompositionCounter()
        showLibrary(counter)
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("download_row").fetchSemanticsNodes().size == 2 }
        compose.waitForIdle()
        counter.reset()
        for (i in 1..30) {
            hub.status(a).value = DownloadStatus.Downloading(i * 20_000_000L, 740_000_000, 9_800_000, 40)
            compose.waitForIdle()
        }
        assertEquals(0, counter.counts["download-$b"] ?: 0)
        assertTrue((counter.counts["download-$a"] ?: 0) > 0)
    }
}
