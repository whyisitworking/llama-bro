package com.suhel.llamabro.demo.ui.shell

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.suhel.llamabro.demo.data.ChatSummary
import com.suhel.llamabro.demo.designsystem.components.DemoIcons
import com.suhel.llamabro.demo.designsystem.components.Gap
import com.suhel.llamabro.demo.designsystem.components.GroupedLazyList
import com.suhel.llamabro.demo.designsystem.components.GroupedList
import com.suhel.llamabro.demo.designsystem.components.ListEntry
import com.suhel.llamabro.demo.designsystem.components.ListGroup
import com.suhel.llamabro.demo.designsystem.components.ListRow
import com.suhel.llamabro.demo.designsystem.components.PrimaryButton
import com.suhel.llamabro.demo.designsystem.components.SectionLabel
import com.suhel.llamabro.demo.designsystem.components.Stack
import com.suhel.llamabro.demo.designsystem.components.drawerWidth
import com.suhel.llamabro.demo.designsystem.components.gutterPadding
import com.suhel.llamabro.demo.designsystem.theme.DemoSpacing

/**
 * The Sidebar layout's drawer, stateful: it reads the groups and the model count from [viewModel] and owns the
 * long-press sheets. Long-press a chat for Rename and Delete.
 */
@Composable
fun SidebarDrawer(
    viewModel: ShellViewModel,
    currentChatId: Long?,
    onNewChat: () -> Unit,
    onOpenChat: (Long) -> Unit,
    onModels: () -> Unit,
    onSettings: () -> Unit,
) {
    val groups by viewModel.groups.collectAsStateWithLifecycle()
    val modelCount by viewModel.modelCount.collectAsStateWithLifecycle()
    var pressed by remember { mutableStateOf<ChatSummary?>(null) }
    DrawerContent(groups, modelCount, currentChatId, onNewChat, onOpenChat, onModels, onSettings, onLongPress = {
        pressed =
            it
    })
    ChatActionsSheets(pressed, { pressed = null }, viewModel::rename, viewModel::delete)
}

/**
 * The drawer, stateless (demo design §4.2): New chat, Models with its count, the chats in Today / This week / Earlier
 * groups, Settings at the bottom. [currentChatId] marks the open chat.
 */
@Composable
fun DrawerContent(
    groups: List<ChatGroup>,
    modelCount: Int,
    currentChatId: Long?,
    onNewChat: () -> Unit,
    onOpenChat: (Long) -> Unit,
    onModels: () -> Unit,
    onSettings: () -> Unit,
    onLongPress: (ChatSummary) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .drawerWidth()
            .testTag("drawer"),
    ) {
        Stack(Gap.Item, Modifier.gutterPadding()) {
            PrimaryButton("New chat", onNewChat, Modifier.fillMaxWidth())
            GroupedList {
                row {
                    ListRow(
                        "Models",
                        leading = DemoIcons.Models,
                        trailing = "$modelCount on this phone",
                        onClick = onModels,
                    )
                }
            }
        }
        GroupedLazyList(
            groups.map { group ->
                ListGroup(group.group.label, group.chats.map { ListEntry(it.id, it.title, it.id == currentChatId) })
            },
            onClick = onOpenChat,
            onLongClick = { id ->
                groups.firstNotNullOfOrNull { g -> g.chats.firstOrNull { it.id == id } }?.let(onLongPress)
            },
            modifier = Modifier.weight(1f).testTag("chat_list"),
        )
        GroupedList(Modifier.gutterPadding()) {
            row { ListRow("Settings", leading = DemoIcons.Settings, chevron = true, onClick = onSettings) }
        }
    }
}
