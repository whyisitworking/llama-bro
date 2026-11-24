package com.suhel.llamabro.demo.designsystem.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import com.suhel.llamabro.demo.designsystem.theme.DemoShapes
import com.suhel.llamabro.demo.designsystem.theme.DemoSize
import com.suhel.llamabro.demo.designsystem.theme.DemoSpacing
import com.suhel.llamabro.demo.designsystem.theme.DemoType
import com.suhel.llamabro.demo.designsystem.theme.LocalDemoColors

/** A model's initial in a soft accent circle (the chat list's avatars: W, S, D). */
@Composable
fun InitialAvatar(letter: String, modifier: Modifier = Modifier, size: Dp = DemoSize.control) {
    val colors = LocalDemoColors.current
    Box(modifier.size(size).background(colors.accentSoft, CircleShape), contentAlignment = Alignment.Center) {
        Text(
            letter.take(1).uppercase(),
            color = colors.accent,
            style = if (size >= DemoSize.control) DemoType.title else DemoType.label,
        )
    }
}

/**
 * A chat in the chat list: the model's [avatar] letter, the [title] and [time], and the last message [preview]
 * ("Gemma 3 4B: Here's a tighter…", "You: what about…"). A reply's sender name is in ink, a user's line all muted.
 * No unread counts, ticks or status (demo design §4.2).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChatRow(
    avatar: String,
    title: String,
    time: String,
    preview: String,
    fromUser: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {},
    onLongClick: (() -> Unit)? = null,
) {
    val colors = LocalDemoColors.current
    val text =
        remember(preview, fromUser, colors) {
            val cut = preview.indexOf(": ")
            buildAnnotatedString {
                if (!fromUser && cut > 0) {
                    withStyle(SpanStyle(color = colors.ink)) { append(preview.substring(0, cut + 1)) }
                    append(preview.substring(cut + 1))
                } else {
                    append(preview)
                }
            }
        }
    Row(
        modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .heightIn(min = DemoSize.rowTwoLine)
            .padding(start = DemoSpacing.item, end = DemoSpacing.item, top = DemoSpacing.compact),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        InitialAvatar(avatar)
        Spacer(Modifier.width(DemoSpacing.item))
        Column(
            Modifier
                .weight(1f)
                .drawBehind {
                    val stroke = DemoSize.hairline.toPx()
                    val y = size.height - stroke / 2
                    drawLine(colors.hairline, Offset(0f, y), Offset(size.width, y), strokeWidth = stroke)
                }.padding(bottom = DemoSpacing.compact),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title,
                    Modifier.weight(1f),
                    color = colors.ink,
                    style = DemoType.title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    time,
                    Modifier.padding(start = DemoSpacing.block),
                    color = colors.muted,
                    style = DemoType.bodySmall,
                    maxLines = 1,
                )
            }
            Text(
                text,
                color = colors.muted,
                style = DemoType.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** One chat in a [ChatHistoryList]: its [title], and its [trailing] time ("2:14 pm", "Thu", "12 Nov"). */
@Immutable
data class ChatHistoryEntry(val key: Long, val title: String, val selected: Boolean, val trailing: String? = null)

/** A labelled run of chats in a [ChatHistoryList] ("Today", "This week"). */
@Immutable
data class ChatHistoryGroup(val label: String, val entries: List<ChatHistoryEntry>)

/**
 * The sidebar's chats (D126): each group's quiet [label] (sentence case, soft), then its [SidebarChatRow]s, the open
 * chat on a soft fill. The rows sit 12 dp in from the drawer's edges with 12 dp inside, so their text and the labels
 * start on one 24 dp line. Lazy, so a long history costs only the rows on screen. A label breathes above (a section,
 * 24 dp; the first only a block, under the search) and sits tight on its rows.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChatHistoryList(
    groups: List<ChatHistoryGroup>,
    onClick: (Long) -> Unit,
    onLongClick: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = DemoSpacing.compact)) {
        for ((index, group) in groups.withIndex()) {
            item(key = "label-${group.label}", contentType = "label") {
                SectionLabel(
                    group.label,
                    Modifier.padding(
                        start = DemoSpacing.compact,
                        top = if (index == 0) DemoSpacing.block else DemoSpacing.section,
                        // the quiet label's 20 sp line already leaves air under its letters: with 4 dp more it sits as
                        // close to its rows as 6 dp does under a tighter line
                        bottom = DemoSpacing.inline,
                    ),
                    inset = false,
                    quiet = true,
                )
            }
            items(group.entries, key = { it.key }, contentType = { "chat" }) { entry ->
                SidebarChatRow(
                    title = entry.title,
                    trailing = entry.trailing,
                    selected = entry.selected,
                    onClick = { onClick(entry.key) },
                    onLongClick = { onLongClick(entry.key) },
                )
            }
        }
    }
}

/**
 * A quiet one-line row of a long list (the sidebar's chats, D126): the [title], cut with an ellipsis, and at the end a
 * soft [trailing] word (a time). One touch target tall, rounded like a card; [selected] lays the soft accent fill
 * behind it, no outline and no check, so the open chat is found at a glance without the list turning into choices.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SidebarChatRow(
    title: String,
    modifier: Modifier = Modifier,
    trailing: String? = null,
    selected: Boolean = false,
    onClick: () -> Unit = {},
    onLongClick: (() -> Unit)? = null,
) {
    val colors = LocalDemoColors.current
    Row(
        modifier
            .fillMaxWidth()
            .clip(DemoShapes.card)
            // only the open chat says "selected": the others aren't choices, so they say nothing rather than "not
            // selected"
            .then(if (selected) Modifier.background(colors.accentSoft).semantics { this.selected = true } else Modifier)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .heightIn(min = DemoSize.target)
            .padding(horizontal = DemoSpacing.compact, vertical = DemoSpacing.inline),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DemoSpacing.compact),
    ) {
        Text(
            title,
            Modifier.weight(1f),
            color = colors.ink,
            style = DemoType.rowLine,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (trailing != null) Text(trailing, color = colors.soft, style = DemoType.bodySmall, maxLines = 1)
    }
}
