package com.suhel.llamabro.demo.designsystem.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.suhel.llamabro.demo.designsystem.theme.DemoShapes
import com.suhel.llamabro.demo.designsystem.theme.DemoSize
import com.suhel.llamabro.demo.designsystem.theme.DemoSpacing
import com.suhel.llamabro.demo.designsystem.theme.DemoType
import com.suhel.llamabro.demo.designsystem.theme.LocalDemoColors

/**
 * A list row (Material's list item): an optional [leading] icon, the [title] over an optional [subtitle], then at the
 * end [trailing] text, an [end] slot, a [control], a check when [selected], and a [chevron] when it opens a page. One
 * line is 56 dp
 * tall ([dense], 48 dp: still a whole touch target), two lines 72 dp. 16 dp sits between its parts and 16 dp inside
 * its own edges, so a row belongs in something that has no padding of its own: a [GroupedList], a card, the drawer.
 * The title is the same weight on every row; emphasis is for a card's title, not a row's. [titleStyle] steps it down
 * where the surface's one title-sized line is something else (a model card's sizes, under its state line).
 *
 * The title never loses its words to what follows it. [trailing] and [end] are text and status
 * marks (a time, a chip, a fit): below large font they sit at the end and a long title wraps beside them; at large
 * font ([isLargeText]) they move under the title and its subtitle. A [control] (a switch, a button such as "Unload")
 * acts on the row, so it stays at the end at every font scale and the title wraps beside it, as do the check and the
 * chevron.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ListRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    leading: ImageVector? = null,
    trailing: String? = null,
    selected: Boolean = false,
    chevron: Boolean = false,
    dense: Boolean = false,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    end: (@Composable () -> Unit)? = null,
    control: (@Composable () -> Unit)? = null,
    titleStyle: TextStyle = DemoType.title,
) {
    val colors = LocalDemoColors.current
    Row(
        modifier
            .fillMaxWidth()
            .then(if (selected) Modifier.semantics { this.selected = true } else Modifier)
            .then(
                if (onClick != null || onLongClick != null) {
                    Modifier.combinedClickable(onClick = onClick ?: {}, onLongClick = onLongClick)
                } else {
                    Modifier
                },
            ).heightIn(
                min =
                    when {
                        subtitle != null -> DemoSize.rowTwoLine

                        dense -> DemoSize.target

                        // a dense row is one touch target tall
                        else -> DemoSize.field
                    },
            ).padding(horizontal = DemoSpacing.item, vertical = DemoSpacing.block),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DemoSpacing.item),
    ) {
        if (leading != null) {
            Icon(leading, contentDescription = null, tint = colors.ink, modifier = Modifier.size(DemoSize.icon))
        }
        val large = isLargeText()
        TitleFirst(
            stacked = large,
            modifier = Modifier.weight(1f),
            title = {
                Text(
                    title,
                    color = colors.ink,
                    style = titleStyle,
                    maxLines = TITLE_LINES,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            subtitle = {
                if (subtitle != null) {
                    Text(
                        subtitle,
                        color = colors.muted,
                        style = DemoType.bodyMedium,
                        // at large font a settings line ("The model in memory, and when it's freed") needs a third
                        maxLines = if (large) SUBTITLE_LINES_LARGE else SUBTITLE_LINES,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            },
            trailing = if (trailing != null || end != null) ({ RowTrailing(trailing, end) }) else null,
        )
        if (control != null) control()
        if (selected) {
            Icon(
                DemoIcons.Check,
                contentDescription = null,
                tint = colors.accent,
                modifier = Modifier.size(DemoSize.icon),
            )
        }
        if (chevron) {
            Icon(
                DemoIcons.ChevronRight,
                contentDescription = null,
                tint = colors.muted,
                modifier = Modifier.size(DemoSize.icon),
            )
        }
    }
}

/**
 * A setting that is on or off ("Sees images"): a [ListRow] whose whole width toggles it, with a switch at its end and
 * its caveat as the [subtitle], one muted line. A screen reader hears one switch with the title and its state. The
 * switch is the one accent (on: the accent track; off: a muted ring on the surface), so it adds no colour (D123).
 */
@Composable
fun SwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
) {
    val colors = LocalDemoColors.current
    ListRow(
        title,
        modifier.toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange),
        subtitle = subtitle,
        control = {
            Switch(
                checked = checked,
                onCheckedChange = null,
                colors =
                    SwitchDefaults.colors(
                        checkedThumbColor = colors.onAccent,
                        checkedTrackColor = colors.accent,
                        checkedBorderColor = colors.accent,
                        uncheckedThumbColor = colors.muted,
                        uncheckedTrackColor = colors.surface,
                        uncheckedBorderColor = colors.muted,
                    ),
            )
        },
    )
}

