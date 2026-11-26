package com.suhel.llamabro.demo.designsystem.samples

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.suhel.llamabro.demo.designsystem.components.ChoiceChips
import com.suhel.llamabro.demo.designsystem.components.DemoFab
import com.suhel.llamabro.demo.designsystem.components.DemoIcons
import com.suhel.llamabro.demo.designsystem.components.IconAction
import com.suhel.llamabro.demo.designsystem.components.PrimaryButton
import com.suhel.llamabro.demo.designsystem.components.QuietButton
import com.suhel.llamabro.demo.designsystem.components.SegmentedSwitch
import com.suhel.llamabro.demo.designsystem.components.SheetFrame
import com.suhel.llamabro.demo.designsystem.theme.DemoTheme

/**
 * Every action: the strong button (on and off), quiet ones, icons (one with its mark), a card's top row, the FAB, the
 * Think chip, the Basic/Advanced switch and a few choices as chips.
 */
@Composable
internal fun ActionsSamples() {
    SampleFrame {
        PrimaryButton("Get Qwen3.5 4B · about 3 min", onClick = {}, modifier = Modifier.fillMaxWidth())
        PrimaryButton("Get Qwen3.5 0.8B", onClick = {}, modifier = Modifier.fillMaxWidth(), enabled = false)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            QuietButton("What's a model?", onClick = {})
            QuietButton("Get more models", onClick = {})
            QuietButton("Pause", onClick = {}, bordered = true)
        }
        Row {
            IconAction(DemoIcons.Menu, "Chats", onClick = {})
            IconAction(DemoIcons.Back, "Back", onClick = {})
            IconAction(DemoIcons.NewChat, "New chat", onClick = {})
            IconAction(DemoIcons.Copy, "Copy", onClick = {})
            IconAction(DemoIcons.Share, "Share", onClick = {})
            IconAction(DemoIcons.Regenerate, "Regenerate", onClick = {}, muted = true)
            // a search's filters, plain and changed from their defaults (the dot)
            IconAction(DemoIcons.Filter, "Search filters", onClick = {}, muted = true)
            IconAction(DemoIcons.Filter, "Search filters", onClick = {}, muted = true, mark = "Changed")
        }
        // a model card's top row: Back on a card a link opened, and the card's page on Hugging Face at its end
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            IconAction(DemoIcons.Back, "Back", onClick = {}, small = true)
            IconAction(DemoIcons.OpenExternal, "Open on Hugging Face", onClick = {}, small = true)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            DemoFab("New chat", DemoIcons.NewChat, onClick = {})
        }
        SegmentedSwitch(
            listOf("System", "Light", "Dark"),
            selected = 0,
            onSelect = {},
            modifier = Modifier.fillMaxWidth(),
        )
        SheetFrame {
            SegmentedSwitch(
                listOf("Basic", "Advanced"),
                selected = 1,
                onSelect = {},
                modifier = Modifier.fillMaxWidth(),
            )
            ChoiceChips(
                listOf("Most downloaded", "Trending", "Most liked", "Newest", "Recently updated"),
                selected = 3,
                onSelect = {},
            )
        }
    }
}

@LightDarkPreviews
@Composable
private fun ActionsPreview() = DemoTheme { ActionsSamples() }
