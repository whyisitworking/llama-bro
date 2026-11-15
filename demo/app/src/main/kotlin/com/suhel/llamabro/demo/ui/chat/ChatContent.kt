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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
import com.suhel.llamabro.demo.designsystem.components.IconAction
import com.suhel.llamabro.demo.designsystem.components.InitialAvatar
import com.suhel.llamabro.demo.designsystem.components.Inline
import com.suhel.llamabro.demo.designsystem.components.JumpToLatest
import com.suhel.llamabro.demo.designsystem.components.LimitLine
import com.suhel.llamabro.demo.designsystem.components.MessageColumn
import com.suhel.llamabro.demo.designsystem.components.Reveal
import com.suhel.llamabro.demo.designsystem.components.Stack
import com.suhel.llamabro.demo.designsystem.components.Thumbnail
import com.suhel.llamabro.demo.designsystem.components.TopBar
import com.suhel.llamabro.demo.designsystem.components.TopBarTitle
import com.suhel.llamabro.demo.designsystem.components.TypingDots
import com.suhel.llamabro.demo.designsystem.components.WakeHairline
import com.suhel.llamabro.demo.designsystem.components.messageMotion
import com.suhel.llamabro.demo.designsystem.theme.DemoShapes
import com.suhel.llamabro.demo.designsystem.theme.DemoSpacing
import com.suhel.llamabro.demo.designsystem.theme.DemoType
import com.suhel.llamabro.demo.designsystem.theme.LocalDemoColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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
        WakeHairline(state.bar.waking, progress = state.bar.wakeProgress)
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
        // a problem or a note unfolds above the bar when it arrives and folds away when it is fixed or closed
        Reveal(state.bottom.problem) { LimitLine(it, null, {}, Modifier.testTag("problem_line")) }
        Reveal(state.bottom.limit) { LimitRow(it, actions.onLimitAction, actions.onDismissLimit) }
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
 * The messages. While the reader is at the end ([FollowState]), a new row and the streaming reply's growth keep the end
 * in view; scrolled away, the list stays where they left it until they come back or send a message.
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
    val follow = rememberFollowState()
    val lastReplyKey = remember(rows) { rows.lastOrNull { it is ReplyItem }?.key }
    val bottom = rows.lastOrNull()
    val count = rows.size + if (busy) 1 else 0 // not the laid-out count, which can still be the old one
    // the message the reader last sent, so that coming back to the chat (or turning the phone) does not count again
    var sent by rememberSaveable { mutableStateOf<String?>(null) }

    LaunchedEffect(bottom?.key, busy) {
        // the reader sent a message (or asked again): they want to see the answer, wherever they had scrolled to; a
        // reply finishing or stopping leaves a reader who scrolled away where they are
        if (bottom is UserItem && bottom.key != sent) {
            sent = bottom.key
            follow.follow()
        }
        if (follow.following && count > 0) listState.animateScrollToItem(count - 1, Int.MAX_VALUE)
    }
    LaunchedEffect(listState) { followScrolls(listState, follow) }
    LaunchedEffect(listState) { keepAtEnd(listState, follow) }

    // away from the end: a button back to the latest message. While a followed reply grows the end runs a frame
    // ahead of the list, which is not the reader being away
    val away by remember(busy) { derivedStateOf { listState.canScrollForward && !(busy && follow.following) } }
    val scope = rememberCoroutineScope()
    MessageColumn(
        listState,
        Modifier.testTag("message_list"),
        overlay = {
            JumpToLatest(
                visible = away,
                onClick = {
                    // to the end of the last item, not the start of it: a reply taller than the screen would
                    // otherwise stay "away" and the button would stay on screen. Following resumes once there, so a
                    // growing reply does not move the end under the animation
                    scope.launch {
                        listState.animateScrollToItem(count - 1, Int.MAX_VALUE)
                        follow.follow()
                    }
                },
                modifier = Modifier.align(Alignment.BottomCenter).testTag("jump_to_latest"),
            )
        },
    ) {
        items(rows, key = { it.key }, contentType = { it.type }) { item ->
            // a finished reply takes the streaming row's place: it is already on screen, so it does not fade in again
            Box(Modifier.messageMotion(this, appear = item !is ReplyItem)) {
                MessageRow(item, style, actions, showActions = !busy && item.key == lastReplyKey)
            }
        }
        if (busy) {
            item(key = "streaming", contentType = "streaming") {
                Box(Modifier.messageMotion(this)) { StreamingHost(streaming, style, actions) }
            }
        }
    }
}