/**
 * A place to go from a menu (the sidebar's Models and Settings, D126): a muted [icon], the [title], and at the end a
 * soft [note] ("3 on this phone"). One touch target tall, rounded like a card where it presses. A screen reader hears
 * one button, "[title], [note]" ("Models, 3 on this phone"), or the title alone while there is no note; the visible
 * words are not read twice.
 */
@Composable
fun NavRow(
    icon: ImageVector,
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    note: String? = null,
) {
    val colors = LocalDemoColors.current
    val spoken = if (note == null) title else "$title, $note"
    // named apart from the semantics block's own `onClick`, which would shadow the parameter inside it
    val click = onClick
    Row(
        modifier
            .fillMaxWidth()
            .clip(DemoShapes.card)
            .clickable(role = Role.Button, onClick = click)
            .clearAndSetSemantics {
                contentDescription = spoken
                role = Role.Button
                onClick {
                    click()
                    true
                }
            }.heightIn(min = DemoSize.target)
            .padding(horizontal = DemoSpacing.compact),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DemoSpacing.item),
    ) {
        Icon(icon, contentDescription = null, tint = colors.muted, modifier = Modifier.size(DemoSize.icon))
        Text(
            title,
            Modifier.weight(1f),
            color = colors.ink,
            style = DemoType.rowLine,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (note != null) Text(note, color = colors.soft, style = DemoType.quietLabel, maxLines = 1)
    }
}

/** A row's [trailing] text and its [end] slot, side by side; the text ellipsises before the slot gives way. */
@Composable
private fun RowTrailing(trailing: String?, end: (@Composable () -> Unit)?) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DemoSpacing.item),
    ) {
        if (trailing != null) {
            Text(
                trailing,
                Modifier.weight(1f, fill = false),
                color = LocalDemoColors.current.muted,
                style = DemoType.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.End,
            )
        }
        if (end != null) end()
    }
}

/**
 * A row's text block: the [title] over its [subtitle], with the [trailing] parts beside them, or under them when
 * [stacked]. Beside, the trailing parts take what the title's line leaves but never more than half the row, and the
 * title wraps in the rest.
 */
@Composable
private fun TitleFirst(
    stacked: Boolean,
    modifier: Modifier,
    title: @Composable () -> Unit,
    subtitle: @Composable () -> Unit,
    trailing: (@Composable () -> Unit)?,
) {
    Layout(contents = listOf(title, subtitle, trailing ?: {}), modifier = modifier) { slots, constraints ->
        val (titles, subtitles, trailings) = slots
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else constraints.minWidth
        val gap = DemoSpacing.item.roundToPx()
        val line = DemoSpacing.inline.roundToPx()
        val titleNeeds = titles.sumOf { it.maxIntrinsicWidth(Constraints.Infinity) }
        val beside = trailings.isEmpty() || !stacked
        val trailingMax = if (beside) maxOf(width / 2, width - gap - titleNeeds).coerceAtLeast(0) else width
        val trailingPlaced = trailings.map { it.measure(Constraints(maxWidth = trailingMax)) }
        val textWidth =
            if (beside && trailingPlaced.isNotEmpty()) {
                (width - gap - trailingPlaced.maxOf { it.width }).coerceAtLeast(0)
            } else {
                width
            }
        val text = Constraints(maxWidth = textWidth)
        val titlePlaced = titles.map { it.measure(text) }
        val subtitlePlaced = subtitles.map { it.measure(text) }
        val block = (titlePlaced + subtitlePlaced)
        val blockHeight = block.sumOf { it.height } + line * (block.size - 1).coerceAtLeast(0)
        val trailingHeight = trailingPlaced.maxOfOrNull { it.height } ?: 0
        val height =
            if (beside) {
                maxOf(blockHeight, trailingHeight)
            } else {
                blockHeight + line + trailingHeight
            }.coerceAtLeast(constraints.minHeight)
        layout(width, height) {
            var y = if (beside) (height - blockHeight) / 2 else 0
            for (part in block) {
                part.placeRelative(0, y)
                y += part.height + line
            }
            for (part in trailingPlaced) {
                if (beside) {
                    part.placeRelative(width - part.width, (height - part.height) / 2)
                } else {
                    part.placeRelative(0, y)
                }
            }
        }
    }
}

/** A row's title wraps rather than give way; past this many lines it ellipsises. */
private const val TITLE_LINES = 3

/** A subtitle's lines before it ellipsises, at normal and at large font. */
private const val SUBTITLE_LINES = 2
private const val SUBTITLE_LINES_LARGE = 3

