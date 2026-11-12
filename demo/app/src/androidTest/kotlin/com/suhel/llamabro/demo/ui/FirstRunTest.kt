package com.suhel.llamabro.demo.ui

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import com.suhel.llamabro.DeviceKind
import com.suhel.llamabro.demo.MainActivity
import com.suhel.llamabro.demo.models.CachedEntry
import com.suhel.llamabro.demo.models.Catalog
import com.suhel.llamabro.demo.models.DownloadStatus
import com.suhel.llamabro.demo.models.FakeHubGateway
import com.suhel.llamabro.demo.models.FakeScheduler
import com.suhel.llamabro.demo.models.HubEstimate
import com.suhel.llamabro.demo.testing.FakeDiscovery
import com.suhel.llamabro.demo.testing.ScreenshotRule
import com.suhel.llamabro.demo.testing.TestAppRule
import com.suhel.llamabro.demo.testing.TestConfig
import com.suhel.llamabro.demo.testing.demoRules
import com.suhel.llamabro.demo.testing.withAccessibilityChecks
import com.suhel.llamabro.testing.FakeLlamaBro
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain

/**
 * First run on the phone, over a fake hub, engine and Hugging Face: the greeting, three picks, a pick's sheet,
 * and Get opening a chat that holds the first message. Runs in the real activity, as a user would meet it.
 */
@OptIn(ExperimentalTestApi::class)
class FirstRunTest {
    private val fake = FakeLlamaBro()
    private val hub = FakeHubGateway()
    private val scheduler = FakeScheduler()
    private val discovery = FakeDiscovery()
    private val app =
        TestAppRule(
            config = {
                TestConfig.fresh(
                    llamaBro = fake.llamaBro,
                    hub = hub,
                    scheduler = scheduler,
                    discovery = discovery,
                )
            },
            seenFirstRun = false,
        )
    private val compose = createAndroidComposeRule<MainActivity>()
    private val shots = ScreenshotRule(compose)

    @get:Rule val rules: RuleChain = RuleChain.outerRule(demoRules(app)).around(compose).around(shots)

    private val swift = Catalog.picks[0]

    private fun waitForFirstRun(checks: Boolean = true) {
        if (checks) compose.withAccessibilityChecks()
        compose.waitUntilAtLeastOneExists(hasTestTag("pick_card"), 10_000)
        // the window's open animation makes the accessibility check read buttons at about three quarters of their size (F101)
        compose.waitForIdle()
        Thread.sleep(900)
    }

    private fun waitFor(text: String, substring: Boolean = false) =
        compose.waitUntilAtLeastOneExists(hasText(text, substring = substring), 15_000)

