package com.suhel.llamabro.demo.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertLeftPositionInRootIsEqualTo
import androidx.compose.ui.test.assertTopPositionInRootIsEqualTo
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import com.suhel.llamabro.demo.designsystem.theme.DemoTheme
import com.suhel.llamabro.demo.designsystem.theme.ThemeMode
import com.suhel.llamabro.demo.ui.chat.ChatPreviewData
import com.suhel.llamabro.demo.ui.chat.ChatScreenActions
import com.suhel.llamabro.demo.ui.chat.Markdown
import com.suhel.llamabro.demo.ui.chat.MessageRow
import com.suhel.llamabro.demo.ui.chat.MessageStyle
import com.suhel.llamabro.demo.ui.chat.StreamingItem
import com.suhel.llamabro.demo.ui.chat.StreamingRow
import org.junit.Rule
import org.junit.Test

/** D146: a reply's text stays where it is when the stream ends and the saved reply takes its place. */
class ReplyPositionTest {
    @get:Rule val rule = createComposeRule()

    private fun textStaysPut(style: MessageStyle, text: String, reasoning: String? = null) {
        val actions = ChatScreenActions().message
        val streaming = StreamingItem(7, text, Markdown.parse(text), reasoning, null, false)
        val saved = ChatPreviewData.reply(7, 1, text, time = "2:14 pm", reasoning = reasoning, thoughtSeconds = 2)
        var done by mutableStateOf(false)
        rule.setContent {
            DemoTheme(ThemeMode.Light) {
                Column(Modifier.padding(16.dp)) {
                    if (done) {
                        MessageRow(saved, style, actions, showActions = true, easeIn = true)
                    } else {
                        StreamingRow(streaming, style, actions)
                    }
                }
            }
        }
        rule.waitForIdle()
        val before = rule.onNode(hasText(text, substring = true)).getBoundsInRoot()
        done = true
        rule.waitForIdle()
        // the actions have come in under the text, and the text has not moved
        rule.onNodeWithContentDescription("Copy reply").assertExists()
        rule
            .onNode(hasText(text, substring = true))
            .assertTopPositionInRootIsEqualTo(before.top)
            .assertLeftPositionInRootIsEqualTo(before.left)
            .assertWidthIsEqualTo(before.width)
            .assertHeightIsEqualTo(before.height)
    }

    @Test fun aShortOpenReplyDoesNotMoveWhenItFinishes() = textStaysPut(MessageStyle.Open, "Sure, here you go.")

    @Test fun aShortBubbleDoesNotMoveWhenItFinishes() = textStaysPut(MessageStyle.Bubbles, "Sure, here you go.")

    @Test fun aThoughtfulReplyDoesNotMoveWhenItFinishes() =
        textStaysPut(MessageStyle.Open, "The harbour was quiet that morning.", reasoning = "A harbour feels right.")
}