/** What a [GroupedList] collects: its rows, in order. */
@Stable
class GroupScope internal constructor() {
    internal val rows = mutableListOf<@Composable () -> Unit>()

    /** Adds a row to the group. */
    fun row(content: @Composable () -> Unit) {
        rows += content
    }
}

/**
 * A group of rows in one card (Android's settings pattern): a 1 dp divider, inset 16 dp, between the rows, none after
 * the last. A card of the app's usual 12 dp corners on the surface (the canvas on a sheet). A row that opens a page
 * has a chevron; a group of one is an action ("Get more models").
 */
@Composable
fun GroupedList(modifier: Modifier = Modifier, content: GroupScope.() -> Unit) {
    val colors = LocalDemoColors.current
    val rows = GroupScope().apply(content).rows
    DemoCard(modifier.fillMaxWidth(), contentPadding = PaddingValues(0.dp)) {
        rows.forEachIndexed { index, row ->
            if (index > 0) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(
                            start = DemoSpacing.item,
                        ).height(DemoSize.hairline)
                        .background(colors.hairline),
                )
            }
            row()
        }
    }
}

/**
 * The mark of one choice among several: a ring, with a dot in the accent when [selected] (models audit S21: an
 * outline alone didn't say a choice was made). Muted when it can't be chosen ([enabled] false). It only draws; the row
 * it sits in is the target and says whether it is selected.
 */
@Composable
fun RadioMark(selected: Boolean, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val colors = LocalDemoColors.current
    val ink =
        when {
            !enabled -> colors.hairline
            selected -> colors.accent
            else -> colors.muted
        }
    Box(
        modifier.size(DemoSize.radioMark).border(DemoSize.selectedStroke, ink, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) Box(Modifier.size(DemoSize.radioDot).background(ink, CircleShape))
    }
}

/**
 * How a choice that can't be made is drawn, wherever it is listed (Basic's size on the phone, Advanced's file on the
 * phone): Material's disabled content opacity.
 */
const val UNAVAILABLE_ALPHA = 0.38f

/**
 * A card that holds a [ListRow] edge to edge: [selected] outlines it in the accent, the one signal that a choice is
 * made. For a list of choices (the sizes of a model, the models of a library) where each row is its own card.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RowCard(
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    /** False for a choice that can't be made (a size already on the phone): drawn at [UNAVAILABLE_ALPHA]. */
    available: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    DemoCard(
        modifier.fillMaxWidth().then(if (available) Modifier else Modifier.alpha(UNAVAILABLE_ALPHA)),
        selected = selected,
        onClick = onClick,
        onLongClick = onLongClick,
        contentPadding = PaddingValues(0.dp),
        content = content,
    )
}

/**
 * A value set with a slider (Material's slider in a list row), for a group of settings such as sampling: the [title]
 * and the value as [valueText] on one line, a [note] under them (where the value comes from), then the slider over the
 * row's width. [steps] are the stops between the ends, as Material's slider counts them; the ticks are not drawn, as
 * there can be a hundred. The row keeps the value under the finger while it moves and calls [onValueChange] once, when
 * the finger lifts or a key moves it, so a setting is written once per change, not once per frame.
 */
@Composable
fun SliderRow(
    title: String,
    value: Float,
    valueText: (Float) -> String,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    note: String? = null,
) {
    val colors = LocalDemoColors.current
    var moving by remember(value) { mutableStateOf<Float?>(null) }
    val shown = moving ?: value
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = DemoSpacing.item, vertical = DemoSpacing.block),
        verticalArrangement = Arrangement.spacedBy(DemoSpacing.inline),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(DemoSpacing.block),
        ) {
            // at large font a long name ("Presence penalty") wraps beside its value rather than lose its end
            Text(
                title,
                Modifier.weight(1f),
                color = colors.ink,
                style = DemoType.title,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(valueText(shown), color = colors.ink, style = DemoType.title, maxLines = 1)
        }
        if (note != null) Text(note, color = colors.muted, style = DemoType.bodyMedium, maxLines = 1)
        Slider(
            value = shown,
            onValueChange = { moving = it },
            onValueChangeFinished = { moving?.let(onValueChange) },
            valueRange = range,
            steps = steps,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = DemoSize.target)
                    .semantics {
                        contentDescription = title
                        stateDescription = valueText(shown)
                    },
            colors =
                SliderDefaults.colors(
                    thumbColor = colors.accent,
                    activeTrackColor = colors.accent,
                    inactiveTrackColor = colors.hairline,
                    activeTickColor = Color.Transparent,
                    inactiveTickColor = Color.Transparent,
                ),
        )
    }
}
