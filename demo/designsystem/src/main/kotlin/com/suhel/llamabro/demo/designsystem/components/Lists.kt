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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.suhel.llamabro.demo.designsystem.theme.DemoShapes
import com.suhel.llamabro.demo.designsystem.theme.DemoSize
import com.suhel.llamabro.demo.designsystem.theme.DemoSpacing
import com.suhel.llamabro.demo.designsystem.theme.DemoType
import com.suhel.llamabro.demo.designsystem.theme.LocalDemoColors

/**
 * A small uppercase section heading ("Picked for your phone", "Today"), with an optional link at the end ("What's a
 * model?") or a muted [note] there instead ("6.1 GB · 120 GB free", what the section's models take). It is inset by the
 * cards' and rows' own padding ([DemoSpacing.item]), on a screen and on a sheet alike, so it starts where the text of
 * the cards and rows under it starts, not on the edge of their surface; [inset] false puts it on the gutter, where a
 * sheet's own lines start (a [RuledLabel] over a page). At large font ([isLargeText]) the heading wraps
 * and the action or the note moves under it, so the heading is never the part that is cut.
 * [quiet] is the Models page's label (D123): sentence case, Regular, soft, with no tracking.
 */
@Composable
fun SectionLabel(
    text: String,
    modifier: Modifier = Modifier,
    action: String? = null,
    onAction: (() -> Unit)? = null,
    note: String? = null,
    inset: Boolean = true,
    quiet: Boolean = false,
) {
    val colors = LocalDemoColors.current
    val edge = modifier.fillMaxWidth().padding(start = if (inset) DemoSpacing.item else 0.dp)
    val heading = if (quiet) text else text.uppercase()
    val headingInk = if (quiet) colors.soft else colors.muted
    val headingStyle = if (quiet) DemoType.quietLabel else DemoType.labelSmall
    val noteInk = if (quiet) colors.soft else colors.muted
    if (isLargeText()) {
        Column(edge) {
            Text(heading, color = headingInk, style = headingStyle)
            if (action != null) QuietButton(text = action, onClick = onAction ?: {})
            if (note != null) Text(note, color = noteInk, style = DemoType.bodySmall)
        }
        return
    }
    Row(edge, verticalAlignment = Alignment.CenterVertically) {
        Text(
            heading,
            Modifier.weight(1f),
            color = headingInk,
            style = headingStyle,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        // with an action the row is the action's 48 dp, like Material's list subheader: a target that reaches past
        // its row is cut by the parent's bounds, and accessibility services see only what's left (F130)
        if (action != null) QuietButton(text = action, onClick = onAction ?: {})
        // the note ends where the rows' text ends, inset like the heading
        if (note != null) {
            Text(
                note,
                Modifier.padding(start = DemoSpacing.block, end = DemoSpacing.item),
                color = noteInk,
                style = DemoType.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * A muted line under a section's cards ("Changing this reloads the model."): inset like [SectionLabel], so it starts on
 * the text of the cards above it, not on the edge of their surface. With an [action] ("Reset") the line is a touch
 * target tall and the action, a quiet underlined word, ends it; the words wrap before the action gives way.
 */
@Composable
fun SectionNote(text: String, modifier: Modifier = Modifier, action: String? = null, onAction: () -> Unit = {}) {
    val line = @Composable { textModifier: Modifier ->
        Text(text, textModifier, color = LocalDemoColors.current.muted, style = DemoType.bodySmall)
    }
    if (action == null) {
        line(modifier.padding(start = DemoSpacing.item))
        return
    }
    Row(
        modifier.fillMaxWidth().padding(start = DemoSpacing.item).heightIn(min = DemoSize.target),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DemoSpacing.block),
    ) {
        line(Modifier.weight(1f))
        QuietButton(action, onAction)
    }
}

/**
 * Where what follows comes from ("From the model's page on Hugging Face"): a [SectionLabel] over a hairline, so the
 * author's words below don't read as the app's own. Both start on the gutter, as every other line of
 * a sheet does, one inset.
 */
@Composable
fun RuledLabel(text: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(DemoSpacing.inline)) {
        SectionLabel(text, inset = false)
        Box(Modifier.fillMaxWidth().height(DemoSize.hairline).background(LocalDemoColors.current.hairline))
    }
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

private val QuantPadding = PaddingValues(horizontal = DemoSpacing.item)

/**
 * The quant table's grid: the size and fit columns are sized in sp so they grow with the font. At large font
 * ([LARGE_TEXT_SCALE]) the three columns no longer fit a phone's width, so each row stacks the quant over size and fit
 * (still one height for every row).
 */
@Immutable
private class QuantGrid(val stacked: Boolean, val sizeWidth: Dp, val fitWidth: Dp, val rowHeight: Dp)

@Composable
private fun rememberQuantGrid(): QuantGrid {
    val density = LocalDensity.current
    return remember(density) {
        with(density) {
            val stacked = fontScale >= LARGE_TEXT_SCALE
            QuantGrid(
                stacked = stacked,
                sizeWidth = DemoSize.quantSizeColumn.toDp(),
                fitWidth = DemoSize.quantFitColumn.toDp(),
                rowHeight =
                    if (stacked) {
                        DemoSize.quantStackedLine.toDp() * 2 + DemoSpacing.item + DemoSpacing.inline
                    } else {
                        DemoSize.target
                    },
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
 * One file of a model in the Advanced list: the [quant] name, its [size] and how it runs here, [fit] in the ink of
 * [tint], in three aligned columns. Every row has the same height, whatever it holds. [selected] outlines it;
 * [edgeLabel] puts "Recommended" beside the quant's name. An [included] row (the image reader, a file already on the
 * phone) can't be picked and is not outlined. [note] ("Recommended", "On phone") is a word under the quant instead of
 * a chip beside it; [notes] reserves its line on every row of a list, so the rows stay one height.
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
) = QuantRow(quant, size, selected, modifier, included, edgeLabel, onClick, symbol = { FitText(fit, tint) })

/**
 * As the [QuantRow] with words, with a [symbol] (such as a [FitSymbol]) in the fit's column instead. The quant is plain
 * label text, not a pill (a pill is a state's), and the quant and size share a baseline. A
 * [flat] row has no card edge: a fact under the list (the image reader, "Included"), not a choice.
 */
@Composable
fun QuantRow(
    quant: String,
    size: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    included: Boolean = false,
    edgeLabel: String? = null,
    onClick: () -> Unit = {},
    note: String? = null,
    notes: Boolean = note != null,
    flat: Boolean = false,
    symbol: @Composable () -> Unit,
) {
    val colors = LocalDemoColors.current
    val grid = rememberQuantGrid()
    val chips: @Composable () -> Unit = {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(DemoSpacing.block),
        ) {
            Text(quant, color = colors.ink, style = DemoType.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (edgeLabel != null) EdgeChip(edgeLabel)
        }
    }
    val twoLines =
        with(LocalDensity.current) { DemoType.label.lineHeight.toDp() + DemoType.bodySmall.lineHeight.toDp() }
    val nameCluster: @Composable (Boolean) -> Unit = { stacked ->
        if (notes && note == null && !stacked) {
            // no note: the quant is centred in the note's reserved height, so the row isn't top-heavy and its symbol
            // and size sit on the quant's line
            Box(Modifier.height(twoLines), contentAlignment = Alignment.CenterStart) { chips() }
        } else if (notes && note != null) {
            Column {
                chips()
                Text(
                    note,
                    color = colors.muted,
                    style = DemoType.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        } else {
            chips()
        }
    }
    val sizeText: @Composable (Modifier) -> Unit = { m ->
        Text(size, m, color = colors.ink, style = DemoType.label.copy(fontFeatureSettings = "tnum"), maxLines = 1)
    }
    val body: @Composable () -> Unit = {
        if (grid.stacked) {
            // stacked, the note is a line of its own on every row of the list
            val noteLine = if (notes) with(LocalDensity.current) { DemoSize.quantStackedLine.toDp() } else 0.dp
            Column(
                Modifier.fillMaxWidth().height(grid.rowHeight + noteLine),
                verticalArrangement = Arrangement.Center,
            ) {
                // a row without a note has no blank line between its quant and its size
                nameCluster(true)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    sizeText(Modifier.weight(1f))
                    symbol()
                }
            }
        } else {
            Row(Modifier.fillMaxWidth().height(grid.rowHeight), verticalAlignment = Alignment.CenterVertically) {
                // the quant's first line and the size share a baseline; the symbol sits centred in its column
                Box(Modifier.weight(1f).alignByBaseline()) { nameCluster(false) }
                sizeText(Modifier.width(grid.sizeWidth).alignByBaseline())
                Box(Modifier.width(grid.fitWidth), contentAlignment = Alignment.CenterEnd) { symbol() }
            }
        }
    }
    if (flat) {
        Box(modifier.fillMaxWidth().padding(QuantPadding)) { body() }
    } else {
        DemoCard(
            // a file that can't be picked (on the phone) looks as Basic's size on the phone does
            modifier = modifier.fillMaxWidth().then(if (included) Modifier.alpha(UNAVAILABLE_ALPHA) else Modifier),
            selected = selected,
            onClick = if (included) null else onClick,
            contentPadding = QuantPadding,
        ) { body() }
    }
}

/**
 * A row that is removed by swiping it to the start. The swipe only asks: [onRemove] is called and the row springs
 * back, so the screen can confirm first. Swiping is not the only way: a screen reader gets a "[label]" action. Not
 * [enabled], the row neither swipes nor offers the action, but stays the same node: a list's row whose model lands on
 * the phone keeps its composition, so its footer folds away and its pill fades in.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SwipeToRemove(
    label: String,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    /** The row's own corners, so the swipe's ground matches the card it uncovers (a model row's, D123). */
    shape: Shape = DemoShapes.card,
    content: @Composable () -> Unit,
) {
    val colors = LocalDemoColors.current
    // the state keeps the callback it was made with (Material saves it), and a list's row can outlive its first
    // entry (a suggestion that is got and lands is one composition): the swipe calls the latest [onRemove]
    val remove by rememberUpdatedState(onRemove)

    // The swipe only asks: `confirmValueChange` answers "no" so the row springs back while the screen confirms. The
    // callback is deprecated in favour of dynamic anchors, but the replacements tried (an effect on `currentValue`,
    // with `reset()`) left the row stuck after a second swipe on the phone; this one is the tested behaviour.
    @Suppress("DEPRECATION")
    val state =
        androidx.compose.material3.rememberSwipeToDismissBoxState(
            confirmValueChange = { target ->
                if (target == SwipeToDismissBoxValue.EndToStart) remove()
                false
            },
        )
    SwipeToDismissBox(
        state = state,
        modifier =
            modifier.semantics {
                if (enabled) {
                    customActions =
                        listOf(
                            CustomAccessibilityAction(label) {
                                onRemove()
                                true
                            },
                        )
                }
            },
        enableDismissFromStartToEnd = false,
        enableDismissFromEndToStart = enabled,
        gesturesEnabled = enabled,
        backgroundContent = {
            // only while the row is being pulled: at rest the label would sit in the tree, unseen, for a screen reader
            // to read
            if (state.dismissDirection == SwipeToDismissBoxValue.EndToStart) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .clip(shape)
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

/** One chat in a [GroupedLazyList]: its [title], and its [trailing] time ("2:14 pm", "Thu", "12 Nov"). */
@Immutable
data class ListEntry(val key: Long, val title: String, val selected: Boolean, val trailing: String? = null)

/** A labelled run of chats in a [GroupedLazyList] ("Today", "This week"). */
@Immutable
data class ListGroup(val label: String, val entries: List<ListEntry>)

/**
 * The sidebar's chats (D126): each group's quiet [label] (sentence case, soft), then its [LineRow]s, the open chat on
 * a soft fill. The rows sit 12 dp in from the drawer's edges with 12 dp inside, so their text and the labels start on
 * one 24 dp line. Lazy, so a long history costs only the rows on screen. A label breathes above (a section, 24 dp; the
 * first only a block, under the search) and sits tight on its rows.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GroupedLazyList(
    groups: List<ListGroup>,
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
                LineRow(
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
fun LineRow(
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
