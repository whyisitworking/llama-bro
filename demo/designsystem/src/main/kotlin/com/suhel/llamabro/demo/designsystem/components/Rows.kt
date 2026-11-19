package com.suhel.llamabro.demo.designsystem.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
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
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.suhel.llamabro.demo.designsystem.theme.DemoSize
import com.suhel.llamabro.demo.designsystem.theme.DemoSpacing
import com.suhel.llamabro.demo.designsystem.theme.DemoType
import com.suhel.llamabro.demo.designsystem.theme.LocalDemoColors

/**
 * A list row (Material's list item): an optional [leading] icon, the [title] over an optional [subtitle], then at the
 * end [trailing] text, an [end] slot, a check when [selected], and a [chevron] when it opens a page. One line is 56 dp
 * tall ([dense], 48 dp: still a whole touch target), two lines 72 dp. 16 dp sits between its parts and 16 dp inside
 * its own edges, so a row belongs in something that has no padding of its own: a [GroupedList], a card, the drawer.
 * The title is the same weight on every row; emphasis is for a card's title, not a row's.
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
        if (leading !=
            null
        ) {
            Icon(leading, contentDescription = null, tint = colors.ink, modifier = Modifier.size(DemoSize.icon))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(DemoSpacing.inline)) {
            Text(title, color = colors.ink, style = DemoType.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) {
                Text(
                    subtitle,
                    color = colors.muted,
                    style = DemoType.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (trailing != null) {
            Text(trailing, color = colors.muted, style = DemoType.bodyMedium, maxLines = 1, textAlign = TextAlign.End)
        }
        if (end != null) end()
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
    content: @Composable ColumnScope.() -> Unit,
) {
    DemoCard(
        modifier.fillMaxWidth(),
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
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                title,
                Modifier.weight(1f),
                color = colors.ink,
                style = DemoType.title,
                maxLines = 1,
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
