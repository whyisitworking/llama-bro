package com.suhel.llamabro.demo.ui.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The message list's live parts: the reply shown while it streams and after, and following its end. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp")
class ShownReplyTest {
    @get:Rule val rule = createComposeRule()

    private fun reply(id: Long, text: String) = StreamingItem(id, text, Markdown.parse(text), null, null, false)

    @Test fun theShownReplyKeepsItsLastStreamedTextUntilItsRowArrives() {
        var streaming by mutableStateOf<StreamingItem?>(null)
        var rowKeys by mutableStateOf(emptySet<String>())
        lateinit var shown: () -> StreamingItem?
        rule.setContent { shown = rememberShownReply({ streaming }, rowKeys) }

        rule.runOnIdle { assertNull("nothing streamed yet", shown()) }
        rule.runOnIdle { streaming = reply(7, "Hel") }
        rule.runOnIdle { streaming = reply(7, "Hello there") }
        rule.runOnIdle { assertEquals("Hello there", shown()?.text) }
        // the stream ends a moment before the database delivers its row: the last text stays
        rule.runOnIdle { streaming = null }
        rule.runOnIdle { assertEquals("Hello there", shown()?.text) }
        rule.runOnIdle { rowKeys = setOf("m-7") }
        rule.runOnIdle { assertNull("its row is in the list", shown()) }
        // the next reply streams in its own right
        rule.runOnIdle { streaming = reply(8, "Next") }
        rule.runOnIdle { assertEquals("Next", shown()?.text) }
    }

    @Test fun followingStopsWhenTheReaderScrollsUpAndResumesAtTheEnd() {
        val follow = FollowState()
        rule.setContent {
            val state = rememberLazyListState()
            LaunchedEffect(state) { followScrolls(state, follow) }
            LaunchedEffect(state) { state.scrollToItem(99) } // the end, where a chat opens
            Column {
                LazyColumn(Modifier.height(400.dp).testTag("list"), state = state) {
                    items(100) { Text("row $it", Modifier.height(60.dp)) }
                }
            }
        }
        rule.waitForIdle()
        assertTrue("follows from the start", follow.following)
        rule.onNodeWithTag("list").performTouchInput { swipeDown() }
        rule.waitForIdle()
        assertFalse("a scroll toward older messages stops following", follow.following)
        repeat(3) { rule.onNodeWithTag("list").performTouchInput { swipeUp() } }
        rule.waitForIdle()
        assertTrue("a scroll that reaches the end follows again", follow.following)
    }
}
