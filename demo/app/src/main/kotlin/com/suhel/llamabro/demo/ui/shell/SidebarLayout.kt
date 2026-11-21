package com.suhel.llamabro.demo.ui.shell

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
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
import com.suhel.llamabro.demo.designsystem.components.DemoIcons
import com.suhel.llamabro.demo.designsystem.components.DrawerFoot
import com.suhel.llamabro.demo.designsystem.components.DrawerHead
import com.suhel.llamabro.demo.designsystem.components.GroupedLazyList
import com.suhel.llamabro.demo.designsystem.components.ListEntry
import com.suhel.llamabro.demo.designsystem.components.ListGroup
import com.suhel.llamabro.demo.designsystem.components.NavRow
import com.suhel.llamabro.demo.designsystem.components.Notice
import com.suhel.llamabro.demo.designsystem.components.SearchField
import com.suhel.llamabro.demo.designsystem.components.drawerWidth
import com.suhel.llamabro.demo.designsystem.theme.DemoSpacing
import java.time.ZoneId

/**
 * The Sidebar layout's drawer, stateful: it reads the groups, the search and the model count from [viewModel] and owns
 * the long-press sheets. Long-press a chat for Rename and Delete; [onDelete] deletes it.
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
    val searchText by viewModel.searchText.collectAsStateWithLifecycle()
    val modelCount by viewModel.modelCount.collectAsStateWithLifecycle()
    // the rows' times are read against the moment the groups were made, so a row and its group agree
    val now = remember(groups) { viewModel.now() }
    var pressed by remember { mutableStateOf<ChatSummary?>(null) }
    DrawerContent(
        groups,
        searchText,
        modelCount,
        currentChatId,
        now,
        viewModel.zone(),
        onSearch = viewModel::search,
        onNewChat = onNewChat,
        onOpenChat = onOpenChat,
        onModels = onModels,
        onSettings = onSettings,
        onLongPress = { pressed = it },
    )
    ChatActionsSheets(pressed, { pressed = null }, viewModel::rename, onDelete)
}

/**
 * The drawer, stateless (demo design §4.2, D126 "Quiet list"): the app's name with New chat, the search, the chats in
 * Today / This week / Earlier groups with each one's time, then Models and Settings as labelled rows at the foot.
 * [currentChatId] marks the open chat; [now] and [zone] give the rows their times.
 */
@Composable
fun DrawerContent(
    groups: List<ChatGroup>,
    searchText: String,
    modelCount: Int?,
    currentChatId: Long?,
    now: Long,
    zone: ZoneId,
    onSearch: (String) -> Unit,
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
            // a screen reader reaches the head and the foot first, not after every chat
            .semantics { isTraversalGroup = true },
    ) {
        DrawerHead(
            "Llama Bro",
            DemoIcons.NewChat,
            "New chat",
            onNewChat,
            Modifier.semantics { traversalIndex = -1f },
        )
        SearchField(
            searchText,
            onSearch,
            "Search chats",
            Modifier
                .fillMaxWidth()
                .padding(horizontal = DemoSpacing.gutter)
                .padding(top = DemoSpacing.compact, bottom = DemoSpacing.inline),
        )
        if (groups.isEmpty()) {
            // a plain line at the top of where the chats would be, never centred in empty space (D117), on the rows'
            // 24 dp text line; the box keeps the places at the foot
            Box(Modifier.weight(1f).fillMaxWidth()) {
                Notice(
                    if (searchText.isBlank()) "No chats yet. Start one." else "No chats match.",
                    Modifier.padding(start = DemoSpacing.block),
                )
            }
        } else {
            GroupedLazyList(
                groups.map { group ->
                    ListGroup(
                        group.group.label,
                        group.chats.map {
                            ListEntry(
                                it.id,
                                it.title,
                                it.id == currentChatId,
                                ChatListFormat.rowTime(it.updatedAt, now, zone),
                            )
                        },
                    )
                },
                onClick = onOpenChat,
                onLongClick = { id ->
                    groups.firstNotNullOfOrNull { g -> g.chats.firstOrNull { it.id == id } }?.let(onLongPress)
                },
                modifier = Modifier.weight(1f).testTag("chat_list"),
            )
        }
        DrawerFoot(Modifier.semantics { traversalIndex = -1f }) {
            // before the library is read the count is unknown: "Models" alone, not a "0" that a frame later corrects
            NavRow(DemoIcons.Models, "Models", onModels, note = modelCount?.let { "$it on this phone" })
            NavRow(DemoIcons.Settings, "Settings", onSettings)
        }
    }
}