/** Tells [follow] where every scroll took the list, including the last step of one that has just stopped. */
private suspend fun followScrolls(listState: LazyListState, follow: FollowState) {
    var from = listState.position()
    var wasScrolling = false
    snapshotFlow { Triple(listState.position(), listState.isScrollInProgress, !listState.canScrollForward) }
        .collect { (to, scrolling, atEnd) ->
            if (scrolling || wasScrolling) follow.onScroll(from, to, atEnd)
            from = to
            wasScrolling = scrolling
        }
}

/**
 * While following and nothing else scrolls the list, keeps the end of the last item in view as the streaming row grows
 * (its laid-out height, so the snap sees the grown row; a thought's open fold grows it too) and when it ends.
 */
private suspend fun keepAtEnd(listState: LazyListState, follow: FollowState) {
    snapshotFlow {
        val streamingHeight =
            listState.layoutInfo.visibleItemsInfo
                .lastOrNull()
                ?.takeIf { it.key == "streaming" }
                ?.size
        Triple(streamingHeight, follow.following, listState.isScrollInProgress)
    }.collect { (_, following, scrolling) ->
        // only when short of the end: the snap's own scroll ending emits again, and must not snap again
        if (following && !scrolling && listState.canScrollForward) {
            try {
                listState.scrollToItem(listState.layoutInfo.totalItemsCount - 1, Int.MAX_VALUE)
            } catch (e: CancellationException) {
                // the reader's touch took the list over: keep listening unless this effect itself was stopped
                currentCoroutineContext().ensureActive()
            }
        }
    }
}

private fun LazyListState.position() = ListPosition(firstVisibleItemIndex, firstVisibleItemScrollOffset)

/**
 * The reply being written, in its own row: it reads [streaming] here, so a token redraws this and nothing above. Until
 * the first word (the model wakes, then reads the prompt) the typing dots hold its place.
 */
@Composable
private fun StreamingHost(streaming: () -> StreamingItem?, style: MessageStyle, actions: MessageActions) {
    streaming()?.let { StreamingRow(it, style, actions) } ?: TypingDots(Modifier.testTag("typing"))
}

/**
 * A limit's line and its button, which does what the limit says (switch, get a model, add a token, retry); a note
 * ([Limit.dismissible]) also has a close button.
 */
@Composable
private fun LimitRow(limit: Limit, onAction: (LimitAction) -> Unit, onDismiss: () -> Unit) {
    LimitLine(
        limit.line,
        limit.actionLabel,
        onAction = { onAction(limit.action) },
        modifier = Modifier.testTag("limit_line"),
        secondAction = limit.secondLabel,
        onSecondAction = { limit.second?.let(onAction) },
        onDismiss = onDismiss.takeIf { limit.dismissible },
    )
}

/**
 * The message bar, with the edit line above it and the draft's pictures inside it. It reads the draft here, so typing
 * recomposes this and nothing else.
 */
@Composable
private fun ComposerArea(modelName: String, live: ChatLive, actions: ChatScreenActions) {
    val draft = live.draft()
    Stack(Gap.Block) {
        Reveal(draft.editing) { LimitLine("Editing your message", "Cancel", actions.onCancelEdit) }
        Composer(
            state = live.composer(),
            placeholder = if (modelName.isEmpty()) "Message" else "Message $modelName",
            onTextChange = actions.onText,
            onSend = actions.onSend,
            onStop = actions.onStop,
            onAttach = actions.onAttach,
            onThink = actions.onThink,
            modifier = Modifier.testTag("composer"),
            attachments =
                if (draft.attachments.isEmpty()) {
                    null
                } else {
                    { PhotoRow(draft.attachments, live.thumbnail, actions.onRemovePhoto) }
                },
        )
    }
}

/** What is attached to the message being written, inside the bar: a small picture each, with a ✕ to take it back. */
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
