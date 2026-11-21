package com.suhel.llamabro.demo.journey

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.suhel.llamabro.demo.MainActivity
import com.suhel.llamabro.demo.testing.ScreenshotRule
import com.suhel.llamabro.demo.testing.TestAppRule
import com.suhel.llamabro.demo.testing.demoRules
import com.suhel.llamabro.hub.HfModel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.rules.RuleChain

/**
 * A journey starts the real activity over a fresh [World] and drives it as a person would: by what is on the screen
 * (its text, its description, its tags), never through a repository. [asksForNotifications] lets the system's own
 * permission dialog appear; [seenFirstRun] false starts at first run, the Models page's empty state.
 */
@OptIn(ExperimentalTestApi::class)
abstract class Journey(
    seenFirstRun: Boolean = true,
    asksForNotifications: Boolean = false,
    realDownloads: Boolean = false,
) {
    val world = World(realDownloads)
    val app = TestAppRule(config = { world.config(asksForNotifications) }, seenFirstRun = seenFirstRun)
    val compose = createAndroidComposeRule<MainActivity>()
    val shots = ScreenshotRule(compose)

    @get:Rule val rules: RuleChain = RuleChain.outerRule(demoRules(app)).around(compose).around(shots)

    /** The library lists a model once the worker says it arrived. */
    @Before fun connectDownloads() {
        world.downloads.onFinished = { app.container.models.changed() }
        world.downloads.hasToken = { runBlocking { app.container.tokenStore.read() } != null }
    }

    /** The fake hub's downloads stop and its folder goes. */
    @After fun closeWorld() = world.close()

    /** These models are on the phone before the journey begins, and the first is the one the last chat used. */
    fun onPhone(vararg models: HfModel) {
        models.forEach(world.downloads::finish)
        runBlocking { app.container.settings.setLastModel(models.first().toString()) }
        // the activity was already up and may have opened its chat before the last model was saved: open it again
        compose.activityRule.scenario.recreate()
        compose.waitForIdle()
    }

    fun see(text: String, substring: Boolean = false, timeoutMillis: Long = WAIT) =
        compose.waitUntilAtLeastOneExists(hasText(text, substring = substring), timeoutMillis)

    /** A download's ring says how far it is, past 0% (the fake hub's file arrives in quarters). */
    fun seeArrivingUnderWay() =
        compose.waitUntil(WAIT) {
            compose
                .onAllNodes(hasContentDescription("Arriving · ", substring = true))
                .fetchSemanticsNodes()
                .any { node -> !node.config[SemanticsProperties.ContentDescription].joinToString().contains("· 0%") }
        }

    fun seeDescription(text: String, timeoutMillis: Long = WAIT) =
        compose.waitUntilAtLeastOneExists(hasContentDescription(text, substring = true), timeoutMillis)

    fun seeTag(tag: String, timeoutMillis: Long = WAIT) =
        compose.waitUntilAtLeastOneExists(hasTestTag(tag), timeoutMillis)

    fun gone(text: String, substring: Boolean = false, timeoutMillis: Long = WAIT) =
        compose.waitUntil(timeoutMillis) {
            compose.onAllNodes(hasText(text, substring = substring)).fetchSemanticsNodes().isEmpty()
        }

    fun goneTag(tag: String, timeoutMillis: Long = WAIT) =
        compose.waitUntil(timeoutMillis) { compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isEmpty() }

    fun tap(text: String, substring: Boolean = false) {
        see(text, substring)
        compose.onNodeWithText(text, substring = substring).performClick()
    }

    fun tapTag(tag: String) {
        seeTag(tag)
        compose.onNodeWithTag(tag).performClick()
    }

    /**
     * On the Models page: [name]'s row, then its card's Get (the card names the quant and size, not the model). The
     * first Get on a fresh install opens a chat on it; a later one stays on the page.
     */
    fun getFromModels(name: String) {
        compose.waitUntilAtLeastOneExists(hasTestTag("model_row") and hasText(name, substring = true), WAIT)
        compose.onNode(hasTestTag("model_row") and hasText(name, substring = true)).performClick()
        tapTag("get_button")
    }

    fun tapDescription(description: String, substring: Boolean = false) {
        compose.waitUntilAtLeastOneExists(
            androidx.compose.ui.test
                .hasContentDescription(description, substring = substring),
            WAIT,
        )
        compose.onNodeWithContentDescription(description, substring = substring).performClick()
    }

    /** The Sidebar drawer's Models, whose label carries the count ("Models, 2 on this phone"). */
    fun tapDrawerModels() = tapDescription("Models, ", substring = true)

    /** Types into the chat's composer and sends. */
    fun send(text: String) {
        seeTag("composer")
        compose.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag("composer"))).performTextInput(text)
        tapDescription("Send")
    }

    /** A screenshot of this point of the journey, kept as evidence (`adb pull .../files/screens`). */
    fun shot(name: String) {
        compose.waitForIdle()
        shots.capture(name)
    }

    companion object {
        const val WAIT = 15_000L
    }
}