    @Test fun firstRunShowsThreePicksWithoutIcons() {
        waitForFirstRun()
        compose.onAllNodesWithTag("pick_card").assertCountEquals(3)
        for (name in listOf(
            "Qwen3.5 0.8B",
            "Gemma 3 4B",
            "Qwen3.5 4B",
        )) {
            compose.onNodeWithText(name).assertIsDisplayed()
        }
        for (real in listOf(
            "Qwen3.5 0.8B",
            "Gemma 3 4B",
            "Qwen3.5 4B",
        )) {
            compose.onNodeWithText(real).assertIsDisplayed()
        }
        compose
            .onNodeWithText(
                "Pick a mind to talk to. It lives on this phone: private, and it works offline.",
            ).assertIsDisplayed()
        compose.onNodeWithText("PICKED FOR YOUR PHONE").assertIsDisplayed()
        // no icons: nothing inside a pick card is an image
        compose
            .onAllNodes(
                hasAnyAncestor(hasTestTag("pick_card")) and
                    SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Image),
                useUnmergedTree = true,
            ).assertCountEquals(0)
        shots.capture("light")
    }

    @Test fun greetingIsOneOfTheFourBands() {
        waitForFirstRun()
        val greetings = listOf("Good morning", "Good afternoon", "Good evening", "Hello")
        assertEquals(1, greetings.count { compose.onAllNodes(hasText(it)).fetchSemanticsNodes().isNotEmpty() })
    }

    @Test fun picksAreTintedBySize() {
        waitForFirstRun()
        // the fake phone has 8 GiB for models: Qwen3.5 0.8B runs great, Gemma 3 4B and Qwen3.5 4B run well
        compose.onNodeWithText("Runs great").assertIsDisplayed()
    }

    @Test fun whatsAModelOpensTwoLines() {
        waitForFirstRun()
        compose.onNodeWithText("What's a model?").performClick()
        compose.onNodeWithText("A model is the mind that answers you", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Bigger ones think better", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Got it").performClick()
        compose.onAllNodes(hasText("Bigger ones think better", substring = true)).assertCountEquals(0)
    }

    @Test fun firstRunSearchOpensGetModels() {
        // the keyboard hides most of the third pick, leaving a 4 dp strip of a card above it, which the accessibility
        // check rightly calls a tiny target; scrolled content behind a keyboard is normal, so the check starts on Get models
        waitForFirstRun(checks = false)
        compose.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag("first_run_search"))).performTextInput("qwen")
        compose.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag("first_run_search"))).performImeAction()
        compose.waitUntilAtLeastOneExists(hasTestTag("get_models"), 10_000)
        compose.withAccessibilityChecks()
        compose.waitUntil(10_000) { discovery.searches.contains("qwen") }
        compose.onNodeWithText("qwen").assertIsDisplayed() // the query came along
    }

    @Test fun pickSheetShowsFourFacts() {
        waitForFirstRun()
        compose.onNodeWithText("Qwen3.5 0.8B").performClick()
        waitFor("Get Qwen3.5 0.8B")
        for (title in listOf(
            "Thinking",
            "Images",
            "Memory",
            "Download",
        )) {
            compose.onNodeWithText(title).assertIsDisplayed()
        }
        compose.onNodeWithText("From Hugging Face").assertIsDisplayed()
        compose.onNodeWithText("by Alibaba Qwen").assertIsDisplayed()
        Thread.sleep(700)
        shots.capture("pick_sheet")
    }

    @Test fun pickSheetFitIsQuickWhenOffline() {
        // no estimate for Qwen3.5 0.8B in the fake hub: it fails as an offline phone does, so the weights' size is shown
        waitForFirstRun()
        compose.onNodeWithText("Qwen3.5 0.8B").performClick()
        waitFor("Uses about 0.7 GB")
    }

    @Test fun pickSheetFitIsRealWhenOnline() {
        hub.estimates[swift.key] = HubEstimate(532_517_120, 207_346_528, true, 32_768, 900_000_000, DeviceKind.Npu)
        waitForFirstRun()
        compose.onNodeWithText("Qwen3.5 0.8B").performClick()
        waitFor("Uses about 0.9 GB") // the SDK's projection replaced the quick figure
    }

    @Test fun getOpensTheChatAndHoldsAMessage() {
        hub.status(swift.key).value = DownloadStatus.Downloading(100_000_000, 739_000_000, 9_000_000, 70)
        fake.reply {
            content("Hello from Qwen3.5 0.8B")
            timings(promptTokens = 5, predictedTokens = 5)
        }
        waitForFirstRun()
        compose.onNodeWithText("Qwen3.5 0.8B").performClick()
        waitFor("Get Qwen3.5 0.8B")
        compose.onNodeWithText("Get Qwen3.5 0.8B").performClick()
        compose.waitUntilAtLeastOneExists(hasTestTag("chat_screen"), 15_000)
        assertEquals("the download was started", listOf(swift.key), scheduler.enqueued)

        // type during the download: the message is held, with the wait line
        compose.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag("composer"))).performTextInput("hello there")
        compose.onNodeWithContentDescription("Send").performClick()
        waitFor("Qwen3.5 0.8B will answer the moment it's here")

        // the model arrives: the held message is answered
        hub.cachedEntries += CachedEntry(swift.key, 739_000_000)
        app.container.models.changed()
        hub.status(swift.key).value = DownloadStatus.Complete
        waitFor("Hello from Qwen3.5 0.8B")
    }

    @Test fun getMarksFirstRunSeen() {
        waitForFirstRun()
        compose.onNodeWithText("Qwen3.5 0.8B").performClick()
        waitFor("Get Qwen3.5 0.8B")
        compose.onNodeWithText("Get Qwen3.5 0.8B").performClick()
        compose.waitUntilAtLeastOneExists(hasTestTag("chat_screen"), 15_000)
        assertTrue(
            runBlocking {
                app.container.settings.settings
                    .first()
                    .seenFirstRun
            },
        )
        assertFalse(
            "first run left the screen",
            compose.onAllNodes(hasTestTag("first_run")).fetchSemanticsNodes().isNotEmpty(),
        )
    }
}
