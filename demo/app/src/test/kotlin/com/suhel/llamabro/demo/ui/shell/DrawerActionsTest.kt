package com.suhel.llamabro.demo.ui.shell

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.suhel.llamabro.demo.data.ChatSummary
import com.suhel.llamabro.demo.designsystem.components.DemoDrawer
import com.suhel.llamabro.demo.designsystem.theme.DemoTheme
import com.suhel.llamabro.demo.designsystem.theme.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.ZoneOffset

/**
 * The Sidebar drawer (demo design §4.2, D126): New chat at its head and Models and Settings as labelled rows at its foot,
 * all on screen around a list of chats with their times; Models says how many models are on the phone; the search
 * filters, and says so when nothing matches.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DrawerActionsTest {
    @get:Rule val rule = createComposeRule()

    private val now = 1_800_000_000_000L

    private fun drawer(
        chats: List<ChatSummary>,
        modelCount: Int?,
        search: String = "",
        onSearch: (String) -> Unit = {},
        onModels: () -> Unit = {},
    ) {
        rule.setContent {
            DemoTheme(ThemeMode.Light) {
                DemoDrawer(open = true, onOpenChange = {}, drawerContent = {
                    DrawerContent(
                        ChatListFormat.group(chats, now, ZoneOffset.UTC),
                        search,
                        modelCount,
                        currentChatId = null,
                        now = now,
                        zone = ZoneOffset.UTC,
                        onSearch = onSearch,
                        onNewChat = {},
                        onOpenChat = {},
                        onModels = onModels,
                        onSettings = {},
                        onLongPress = {},
                    )
                }) {}
            }
        }
        rule.waitForIdle()
    }

    private fun inDrawer(description: String) =
        hasContentDescription(description) and hasAnyAncestor(hasTestTag("drawer"))

    @Test fun theDrawersActionsAreOnScreenAroundItsChats() {
        drawer(listOf(ChatSummary(1, "Portfolio bio", now, "a/b:Q4_K_M", "hello", true)), 0)
        for (action in listOf("New chat", "Models, 0 on this phone", "Settings")) {
            rule.onNode(inDrawer(action)).assertIsDisplayed()
        }
        rule.onNodeWithText("Llama Bro").assertIsDisplayed()
        rule.onNodeWithText("Today").assertIsDisplayed()
        rule.onNodeWithText("Portfolio bio").assertIsDisplayed()
        // the row's time, as the chat list's rows give it
        rule.onNodeWithText(ChatListFormat.rowTime(now, now, ZoneOffset.UTC)).assertIsDisplayed()
    }

    @Test fun modelsIsOneButtonThatSaysItsCount() {
        var opened = 0
        drawer(emptyList(), 3, onModels = { opened++ })
        // one node, read once: the visible "Models" and "3 on this phone" are not separate stops
        rule.onNode(inDrawer("Models, 3 on this phone")).assertIsDisplayed().performClick()
        rule.onAllNodes(hasText("Models")).assertCountEquals(0)
        assertEquals(1, opened)
    }

    @Test fun beforeTheLibraryIsReadModelsSaysNoCount() {
        drawer(emptyList(), null)
        rule.onNode(inDrawer("Models")).assertIsDisplayed()
        rule.onAllNodes(hasContentDescription("on this phone", substring = true)).assertCountEquals(0)
        rule.onNodeWithText("No chats yet. Start one.").assertIsDisplayed()
    }

    @Test fun theSearchFiltersAndSaysWhenNothingMatches() {
        val typed = mutableListOf<String>()
        drawer(emptyList(), 3, search = "zzzz", onSearch = { typed += it })
        rule.onNodeWithText("No chats match.").assertIsDisplayed()
        rule.onAllNodes(hasText("No chats yet. Start one.")).assertCountEquals(0)
        rule.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag("drawer"))).performTextReplacement("plan")
        assertEquals(listOf("plan"), typed)
    }
}
