package com.suhel.llamabro.demo.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import com.suhel.llamabro.demo.MainActivity
import com.suhel.llamabro.demo.models.Catalog
import com.suhel.llamabro.demo.models.FakeScheduler
import com.suhel.llamabro.demo.models.TestHub
import com.suhel.llamabro.demo.testing.FakeDiscovery
import com.suhel.llamabro.demo.testing.ScreenshotRule
import com.suhel.llamabro.demo.testing.TestAppRule
import com.suhel.llamabro.demo.testing.TestConfig
import com.suhel.llamabro.demo.testing.demoRules
import com.suhel.llamabro.demo.testing.settleWindow
import com.suhel.llamabro.demo.testing.withAccessibilityChecks
import com.suhel.llamabro.demo.ui.models.MODELS_INTRO
import com.suhel.llamabro.testing.FakeLlamaBro
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain

/**
 * First run on the phone, the Models page's empty state (docs/design/models-page.md §2, §7), over a fake hub, engine
 * and Hugging Face: a fresh install starts on Models with the intro, "What's a model?" teaches the fit symbols, and the
 * first Get opens a chat that holds the first message. Runs in the real activity, as a person would meet it.
 */
@OptIn(ExperimentalTestApi::class)
class FirstRunTest {
    private val fake = FakeLlamaBro()
    private val hub = TestHub()
    private val scheduler = FakeScheduler()
    private val discovery = FakeDiscovery(hub)
    private val app =
        TestAppRule(
            config = {
                TestConfig.fresh(
                    llamaBro = fake.llamaBro,
                    hub = hub.hub,
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

    @After fun closeHub() = hub.close()

    private fun waitForModels(checks: Boolean = true) {
        if (checks) compose.withAccessibilityChecks()
        compose.waitUntilAtLeastOneExists(hasText(MODELS_INTRO), 10_000)
        compose.settleWindow()
    }

    private fun waitFor(text: String, substring: Boolean = false) =
        compose.waitUntilAtLeastOneExists(hasText(text, substring = substring), 15_000)

    private fun get(name: String) {
        compose.onNode(hasTestTag("model_row") and hasText(name, substring = true)).performClick()
        compose.waitUntilAtLeastOneExists(hasTestTag("get_button"), 15_000)
        compose.onNodeWithTag("get_button").performClick()
    }

    @Test fun aFreshInstallStartsOnModelsWithTheIntro() {
        waitForModels()
        compose.onNodeWithTag("models").assertIsDisplayed()
        compose.onNodeWithText(MODELS_INTRO).assertIsDisplayed()
        compose.onNodeWithText("Suggested for this phone").assertIsDisplayed()
        compose.onNodeWithText("Qwen3.5 0.8B", substring = true).assertIsDisplayed()
        // nothing is under the page, so there is no Back
        compose.onAllNodes(hasContentDescription("Back")).assertCountEquals(0)
        shots.capture("light")
    }

    @Test fun suggestionsShowHowTheyRunAsSymbols() {
        waitForModels()
        // the fake phone has 8 GiB for models: Qwen3.5 0.8B runs great; the words are the symbol's description
        // (more than one suggestion may run great: the first is enough to see the symbol speak)
        compose.onAllNodes(hasContentDescription("Runs great")).onFirst().assertIsDisplayed()
    }

    @Test fun whatsAModelTeachesTheFiveSymbols() {
        waitForModels()
        compose.onNodeWithText("What's a model?").performClick()
        compose.onNodeWithText("A model is the mind that answers you", substring = true).assertIsDisplayed()
        for (words in listOf("Runs great", "Runs well", "Slow here", "Too big", "Can't chat")) {
            compose.onNode(hasText(words) and hasAnyAncestor(isDialog())).assertIsDisplayed()
        }
        compose.onNodeWithText("Got it").performClick()
        compose.onAllNodes(hasText("Bigger ones think better", substring = true)).assertCountEquals(0)
    }

    @Test fun searchingOnAFreshInstallStaysOnModelsAndKeepsFirstRun() {
        waitForModels(checks = false)
        val field = hasSetTextAction() and hasAnyAncestor(hasTestTag("models_search"))
        compose.onNode(field).performTextInput("qwen")
        compose.onNode(field).performImeAction()
        compose.withAccessibilityChecks()
        compose.waitUntil(10_000) { discovery.searches.contains("qwen") }
        compose.onNodeWithTag("models").assertIsDisplayed()
        assertFalse(
            "searching no longer marks first run seen (models-page design §7)",
            runBlocking {
                app.container.settings.settings
                    .first()
                    .seenFirstRun
            },
        )
    }

    @Test fun theCardsGetIsTheModelAndItsEncoder() {
        // the Get names the quant and the whole download, Qwen3.5 0.8B's weights and its image encoder (D101), from
        // the catalog's sizes: no listing here (the fake Hub has none), as on a phone offline
        waitForModels()
        compose.onNode(hasTestTag("model_row") and hasText("Qwen3.5 0.8B", substring = true)).performClick()
        compose.waitUntilAtLeastOneExists(
            hasTestTag("get_button") and hasText("Get Q4_K_M · 0.7 GB", substring = true),
            15_000,
        )
        Thread.sleep(700)
        shots.capture("card")
    }

    @Test fun getOpensTheChatAndHoldsAMessage() {
        fake.reply {
            content("Hello from Qwen3.5 0.8B")
            timings(promptTokens = 5, predictedTokens = 5)
        }
        waitForModels()
        get("Qwen3.5 0.8B")
        compose.waitUntilAtLeastOneExists(hasTestTag("chat_screen"), 15_000)
        compose.waitUntil(10_000) { scheduler.enqueued == listOf(swift.key) } // the download was started
        hub.downloading(swift.key) // and the worker, here the fake hub, has it under way

        // type during the download: the message is held, with the wait line
        compose.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag("composer"))).performTextInput("hello there")
        compose.onNodeWithContentDescription("Send").performClick()
        waitFor("Qwen3.5 0.8B will answer the moment it's here")

        // the model arrives: the held message is answered
        hub.install(swift.key)
        app.container.models.changed()
        waitFor("Hello from Qwen3.5 0.8B")
    }

    @Test fun getMarksFirstRunSeen() {
        waitForModels()
        get("Qwen3.5 0.8B")
        compose.waitUntilAtLeastOneExists(hasTestTag("chat_screen"), 15_000)
        val settings =
            runBlocking {
                app.container.settings.settings
                    .first()
            }
        assertTrue(settings.seenFirstRun)
        assertEquals("new chats use the model", swift.key.toString(), settings.lastModel)
        assertFalse(
            "the page gave way to the chat",
            compose.onAllNodes(hasTestTag("models")).fetchSemanticsNodes().isNotEmpty(),
        )
    }
}
