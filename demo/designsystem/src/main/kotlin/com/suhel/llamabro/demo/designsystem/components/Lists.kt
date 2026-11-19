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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.suhel.llamabro.demo.designsystem.theme.DemoShapes
import com.suhel.llamabro.demo.designsystem.theme.DemoSize
import com.suhel.llamabro.demo.designsystem.theme.DemoSpacing
import com.suhel.llamabro.demo.designsystem.theme.DemoType
import com.suhel.llamabro.demo.designsystem.theme.LocalDemoColors

/**
 * A small uppercase section heading ("Picked for your phone", "Today"), with an optional link at the end ("What's a
 * model?"). It is inset by the cards' and rows' own padding ([DemoSpacing.item]), on a screen and on a sheet alike, so
 * it starts where the text of the cards and rows under it starts, not on the edge of their surface.
 */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier, action: String? = null, onAction: (() -> Unit)? = null) {
    val colors = LocalDemoColors.current
    Row(modifier.fillMaxWidth().padding(start = DemoSpacing.item), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text.uppercase(),
            Modifier.weight(1f),
            color = colors.muted,
            style = DemoType.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        // with an action the row is the action's 48 dp, like Material's list subheader: a target that reaches past
        // its row is cut by the parent's bounds, and accessibility services see only what's left (F130)
        if (action != null) QuietButton(text = action, onClick = onAction ?: {})
    }
}

/**
 * A muted line under a section's cards ("Changing this reloads the model."): inset like [SectionLabel], so it starts on
 * the text of the cards above it, not on the edge of their surface.
 */
@Composable
fun SectionNote(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier.padding(start = DemoSpacing.item),
        color = LocalDemoColors.current.muted,
        style = DemoType.bodySmall,
    )
}

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
 * ("Writer: Here's a tighter…", "You: what about…"). A reply's sender name is in ink, a user's line all muted.
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

private val QuantPadding = PaddingValues(horizontal = DemoSpacing.item)

/**
 * The quant table's grid: the size and fit columns are sized in sp so they grow with the font. Past 1.3x font
 * scale the three columns no longer fit a phone's width, so each row stacks the quant over size and fit (still
 * one height for every row).
 */
@Immutable
private class QuantGrid(val stacked: Boolean, val sizeWidth: Dp, val fitWidth: Dp, val rowHeight: Dp)

@Composable
private fun rememberQuantGrid(): QuantGrid {
    val density = LocalDensity.current
    return remember(density) {
        with(density) {
            val stacked = fontScale > 1.3f
            QuantGrid(
                stacked = stacked,
                sizeWidth = 68.sp.toDp(),
                fitWidth = 96.sp.toDp(),
                rowHeight = if (stacked) 18.sp.toDp() * 2 + DemoSpacing.item + DemoSpacing.inline else DemoSize.target,
            )
        }
    }
}

