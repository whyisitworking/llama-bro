package com.suhel.llamabro.demo.designsystem.samples

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.suhel.llamabro.demo.designsystem.components.BottomTab
import com.suhel.llamabro.demo.designsystem.components.BottomTabs
import com.suhel.llamabro.demo.designsystem.components.ChatHistoryEntry
import com.suhel.llamabro.demo.designsystem.components.ChatHistoryGroup
import com.suhel.llamabro.demo.designsystem.components.ChatHistoryList
import com.suhel.llamabro.demo.designsystem.components.DemoDrawer
import com.suhel.llamabro.demo.designsystem.components.DemoIcons
import com.suhel.llamabro.demo.designsystem.components.DrawerFoot
import com.suhel.llamabro.demo.designsystem.components.DrawerHead
import com.suhel.llamabro.demo.designsystem.components.IconAction
import com.suhel.llamabro.demo.designsystem.components.InitialAvatar
import com.suhel.llamabro.demo.designsystem.components.LayoutKind
import com.suhel.llamabro.demo.designsystem.components.LayoutPreviewCard
import com.suhel.llamabro.demo.designsystem.components.NavRow
import com.suhel.llamabro.demo.designsystem.components.SearchField
import com.suhel.llamabro.demo.designsystem.components.TopBar
import com.suhel.llamabro.demo.designsystem.components.TopBarTitle
import com.suhel.llamabro.demo.designsystem.theme.DemoSpacing
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
            title = { TopBarTitle("Gemma 3 4B", chevron = true, onClick = {}) },
            start = { IconAction(DemoIcons.Menu, "Chats", onClick = {}) },
            end = { IconAction(DemoIcons.NewChat, "New chat", onClick = {}) },
        )
        TopBar(
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    InitialAvatar("W", size = 32.dp)
                    TopBarTitle("Portfolio bio", subtitle = "Gemma 3 4B", chevron = true, onClick = {})
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

/**
 * The Sidebar layout's drawer (D126, D145), [open] or closed over a chat: the name and New chat, the search, the
 * history, the places; open, it pushes the chat aside and dims it.
 */
@Composable
internal fun DrawerSample(open: Boolean = true) {
    DemoDrawer(
        open = open,
        onOpenChange = {},
        drawerContent = {
            DrawerHead("Llama Bro", DemoIcons.NewChat, "New chat", onAction = {})
            SearchField(
                "",
                {},
                "Search chats",
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = DemoSpacing.gutter)
                    .padding(top = DemoSpacing.compact, bottom = DemoSpacing.inline),
            )
            ChatHistoryList(
                listOf(
                    ChatHistoryGroup(
                        "Today",
                        listOf(
                            ChatHistoryEntry(1, "Portfolio bio", true, "2:14 pm"),
                            ChatHistoryEntry(2, "Trip plan", false, "9:40 am"),
                        ),
                    ),
                    ChatHistoryGroup("This week", listOf(ChatHistoryEntry(3, "What is a quant", false, "Wed"))),
                ),
                onClick = {},
                onLongClick = {},
                modifier = Modifier.weight(1f),
            )
            DrawerFoot {
                NavRow(DemoIcons.Models, "Models", onClick = {}, note = "3 on this phone")
                NavRow(DemoIcons.Settings, "Settings", onClick = {})
            }
        },
    ) {
        com.suhel.llamabro.demo.designsystem.components
            .EmptyLine("What's on your mind?")
    }
}

@LightDarkPreviews
@Composable
private fun DrawerPreview() = DemoTheme { DrawerSample() }

@LightDarkPreviews
@Composable
private fun DrawerClosedPreview() = DemoTheme { DrawerSample(open = false) }
