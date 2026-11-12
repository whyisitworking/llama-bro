package com.suhel.llamabro.demo.ui.chat

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import com.suhel.llamabro.demo.chat.Limit
import com.suhel.llamabro.demo.chat.LimitAction
import com.suhel.llamabro.demo.data.AppLayout
import com.suhel.llamabro.demo.designsystem.components.BottomBar
import com.suhel.llamabro.demo.designsystem.components.Composer
import com.suhel.llamabro.demo.designsystem.components.DemoIcons
import com.suhel.llamabro.demo.designsystem.components.DownloadRing
import com.suhel.llamabro.demo.designsystem.components.Gap
import com.suhel.llamabro.demo.designsystem.components.GapAbove
import com.suhel.llamabro.demo.designsystem.components.IconAction
import com.suhel.llamabro.demo.designsystem.components.InitialAvatar
import com.suhel.llamabro.demo.designsystem.components.Inline
import com.suhel.llamabro.demo.designsystem.components.JumpToLatest
import com.suhel.llamabro.demo.designsystem.components.LimitLine
import com.suhel.llamabro.demo.designsystem.components.MessageColumn
import com.suhel.llamabro.demo.designsystem.components.Stack
import com.suhel.llamabro.demo.designsystem.components.Thumbnail
import com.suhel.llamabro.demo.designsystem.components.TopBar
import com.suhel.llamabro.demo.designsystem.components.TopBarTitle
import com.suhel.llamabro.demo.designsystem.components.WakeHairline
import com.suhel.llamabro.demo.designsystem.theme.DemoShapes
import com.suhel.llamabro.demo.designsystem.theme.DemoSpacing
import com.suhel.llamabro.demo.designsystem.theme.DemoType
import com.suhel.llamabro.demo.designsystem.theme.LocalDemoColors
import kotlinx.coroutines.launch

/**
 * The conversation, stateless: the top bar with the model's name, the messages, the limit line and the message bar
 * (demo design §4.1 and §4.4). What it draws is [state]; what changes faster is read from [live] where it
 * is drawn; what the user does goes to [actions].
 */
@Composable
fun ChatContent(
    state: ChatScreenState,
    live: ChatLive,
    actions: ChatScreenActions,
    layout: AppLayout,
    style: MessageStyle,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize().statusBarsPadding().testTag("chat_screen")) {
        ChatTopBar(state.bar, layout, actions)
        WakeHairline(state.bar.waking)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (state.rows.isEmpty()) {
                EmptyChat(actions.onStarter)
            } else {
                MessageList(state.rows, state.busy, live.streaming, style, actions.message)
            }
        }
        BottomArea(state, live, actions)
    }
}

/** Under the messages, above the keyboard: a problem line, the limit line, and the message bar unless read-only. */
@Composable
private fun BottomArea(state: ChatScreenState, live: ChatLive, actions: ChatScreenActions) {
    BottomBar {
        state.bottom.problem?.let { LimitLine(it, null, {}, Modifier.testTag("problem_line")) }
        state.bottom.limit?.let { LimitRow(it, actions.onLimitAction) }
        if (!state.bottom.readOnly) ComposerArea(state.bar.header.name, live, actions)
    }
}

/** The model's name (a switcher on tap), the way back or to the chats, the download's ring, and a new chat. */
@Composable
private fun ChatTopBar(bar: ChatBar, layout: AppLayout, actions: ChatScreenActions) {
    val sidebar = layout == AppLayout.Sidebar
    TopBar(
        title = {
            if (sidebar) {
                SidebarTitle(bar.header, actions.onSwitcher)
            } else {
                ChatListTitle(bar, actions.onSwitcher)
            }
        },
        start = {
            if (sidebar) {
                IconAction(
                    DemoIcons.Menu,
                    "Chats",
                    actions.onMenu,
                )
            } else {
                IconAction(DemoIcons.Back, "Back", actions.onBack)
            }
        },
        end = {
            if (sidebar) IconAction(DemoIcons.NewChat, "New chat", actions.onNewChat)
        },
    )
}

/** The model's name with the download's ring and words right beside it (while the model arrives). */
@Composable
private fun SidebarTitle(header: ChatHeader, onSwitcher: () -> Unit) {
    Inline(Gap.Block) {
        TopBarTitle(
            header.name,
            chevron = true,
            onClick = onSwitcher,
            modifier = Modifier.weight(1f, fill = false).testTag("model_title"),
        )
        DownloadIndicator(header)
    }
}

/** The chat list layout's title: the chat's own title over the model's name, beside the model's initial. */
@Composable
private fun ChatListTitle(bar: ChatBar, onSwitcher: () -> Unit) {
    val modelName = bar.header.name
    Inline(Gap.Block) {
        InitialAvatar(modelName.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "?")
        TopBarTitle(
            bar.title.ifEmpty { modelName },
            subtitle = if (bar.title.isEmpty()) null else modelName,
            chevron = true,
            onClick = onSwitcher,
            modifier = Modifier.weight(1f, fill = false).testTag("model_title"),
        )
        DownloadIndicator(bar.header)
    }
}

