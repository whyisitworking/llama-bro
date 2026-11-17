package com.suhel.llamabro.demo.ui.shell

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.traversalIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.suhel.llamabro.demo.data.ChatSummary
import com.suhel.llamabro.demo.designsystem.components.DrawerActions
import com.suhel.llamabro.demo.designsystem.components.Gap
import com.suhel.llamabro.demo.designsystem.components.GapAbove
import com.suhel.llamabro.demo.designsystem.components.GroupedLazyList
import com.suhel.llamabro.demo.designsystem.components.ListEntry
import com.suhel.llamabro.demo.designsystem.components.ListGroup
import com.suhel.llamabro.demo.designsystem.components.drawerWidth

/**
 * The Sidebar layout's drawer, stateful: it reads the groups and the model count from [viewModel] and owns the
 * long-press sheets. Long-press a chat for Rename and Delete; [onDelete] deletes it.
 */
@Composable
fun SidebarDrawer(
    viewModel: ShellViewModel,
    currentChatId: Long?,
    onNewChat: () -> Unit,
    onOpenChat: (Long) -> Unit,
    onModels: () -> Unit,
    onSettings: () -> Unit,
    onDelete: (Long) -> Unit,
) {
    val groups by viewModel.groups.collectAsStateWithLifecycle()
    val modelCount by viewModel.modelCount.collectAsStateWithLifecycle()
    var pressed by remember { mutableStateOf<ChatSummary?>(null) }
    DrawerContent(groups, modelCount, currentChatId, onNewChat, onOpenChat, onModels, onSettings, onLongPress = {
        pressed =
            it
    })
    ChatActionsSheets(pressed, { pressed = null }, viewModel::rename, onDelete)
}

/**
 * The drawer, stateless (demo design §4.2): the chats in Today / This week / Earlier groups, then one row of actions at
 * the foot: Settings and Models (its count in what a screen reader says), and New chat. [currentChatId] marks the open
 * chat.
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
            .testTag("drawer")
            // a screen reader reaches the actions first, not after every chat: they sit at the foot for the thumb
            .semantics { isTraversalGroup = true },
    ) {
        GapAbove(Gap.Item)
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
        DrawerActions(
            "Models, $modelCount on this phone",
            onSettings,
            onModels,
            onNewChat,
            Modifier.semantics { traversalIndex = -1f },
        )
    }
}
