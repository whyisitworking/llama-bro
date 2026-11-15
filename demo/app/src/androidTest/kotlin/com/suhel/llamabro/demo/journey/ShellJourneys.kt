package com.suhel.llamabro.demo.journey

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.suhel.llamabro.demo.models.Catalog
import org.junit.Test

/** The two layouts, and getting around the app. */
@OptIn(ExperimentalTestApi::class)
class ShellJourneys : Journey() {
    private val swift = Catalog.picks[0].key

    @Test fun bothLayoutsJourney() {
        onPhone(swift)
        world.engine.reply { content("Lisbon has seven hills.") }
        see("What's on your mind?")
        send("Plan my trip")
        see("Lisbon has seven hills.")

        // Sidebar: the chat is in the drawer, and opens from it
        tapDescription("Chats")
        see("Plan my trip")
        shot("1-sidebar")
        tapDescription("Settings")
        tap("Appearance")
        compose.onNodeWithTag("layout_chat_list").performClick()
        tapDescription("Back") // Appearance → Settings
        tapDescription("Back") // Settings → the chat, which now has a Back of its own
        seeTag("composer")
        compose.waitForIdle()
        tapDescription("Back") // → the chat list

        // Chat list: the same chat, found by a word of its reply, and opened from the list
        see("Search chats")
        compose.onNode(hasSetTextAction()).performTextInput("hills")
        see("Plan my trip")
        shot("2-chat-list-search")
        tap("Plan my trip")
        see("Lisbon has seven hills.")
        shot("3-the-same-chat")
    }
}
