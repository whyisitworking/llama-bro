package com.suhel.llamabro.demo.ui

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.junit4.accessibility.disableAccessibilityChecks
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ActivityScenario
import com.suhel.llamabro.demo.MainActivity
import com.suhel.llamabro.demo.data.AppLayout
import com.suhel.llamabro.demo.data.MessageKind
import com.suhel.llamabro.demo.data.NewMessage
import com.suhel.llamabro.demo.data.settings.Theme
import com.suhel.llamabro.demo.models.TestHub
import com.suhel.llamabro.demo.testing.TestAppRule
import com.suhel.llamabro.demo.testing.TestConfig
import com.suhel.llamabro.demo.testing.demoRules
import com.suhel.llamabro.demo.testing.withAccessibilityChecks
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain

/**
 * The shell on the phone: both layouts, the drawer, the chat list, settings. Needs the phone; written in
 * and run with `demo/scripts/check.sh --device`.
 */
@OptIn(ExperimentalTestApi::class)
class ShellTest {
    /**
     * The fake hub: the real one reads the app's own model folder, which on a phone holds whatever models are on it,
     * so "nothing downloaded in a fresh app" held only on an empty phone (the drawer said "Models, 3 on this phone").
     */
    private val hub = TestHub()
    private val app = TestAppRule(config = { TestConfig.fresh(hub = hub.hub) })
    private val compose = createEmptyComposeRule()

    @get:Rule val rules: RuleChain = RuleChain.outerRule(demoRules(app)).around(compose)

    /** The app, launched by [waitForShell] once the test has set what it starts from (its layout, its chats). */
    private lateinit var scenario: ActivityScenario<MainActivity>

    @After fun closeApp() {
        if (::scenario.isInitialized) scenario.close()
        hub.close()
    }

    private val swift = "unsloth/Qwen3.5-0.8B-GGUF:Q4_K_M"

    private fun seedChat(title: String, last: String = "hello", fromUser: Boolean = true): Long =
        runBlocking {
            val id = app.container.chats.createChat(swift)
            app.container.chats
                .append(
                    id,
                    NewMessage(
                        if (fromUser) MessageKind.User else MessageKind.Assistant,
                        last,
                        modelKey =
                            swift
                                .takeIf {
                                    !fromUser
                                },
                    ),
                )
            app.container.chats.rename(id, title)
            id
        }

    private fun setLayout(layout: AppLayout) = runBlocking { app.container.settings.setLayout(layout) }

