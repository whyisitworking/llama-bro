package com.suhel.llamabro.demo.ui.shell

import androidx.activity.compose.ReportDrawnWhen
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.suhel.llamabro.demo.data.ChatSummary
import com.suhel.llamabro.demo.designsystem.components.BottomTab
import com.suhel.llamabro.demo.designsystem.components.BottomTabs
import com.suhel.llamabro.demo.designsystem.components.ChatRow
import com.suhel.llamabro.demo.designsystem.components.DemoFab
import com.suhel.llamabro.demo.designsystem.components.DemoIcons
import com.suhel.llamabro.demo.designsystem.components.EmptyLine
import com.suhel.llamabro.demo.designsystem.components.ListHomeScreen
import com.suhel.llamabro.demo.designsystem.components.SearchField
import com.suhel.llamabro.demo.designsystem.components.TopBar
import com.suhel.llamabro.demo.designsystem.components.TopBarTitle
import com.suhel.llamabro.demo.designsystem.theme.DemoSpacing
import java.time.ZoneId

/** The Chat list layout's bottom tabs. */
val ChatListTabs =
    listOf(
        BottomTab("Chats", DemoIcons.Chats),
        BottomTab("Models", DemoIcons.Models),
        BottomTab("Settings", DemoIcons.Settings),
    )

/**
 * The Chat list layout's home, stateful: the chats and the search text come from [viewModel], and the long-press
 * sheets are its own. Typing in the search recomposes only the list.
 */
@Composable
fun ChatListHome(viewModel: ShellViewModel, onOpenChat: (Long) -> Unit, onNewChat: () -> Unit) {
    val chats by viewModel.chats.collectAsStateWithLifecycle()
    val search by viewModel.searchText.collectAsStateWithLifecycle()
    var pressed by remember { mutableStateOf<ChatSummary?>(null) }
    // measured by the startup benchmark: "fully drawn" is the home with its chats on screen
    ReportDrawnWhen { chats.isNotEmpty() }
    val now = remember(chats) { viewModel.now() }
    val zone = remember { viewModel.zone() }
    ChatListContent(
        chats,
        search,
        now,
        zone,
        viewModel::search,
        onOpenChat,
        onNewChat,
        onLongPress = { pressed = it },
    )
    ChatActionsSheets(pressed, { pressed = null }, viewModel::rename, viewModel::delete)
}

/**
 * The Chat list layout's home, stateless (demo design §4.2): the "Chats" title, "Search chats", rows with the model's
 * initial, the title, the time and "<Model>: <last text>" or "You: <last text>", and a "New chat" button. No unread
 * counts, ticks or status.
 */
@Composable
fun ChatListContent(
    chats: List<ChatSummary>,
    search: String,
    now: Long,
    zone: ZoneId,
    onSearch: (String) -> Unit,
    onOpenChat: (Long) -> Unit,
    onNewChat: () -> Unit,
    onLongPress: (ChatSummary) -> Unit,
    modifier: Modifier = Modifier,
) {
    ListHomeScreen(
        topBar = { TopBar(title = { TopBarTitle("Chats") }) },
        header = {
            SearchField(search, onSearch, "Search chats", Modifier.fillMaxWidth())
            if (chats.isEmpty()) {
                EmptyLine(if (search.isBlank()) "No chats yet. Start one." else "No chats match.")
            }
        },
        modifier = modifier.statusBarsPadding(),
        fab = { DemoFab("New chat", DemoIcons.NewChat, onNewChat) },
    ) {
        LazyColumn(Modifier.weight(1f).testTag("chat_list")) {
            items(chats, key = { it.id }, contentType = { "chat" }) { chat ->
                ChatRow(
                    avatar = ChatListFormat.avatarLetter(chat.modelKey),
                    title = chat.title,
                    time = ChatListFormat.rowTime(chat.updatedAt, now, zone),
                    preview = ChatListFormat.preview(chat),
                    fromUser = chat.lastFromUser,
                    onClick = { onOpenChat(chat.id) },
                    onLongClick = { onLongPress(chat) },
                )
            }
        }
    }
}
