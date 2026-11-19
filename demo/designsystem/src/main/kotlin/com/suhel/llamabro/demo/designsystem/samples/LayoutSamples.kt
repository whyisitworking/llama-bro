package com.suhel.llamabro.demo.designsystem.samples

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.suhel.llamabro.demo.designsystem.components.BottomTab
import com.suhel.llamabro.demo.designsystem.components.BottomTabs
import com.suhel.llamabro.demo.designsystem.components.DemoDrawer
import com.suhel.llamabro.demo.designsystem.components.DemoIcons
import com.suhel.llamabro.demo.designsystem.components.DrawerActions
import com.suhel.llamabro.demo.designsystem.components.Gap
import com.suhel.llamabro.demo.designsystem.components.GapAbove
import com.suhel.llamabro.demo.designsystem.components.GroupedLazyList
import com.suhel.llamabro.demo.designsystem.components.IconAction
import com.suhel.llamabro.demo.designsystem.components.InitialAvatar
import com.suhel.llamabro.demo.designsystem.components.LayoutKind
import com.suhel.llamabro.demo.designsystem.components.LayoutPreviewCard
import com.suhel.llamabro.demo.designsystem.components.ListEntry
import com.suhel.llamabro.demo.designsystem.components.ListGroup
import com.suhel.llamabro.demo.designsystem.components.TopBar
import com.suhel.llamabro.demo.designsystem.components.TopBarTitle
import com.suhel.llamabro.demo.designsystem.theme.DemoTheme

internal val SampleTabs =
    listOf(
        BottomTab("Chats", DemoIcons.Chats),
        BottomTab("Models", DemoIcons.Models),
        BottomTab("Settings", DemoIcons.Settings),
    )

/** The sidebar layout's top bar, a conversation's, the bottom tabs and the two layout previews of Appearance. */
@Composable
internal fun LayoutSamples() {
    SampleFrame {
        TopBar(
            title = { TopBarTitle("Writer", chevron = true, onClick = {}) },
            start = { IconAction(DemoIcons.Menu, "Chats", onClick = {}) },
            end = { IconAction(DemoIcons.NewChat, "New chat", onClick = {}) },
        )
        TopBar(
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    InitialAvatar("W", size = 32.dp)
                    TopBarTitle("Portfolio bio", subtitle = "Writer", chevron = true, onClick = {})
                }
            },
            start = { IconAction(DemoIcons.Back, "Back", onClick = {}) },
            divider = true,
        )
        BottomTabs(SampleTabs, selected = 0, onSelect = {})
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            LayoutPreviewCard(LayoutKind.Sidebar, "Sidebar", "Like ChatGPT", selected = false, onClick = {
            }, modifier = Modifier.weight(1f))
            LayoutPreviewCard(LayoutKind.ChatList, "Chat list", "Like messages", selected = true, onClick = {
            }, modifier = Modifier.weight(1f))
        }
    }
}

@LightDarkPreviews
@Composable
private fun LayoutPreview() = DemoTheme { LayoutSamples() }

/** The Sidebar layout's drawer, open over a chat. */
@Composable
internal fun DrawerSample() {
    DemoDrawer(
        open = true,
        onOpenChange = {},
        drawerContent = {
            GapAbove(Gap.Item)
            GroupedLazyList(
                listOf(
                    ListGroup("Today", listOf(ListEntry(1, "Portfolio bio", true), ListEntry(2, "Trip plan", false))),
                ),
                onClick = {},
                onLongClick = {},
                modifier = Modifier.weight(1f),
            )
            DrawerActions("Models, 3 on this phone", onSettings = {}, onModels = {}, onNewChat = {})
        },
    ) {
        com.suhel.llamabro.demo.designsystem.components
            .EmptyLine("What's on your mind?")
    }
}

@LightDarkPreviews
@Composable
private fun DrawerPreview() = DemoTheme { DrawerSample() }