    private fun waitForShell() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.withAccessibilityChecks()
        compose.waitUntilAtLeastOneExists(
            androidx.compose.ui.test
                .hasTestTag("shell_root"),
            10_000,
        )
    }

    @Test fun sidebarIsTheDefault() {
        waitForShell()
        compose.waitUntilAtLeastOneExists(hasText("What's on your mind?"), 10_000)
        compose.onNodeWithText("What's on your mind?").assertIsDisplayed()
    }

    @Test fun switchingToChatListShowsChatsHome() {
        seedChat("Portfolio bio")
        setLayout(AppLayout.ChatList)
        waitForShell()
        compose.waitUntilAtLeastOneExists(hasText("Search chats"), 10_000)
        compose.onNodeWithText("Portfolio bio").assertIsDisplayed()
        compose.onNodeWithText("You: hello").assertIsDisplayed()
    }

    @Test fun newChatFabOpensAnEmptyChat() {
        setLayout(AppLayout.ChatList)
        waitForShell()
        compose.waitUntilAtLeastOneExists(hasText("New chat"), 10_000)
        compose.onAllNodesWithText("What's on your mind?").assertCountEquals(0) // the chats' home, no chat open
        compose.onNodeWithText("New chat").performClick()
        compose.waitUntilAtLeastOneExists(hasText("What's on your mind?"), 10_000)
    }

    @Test fun chatListOpensAChatAndBackReturns() {
        seedChat("Trip plan")
        setLayout(AppLayout.ChatList)
        waitForShell()
        compose.waitUntilAtLeastOneExists(hasText("Trip plan"), 10_000)
        compose.onNodeWithText("Trip plan").performClick()
        compose.waitUntilAtLeastOneExists(hasText("hello"), 10_000) // the chat's own message, on the chat screen
        compose.onNodeWithContentDescription("Back").performClick()
        compose.waitUntilAtLeastOneExists(hasText("Search chats"), 10_000)
    }

    @Test fun layoutChoiceSurvivesRestart() {
        setLayout(AppLayout.ChatList)
        waitForShell()
        compose.waitUntilAtLeastOneExists(hasText("Search chats"), 10_000)
        scenario.recreate()
        compose.waitUntilAtLeastOneExists(hasText("Search chats"), 10_000)
    }

    @Test fun settingsEveryRowOpensItsScreen() {
        setLayout(AppLayout.ChatList)
        waitForShell()
        compose.waitUntilAtLeastOneExists(hasText("Settings"), 10_000)
        compose.onNodeWithText("Settings").performClick()
        for ((row, title) in listOf(
            "Appearance" to "Layout",
            "Custom instructions" to "About me",
            "Default model" to "Last used",
            "Hugging Face token" to "Paste your token",
            "Sampling" to "Models on this phone",
            "Voice" to "Caption what you say",
            "Memory" to "After you leave the app",
            "About" to "Licences",
        )) {
            compose.waitUntilAtLeastOneExists(hasText(row), 10_000)
            // the home is taller than a small screen: scroll to the row first
            compose.onNodeWithText(row).performScrollTo()
            if (row == "About") scrollToTheEnd()
            compose.onNodeWithText(row).performClick()
            compose.waitUntilAtLeastOneExists(hasText(title, ignoreCase = true), 10_000)
            compose.onNodeWithContentDescription("Back").performClick()
        }
    }

    @Test fun tokenSavedAndRemoved() {
        setLayout(AppLayout.ChatList)
        waitForShell()
        compose.waitUntilAtLeastOneExists(hasText("Settings"), 10_000)
        compose.onNodeWithText("Settings").performClick()
        compose.onNodeWithText("Hugging Face token").performClick()
        compose
            .onNode(
                hasSetTextAction() and hasAnyAncestor(hasTestTag("token_field")),
            ).performTextInput("hf_" + "a".repeat(34))
        compose.onNodeWithTag("token_save").performClick()
        compose.waitUntilAtLeastOneExists(hasText("Saved privately on this phone"), 10_000)
        compose.onNodeWithTag("token_remove").performClick()
        compose.waitUntilAtLeastOneExists(hasText("Paste your token"), 10_000)
    }

    @Test fun themeChoiceApplies() {
        runBlocking { app.container.settings.setTheme(Theme.Dark) }
        waitForShell()
        compose.onNodeWithTag("shell_root").assertIsDisplayed()
    }

    /** Runs [action] with the accessibility checks off: they fire mid-animation, with the moving surface clipped. */
    private fun whileItMoves(action: () -> Unit) {
        compose.disableAccessibilityChecks()
        action()
        compose.waitForIdle()
        compose.withAccessibilityChecks()
    }

    private fun openDrawer() {
        compose.waitUntilAtLeastOneExists(hasContentDescription("Chats"), 10_000)
        // The check runs right after the click, while the drawer still slides over the menu button and clips it to
        // 45 dp: that is the animation, not the button (48 dp). It is checked again once the drawer has settled.
        whileItMoves {
            compose.onNodeWithContentDescription("Chats").performClick()
            compose.waitUntilAtLeastOneExists(hasTestTag("drawer"), 10_000)
            // the drawer is in the tree as soon as it starts to slide in, its start edge off the screen: on the phone
            // a check of the icons at its foot ran while Models, at the start, was still off it (New chat, at the
            // end, was already on). Wait until it has slid all the way in.
            compose.waitUntil(10_000) {
                compose
                    .onAllNodes(hasTestTag("drawer"))
                    .fetchSemanticsNodes()
                    .singleOrNull()
                    ?.boundsInRoot
                    ?.left
                    ?.let { it >= 0f } == true
            }
        }
    }

    @Test fun anEmptyDrawerSaysNoChatsYet() {
        waitForShell()
        openDrawer()
        compose
            .onNode(hasText("No chats yet. Start one.") and hasAnyAncestor(hasTestTag("drawer")))
            .assertIsDisplayed()
    }

    @Test fun drawerShowsModelsCountAndGroups() {
        seedChat("Portfolio bio")
        waitForShell()
        openDrawer()

        // New chat is an icon at the drawer's head, Models and Settings labelled rows at its foot, each one node whose
        // spoken label names it (and the chat's top bar has a New chat of its own)
        fun inDrawer(description: String) = hasContentDescription(description) and hasAnyAncestor(hasTestTag("drawer"))
        compose.onNode(inDrawer("New chat")).assertIsDisplayed()
        compose.onNode(inDrawer("Models, 0 on this phone")).assertIsDisplayed() // nothing downloaded in a fresh app
        compose.onNode(inDrawer("Settings")).assertIsDisplayed()
        // ...and a screen reader reaches them before the chats
        compose.onNodeWithTag("drawer").assert(SemanticsMatcher.expectValue(SemanticsProperties.IsTraversalGroup, true))
        compose
            .onNode(
                inDrawer("New chat") and
                    hasAnyAncestor(SemanticsMatcher.expectValue(SemanticsProperties.TraversalIndex, -1f)),
            ).assertExists()
        compose.onNodeWithText("Today").assertIsDisplayed()
        compose.onNodeWithText("Portfolio bio").assertIsDisplayed()
    }

    @Test fun drawerRenameAndDelete() {
        seedChat("Portfolio bio")
        waitForShell()
        openDrawer()
        whileItMoves {
            compose.onNodeWithText("Portfolio bio").performTouchInput { longClick() }
            compose.waitUntilAtLeastOneExists(hasText("Rename"), 10_000)
        }
        whileItMoves {
            // the sheet grows to hold the field and the keyboard, then closes
            compose.onNodeWithText("Rename").performClick()
            compose
                .onNode(
                    hasSetTextAction() and hasText("Portfolio bio"),
                ).performTextReplacement("CV for the design role")
            compose.onNodeWithText("Save").performClick()
        }
        // the new title is in the rename field before it is in the drawer: wait for the field to go and the drawer's
        // row to read it, or the long press lands on the closing sheet and no Delete ever shows (it flaked so once)
        val renamedRow = hasText("CV for the design role") and hasAnyAncestor(hasTestTag("drawer"))
        val renameField = hasSetTextAction() and hasText("CV for the design role")
        compose.waitUntil(10_000) {
            compose.onAllNodes(renameField).fetchSemanticsNodes().isEmpty() &&
                compose.onAllNodes(renamedRow).fetchSemanticsNodes().size == 1
        }
        whileItMoves {
            compose.onNode(renamedRow).performTouchInput { longClick() }
            compose.waitUntilAtLeastOneExists(hasText("Delete"), 10_000)
        }
        compose.onNodeWithText("Delete").performClick()
        compose.waitUntil(
            10_000,
        ) { compose.onAllNodes(hasText("CV for the design role")).fetchSemanticsNodes().isEmpty() }
    }

    @Test fun deletingTheOpenChatOpensANewOne() {
        seedChat("Trip plan", last = "three days in Lisbon")
        waitForShell()
        openDrawer()
        compose.onNodeWithText("Trip plan").performClick()
        compose.waitUntilAtLeastOneExists(hasText("three days in Lisbon"), 10_000)
        compose.waitUntilAtLeastOneExists(hasText("Qwen3.5 0.8B", substring = true), 10_000)
        openDrawer()
        whileItMoves {
            compose
                .onNode(hasText("Trip plan") and hasAnyAncestor(hasTestTag("drawer")))
                .performTouchInput { longClick() }
            compose.waitUntilAtLeastOneExists(hasText("Delete"), 10_000)
        }
        compose.onNodeWithText("Delete").performClick()
        // the deleted chat's screen kept its model's name in the header; a new chat in a fresh app has no model yet,
        // so the name goes only once the screen is a new chat
        compose.waitUntil(10_000) {
            compose.onAllNodes(hasText("Qwen3.5 0.8B", substring = true)).fetchSemanticsNodes().isEmpty() &&
                compose.onAllNodes(hasText("three days in Lisbon")).fetchSemanticsNodes().isEmpty()
        }
    }

    @Test fun switchingLayoutKeepsTheOpenChat() {
        val id = seedChat("Trip plan", last = "three days in Lisbon")
        waitForShell()
        openDrawer()
        compose.onNodeWithText("Trip plan").performClick()
        compose.waitUntilAtLeastOneExists(hasText("three days in Lisbon"), 10_000)
        setLayout(AppLayout.ChatList)
        // the other layout draws the same chat: still open, now with a Back arrow instead of the menu
        compose.waitUntilAtLeastOneExists(hasContentDescription("Back"), 10_000)
        compose.onNodeWithText("three days in Lisbon").assertIsDisplayed()
        check(id > 0)
    }

    @Test fun chatListHasNoUnreadOrTicks() {
        seedChat("Trip plan")
        setLayout(AppLayout.ChatList)
        waitForShell()
        compose.waitUntilAtLeastOneExists(hasText("Trip plan"), 10_000)
        compose.onAllNodes(hasText("✓", substring = true)).assertCountEquals(0)
        compose.onAllNodes(hasContentDescription("unread", substring = true, ignoreCase = true)).assertCountEquals(0)
        compose.onAllNodes(hasText("typing", substring = true, ignoreCase = true)).assertCountEquals(0)
    }

    @Test fun instructionsScreenSaves() {
        setLayout(AppLayout.ChatList)
        waitForShell()
        compose.waitUntilAtLeastOneExists(hasText("Settings"), 10_000)
        compose.onNodeWithText("Settings").performClick()
        compose.onNodeWithText("Custom instructions").performClick()
        compose.waitUntilAtLeastOneExists(hasTestTag("instructions_about"), 10_000)
        compose.onNodeWithTag("instructions_about").performTextInput("a nurse")
        compose.onNodeWithTag("instructions_style").performTextInput("short answers")
        compose.onNodeWithTag("instructions_save").performClick()
        compose.waitUntilAtLeastOneExists(hasText("Hugging Face token"), 10_000) // back on Settings
        compose.onNodeWithText("Custom instructions").performClick()
        compose.waitUntilAtLeastOneExists(hasText("a nurse"), 10_000)
        compose.onNodeWithText("short answers").assertIsDisplayed()
    }

    @Test fun defaultModelChoiceIncludesLastUsed() {
        setLayout(AppLayout.ChatList)
        waitForShell()
        compose.waitUntilAtLeastOneExists(hasText("Settings"), 10_000)
        compose.onNodeWithText("Settings").performClick()
        compose.onNodeWithText("Default model").performClick()
        compose.waitUntilAtLeastOneExists(hasText("Last used"), 10_000)
        compose.onNodeWithText("New chats start with the model you used last").assertIsDisplayed()
        // nothing is pinned, so Last used is the selected choice
        compose.onNode(isSelected() and hasText("Last used")).assertIsDisplayed()
    }

    /**
     * Scrolls the page to its end, as a person would to reach its last row: `performScrollTo` stops a row at the
     * viewport's edge, under the floating tabs (D148), while the page's end is kept clear of them, so a tap there lands.
     */
    private fun scrollToTheEnd() {
        compose.onNode(hasScrollAction()).performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, END) }
    }

    @Test fun aboutShowsVersionsAndLicences() {
        setLayout(AppLayout.ChatList)
        waitForShell()
        compose.waitUntilAtLeastOneExists(hasText("Settings"), 10_000)
        compose.onNodeWithText("Settings").performClick()
        scrollToTheEnd()
        compose.onNodeWithText("About").performClick()
        compose.waitUntilAtLeastOneExists(hasTestTag("about"), 10_000)
        for (text in listOf("Version", "MIT licence", "Figtree", "Source Serif 4")) {
            compose.onNodeWithText(text).assertIsDisplayed()
        }
        compose.onAllNodesWithText("SIL Open Font Licence 1.1").assertCountEquals(2) // the two bundled fonts
        compose.onAllNodesWithText("llama.cpp").assertCountEquals(2) // the build, and its licence
    }
}

/** Further than any page is tall: a scroll by it ends at the page's end. */
private const val END = 100_000f