/** The column heads over [QuantRow]s ("Quant", "Size", "On this phone"), on the same grid. */
@Composable
fun QuantHeader(quant: String, size: String, fit: String, modifier: Modifier = Modifier) {
    val colors = LocalDemoColors.current
    val grid = rememberQuantGrid()
    val style = DemoType.labelSmall
    Row(modifier.fillMaxWidth().padding(QuantPadding).padding(bottom = DemoSpacing.inline)) {
        Text(
            quant.uppercase(),
            Modifier.weight(1f),
            color = colors.muted,
            style = style,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (!grid.stacked) {
            Text(
                size.uppercase(),
                Modifier.width(grid.sizeWidth),
                color = colors.muted,
                style = style,
                maxLines = 1,
            )
        }
        Text(
            fit.uppercase(),
            if (grid.stacked) Modifier else Modifier.width(grid.fitWidth),
            color = colors.muted,
            style = style,
            maxLines = 1,
            textAlign = TextAlign.End,
        )
    }
}

/**
 * One file of a model in the Advanced list: the [quant] name, its [size] and how it runs here ([fit] in the ink of
 * [tint]), in three aligned columns. Every row has the same height, whatever it holds. [selected] outlines it;
 * [edgeLabel] puts "Recommended" beside the quant's name. An [included] row (the image reader) can't be picked and is
 * not outlined.
 */
@Composable
fun QuantRow(
    quant: String,
    size: String,
    fit: String,
    tint: CardTint,
    selected: Boolean,
    modifier: Modifier = Modifier,
    included: Boolean = false,
    edgeLabel: String? = null,
    onClick: () -> Unit = {},
) {
    val colors = LocalDemoColors.current
    val grid = rememberQuantGrid()
    val quantChip: @Composable () -> Unit = {
        Text(
            quant,
            Modifier
                .background(colors.accentSoft, DemoShapes.chip)
                .heightIn(min = DemoSize.tag)
                .padding(horizontal = DemoSpacing.block)
                .wrapContentHeight(Alignment.CenterVertically),
            color = colors.accent,
            style = DemoType.code,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
    // "Recommended" sits beside the quant's name, inside the row, so every row is as tall as the others and no label
    // covers a card's edge
    val nameCluster: @Composable () -> Unit = {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(DemoSpacing.block),
        ) {
            quantChip()
            if (edgeLabel != null) EdgeChip(edgeLabel)
        }
    }
    val sizeText: @Composable (Modifier) -> Unit = { m ->
        Text(
            size,
            m,
            color = colors.ink,
            style = DemoType.label.copy(fontFeatureSettings = "tnum"),
            maxLines = 1,
        )
    }
    DemoCard(
        modifier = modifier.fillMaxWidth(),
        selected = selected,
        onClick = if (included) null else onClick,
        contentPadding = QuantPadding,
    ) {
        if (grid.stacked) {
            Column(Modifier.fillMaxWidth().height(grid.rowHeight), verticalArrangement = Arrangement.Center) {
                nameCluster()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    sizeText(Modifier.weight(1f))
                    FitText(fit, tint)
                }
            }
        } else {
            Row(Modifier.fillMaxWidth().height(grid.rowHeight), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { nameCluster() }
                sizeText(Modifier.width(grid.sizeWidth))
                Box(Modifier.width(grid.fitWidth), contentAlignment = Alignment.CenterEnd) { FitText(fit, tint) }
            }
        }
    }
}

/**
 * A model in the library: [title] (the friendly or real name), [subtitle] ("Qwen3.5 0.8B · 0.7 GB") and [trailing]
 * ("used today"), a [ListRow] in its own card. The default model carries a [defaultLabel] chip.
 */
@Composable
fun LibraryRow(
    title: String,
    subtitle: String,
    trailing: String,
    modifier: Modifier = Modifier,
    defaultLabel: String? = null,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
) {
    DemoCard(
        modifier = modifier.fillMaxWidth(),
        onClick = onClick,
        onLongClick = onLongClick,
        contentPadding = PaddingValues(0.dp),
    ) {
        ListRow(
            title,
            subtitle = subtitle,
            trailing = trailing,
            end = if (defaultLabel != null) ({ EdgeChip(defaultLabel) }) else null,
        )
    }
}

/**
 * A download in the library: [title], its controls ([actionLabel], "Pause" or "Resume", and ✕), a [ThinBar] at
 * [fraction], then [detail] ("310 of 740 MB · 9.8 MB/s") and [timeLeft] ("about 40 s").
 */
@Composable
fun DownloadRow(
    title: String,
    fraction: Float,
    detail: String,
    timeLeft: String,
    actionLabel: String,
    onAction: () -> Unit,
    cancelLabel: String,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalDemoColors.current
    // the 48 dp action row already has 12 dp above the title: no padding of its own above; 4 dp round the ✕ puts its
    // glyph on the 16 dp edge
    DemoCard(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(start = DemoSpacing.item, end = DemoSpacing.inline, bottom = DemoSpacing.item),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(DemoSpacing.block),
        ) {
            Text(
                title,
                Modifier.weight(1f),
                color = colors.ink,
                style = DemoType.title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            QuietButton(text = actionLabel, onClick = onAction, bordered = true)
            IconAction(DemoIcons.Close, cancelLabel, onClick = onCancel, muted = true)
        }
        Column(
            Modifier.padding(end = DemoSpacing.compact),
            verticalArrangement = Arrangement.spacedBy(DemoSpacing.block),
        ) {
            ThinBar(fraction)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(detail, Modifier.weight(1f), color = colors.muted, style = DemoType.bodySmall, maxLines = 1)
                Text(timeLeft, color = colors.muted, style = DemoType.bodySmall, maxLines = 1)
            }
        }
    }
}