/** The ring and its words while the model downloads; nothing otherwise. */
@Composable
private fun DownloadIndicator(header: ChatHeader) {
    val fraction = header.downloadFraction ?: return
    val label = "Arriving · ${header.downloadLabel ?: ""}".trimEnd(' ', '·')
    Inline(Gap.Block, Modifier.testTag("download_ring")) {
        DownloadRing(fraction, label = label)
        // the ring already says it, with the time left, to a screen reader; the bar shows the percentage so that the
        // model's own (long) name keeps its room
        header.downloadLabel?.let {
            Text(
                it.substringBefore(" ·"),
                Modifier.clearAndSetSemantics { },
                color = LocalDemoColors.current.muted,
                style = DemoType.bodySmall,
            )
        }
    }
}

/**
 * The messages. A new row, or the reply starting to stream, scrolls to the end; the reply's growth follows only while
 * we are at the end.
 */
@Composable
private fun MessageList(
    rows: List<ChatItem>,
    busy: Boolean,
    streaming: () -> StreamingItem?,
    style: MessageStyle,
    actions: MessageActions,
) {
    val listState = rememberLazyListState()
    val lastReplyKey = remember(rows) { rows.lastOrNull { it is ReplyItem }?.key }
    val bottom = rows.lastOrNull()?.key

    LaunchedEffect(bottom, busy) {
        val count = rows.size + if (busy) 1 else 0 // not the laid-out count, which can still be the old one
        if (count > 0) listState.animateScrollToItem(count - 1)
    }
    LaunchedEffect(listState) {
        snapshotFlow { streaming()?.text?.length ?: 0 }.collect {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()
            if (last != null && last.index >= info.totalItemsCount - 2) {
                listState.scrollToItem(info.totalItemsCount - 1, Int.MAX_VALUE)
            }
        }
    }

    // away from the end (the reader scrolled up): a button back to the latest message
    val away by remember { derivedStateOf { listState.canScrollForward } }
    val scope = rememberCoroutineScope()
    MessageColumn(
        listState,
        Modifier.testTag("message_list"),
        overlay = {
            if (away) {
                JumpToLatest(
                    onClick = {
                        // to the end of the last item, not the start of it: a reply taller than the screen would
                        // otherwise stay "away" and the button would stay on screen
                        scope.launch {
                            listState.animateScrollToItem((rows.size + if (busy) 1 else 0) - 1, Int.MAX_VALUE)
                        }
                    },
                    modifier = Modifier.align(Alignment.BottomCenter).testTag("jump_to_latest"),
                )
            }
        },
    ) {
        items(rows, key = { it.key }, contentType = { it.type }) { item ->
            MessageRow(item, style, actions, showActions = !busy && item.key == lastReplyKey)
        }
        if (busy) item(key = "streaming", contentType = "streaming") { StreamingHost(streaming, style, actions) }
    }
}

/** The reply being written, in its own row: it reads [streaming] here, so a token redraws this and nothing above. */
@Composable
private fun StreamingHost(streaming: () -> StreamingItem?, style: MessageStyle, actions: MessageActions) {
    streaming()?.let { StreamingRow(it, style, actions) } ?: GapAbove(Gap.None)
}

/** A limit's line and its one button, which does what the limit says (switch, get a model, add a token, retry). */
@Composable
private fun LimitRow(limit: Limit, onAction: (LimitAction) -> Unit) {
    LimitLine(
        limit.line,
        limit.actionLabel,
        onAction = { onAction(limit.action) },
        modifier = Modifier.testTag("limit_line"),
        secondAction = limit.secondLabel,
        onSecondAction = { limit.second?.let(onAction) },
    )
}

/**
 * The message bar, with the edit line and the draft's pictures above it. It reads the draft here, so typing
 * recomposes this and nothing else.
 */
@Composable
private fun ComposerArea(modelName: String, live: ChatLive, actions: ChatScreenActions) {
    val draft = live.draft()
    Stack(Gap.Block) {
        if (draft.editing != null) LimitLine("Editing your message", "Cancel", actions.onCancelEdit)
        if (draft.attachments.isNotEmpty()) PhotoRow(draft.attachments, live.thumbnail, actions.onRemovePhoto)
        Composer(
            state = live.composer(),
            placeholder = if (modelName.isEmpty()) "Message" else "Message $modelName",
            onTextChange = actions.onText,
            onSend = actions.onSend,
            onStop = actions.onStop,
            onAttach = actions.onAttach,
            onThink = actions.onThink,
            modifier = Modifier.testTag("composer"),
        )
    }
}

/** What is attached to the message being written: a small picture each, with a ✕ to take it back. */
@Composable
private fun PhotoRow(names: List<String>, thumbnail: suspend (String) -> ImageBitmap?, onRemove: (String) -> Unit) {
    Inline(Gap.Block, Modifier.testTag("attachments")) {
        for (name in names) PhotoThumb(name, thumbnail, onRemove)
    }
}

@Composable
private fun PhotoThumb(name: String, thumbnail: suspend (String) -> ImageBitmap?, onRemove: (String) -> Unit) {
    val picture by produceState<ImageBitmap?>(null, name) { value = thumbnail(name) }
    Thumbnail(picture, "Attached photo", "Remove photo", { onRemove(name) })
}
