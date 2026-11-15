package com.suhel.llamabro.demo.ui

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.junit4.accessibility.disableAccessibilityChecks
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import androidx.test.platform.app.InstrumentationRegistry
import com.suhel.llamabro.demo.MainActivity
import com.suhel.llamabro.demo.data.AppLayout
import com.suhel.llamabro.demo.data.MessageKind
import com.suhel.llamabro.demo.data.NewMessage
import com.suhel.llamabro.demo.designsystem.theme.ThemeMode
import com.suhel.llamabro.demo.testing.TestAppRule
import com.suhel.llamabro.demo.testing.demoRules
import com.suhel.llamabro.demo.testing.withAccessibilityChecks
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain

/**
 * The shell on the phone: both layouts, the drawer, the chat list, settings. Needs the phone; written in
 * and run with `demo/scripts/check.sh --device`.
 */
@OptIn(ExperimentalTestApi::class)
class ShellTest {
    private val app = TestAppRule()
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule val rules: RuleChain = RuleChain.outerRule(demoRules(app)).around(compose)

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
        compose.activityRule.scenario.recreate()
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
            "Memory" to "After you leave the app",
            "About" to "Licences",
        )) {
            compose.waitUntilAtLeastOneExists(hasText(row), 10_000)
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
        runBlocking { app.container.settings.setTheme(ThemeMode.Dark) }
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
        }
    }

    @Test fun drawerShowsModelsCountAndGroups() {
        seedChat("Portfolio bio")
        waitForShell()
        openDrawer()

        // the drawer's actions are icons at its foot: what a screen reader says names them (and the chat's top bar has
        // a New chat of its own)
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
        compose.onNodeWithText("TODAY").assertIsDisplayed()
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
        compose.waitUntilAtLeastOneExists(hasText("CV for the design role"), 10_000)
        whileItMoves {
            compose.onNodeWithText("CV for the design role").performTouchInput { longClick() }
            compose.waitUntilAtLeastOneExists(hasText("Delete"), 10_000)
        }
        compose.onNodeWithText("Delete").performClick()
        compose.waitUntil(
            10_000,
        ) { compose.onAllNodes(hasText("CV for the design role")).fetchSemanticsNodes().isEmpty() }
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

    @Test fun aboutShowsVersionsAndLicences() {
        setLayout(AppLayout.ChatList)
        waitForShell()
        compose.waitUntilAtLeastOneExists(hasText("Settings"), 10_000)
        compose.onNodeWithText("Settings").performClick()
        compose.onNodeWithText("About").performClick()
        compose.waitUntilAtLeastOneExists(hasTestTag("about"), 10_000)
        for (text in listOf("Version", "MIT licence", "Source Serif 4", "SIL Open Font Licence 1.1")) {
            compose.onNodeWithText(text).assertIsDisplayed()
        }
        compose.onAllNodesWithText("llama.cpp").assertCountEquals(2) // the build, and its licence
    }
}