/** The tick at the end of a chosen row (Default model, Layout). */
@Composable
fun SelectedMark(modifier: Modifier = Modifier) {
    Icon(
        DemoIcons.Check,
        contentDescription = "Selected",
        tint = LocalDemoColors.current.accent,
        modifier = modifier,
    )
}

/**
 * A row that is removed by swiping it to the start. The swipe only asks: [onRemove] is called and the row springs
 * back, so the screen can confirm first. Swiping is not the only way: a screen reader gets a "[label]" action.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SwipeToRemove(label: String, onRemove: () -> Unit, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val colors = LocalDemoColors.current

    // The swipe only asks: `confirmValueChange` answers "no" so the row springs back while the screen confirms. The
    // callback is deprecated in favour of dynamic anchors, but the replacements tried (an effect on `currentValue`,
    // with `reset()`) left the row stuck after a second swipe on the phone; this one is the tested behaviour.
    @Suppress("DEPRECATION")
    val state =
        androidx.compose.material3.rememberSwipeToDismissBoxState(
            confirmValueChange = { target ->
                if (target == SwipeToDismissBoxValue.EndToStart) onRemove()
                false
            },
        )
    SwipeToDismissBox(
        state = state,
        modifier =
            modifier.semantics {
                customActions =
                    listOf(
                        CustomAccessibilityAction(label) {
                            onRemove()
                            true
                        },
                    )
            },
        enableDismissFromStartToEnd = false,
        backgroundContent = {
            // only while the row is being pulled: at rest the label would sit in the tree, unseen, for a screen reader
            // to read
            if (state.dismissDirection == SwipeToDismissBoxValue.EndToStart) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .clip(DemoShapes.card)
                        .background(colors.canvas)
                        .padding(end = DemoSpacing.item),
                    contentAlignment = Alignment.CenterEnd,
                ) {
                    // a removal speaks in the danger colour, on a ground the contrast test holds it to 4.5:1 against
                    Text(label, color = colors.danger, style = DemoType.label)
                }
            }
        },
        content = { content() },
    )
}

/** One chat in a [GroupedLazyList]. */
@Immutable
data class ListEntry(val key: Long, val title: String, val selected: Boolean)

/** A labelled run of chats in a [GroupedLazyList] ("Today", "This week"). */
@Immutable
data class ListGroup(val label: String, val entries: List<ListEntry>)

/**
 * The drawer's chats: each group's upper-case [label], then its rows, full width and [dense] with their own 16 dp, the
 * open chat ticked. The rows reach the drawer's edges, so the label's own inset puts it on their text. Lazy, so a long
 * history costs only the rows on screen. Groups are a [Gap.Section] apart.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GroupedLazyList(
    groups: List<ListGroup>,
    onClick: (Long) -> Unit,
    onLongClick: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(modifier.fillMaxWidth()) {
        for ((index, group) in groups.withIndex()) {
            item(key = "label-${group.label}", contentType = "label") {
                // groups are a section apart; the first has the gap the drawer gives above the list
                SectionLabel(group.label, Modifier.padding(top = if (index == 0) 0.dp else DemoSpacing.section))
            }
            items(group.entries, key = { it.key }, contentType = { "chat" }) { entry ->
                ListRow(
                    title = entry.title,
                    selected = entry.selected,
                    dense = true,
                    onClick = { onClick(entry.key) },
                    onLongClick = { onLongClick(entry.key) },
                )
            }
        }
    }
}
