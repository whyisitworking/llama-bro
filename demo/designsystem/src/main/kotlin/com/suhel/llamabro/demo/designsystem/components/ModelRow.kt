package com.suhel.llamabro.demo.designsystem.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.snap
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.AlignmentLine
import androidx.compose.ui.layout.HorizontalAlignmentLine
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.VerticalAlignmentLine
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import com.suhel.llamabro.demo.designsystem.theme.DemoShapes
import com.suhel.llamabro.demo.designsystem.theme.DemoSize
import com.suhel.llamabro.demo.designsystem.theme.DemoSpacing
import com.suhel.llamabro.demo.designsystem.theme.DemoType
import com.suhel.llamabro.demo.designsystem.theme.LocalDemoColors
import com.suhel.llamabro.demo.designsystem.theme.animationsDisabled
import kotlin.math.min

/**
 * One model, wherever a list shows one (models-page design §3, the "ultra subtle" design, D123): two lines and
 * the fit symbol on the right, centred on both, so the row is balanced.
 *
 * 1. The [name] (Regular, in the ink whatever the model can do: its fit symbol says it can't chat; never the first
 *    thing cut).
 * 2. One muted line: "[maker] · [size]" (the whole download's size), the [capabilities] as soft light icons with no
 *    words, then the quiet [fact] (a Hugging Face result's download count) and the [state] mark ("Default",
 *    "Current", "On phone"), small and soft, never a pill; each fades through as it changes.
 * 3. The [download] footer, a compact [DownloadPanel], in every download stage, so a row keeps one height while its
 *    download changes. When the download lands or goes, the footer fades and folds away together, and when one
 *    starts it unfolds, then fades in, on [rowSizeSpec], the spec the list moves the row's neighbours with.
 *
 * [onLongClick] is the row's second action (the chat's switcher: "Use for new chats"). [onInfo] adds an info action
 * beside the fit symbol, its own 48 dp tap over the lines, "About [name]" to a screen reader (the switcher opens the
 * model's card with it): the row keeps the page's height and the fit column's right edge.
 *
 * At normal size a row is one height with or without a state and before or after its fit is known. At large font
 * ([isLargeText]) the fit moves under the name, so the name keeps the row's width; the maker and size may take two
 * lines rather than cut the size, and the icons and marks take a line under them, whose height eases as it comes and
 * goes.
 */
@Composable
@Suppress("LongParameterList") // the row's typed slots, one parameter each (models-page design §3)
fun ModelRow(
    name: String,
    fit: FitLevel?,
    fitDescription: String,
    maker: String?,
    size: String?,
    state: String?,
    capabilities: List<Pair<ImageVector, String>>,
    fact: String?,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    onInfo: (() -> Unit)? = null,
    download: (@Composable () -> Unit)? = null,
) {
    val large = isLargeText()
    val still = animationsDisabled()
    // the card's sides are the row's text inset less RowBleed, so the download footer's 48 dp targets fit inside the
    // card while their marks meet the text's edges (F130); the lines take the bleed back
    val side = RowInset - RowBleed
    DemoCard(
        modifier = modifier.fillMaxWidth(),
        onClick = onClick,
        onLongClick = onLongClick,
        contentPadding = PaddingValues(start = side, top = RowInset, end = side, bottom = RowInset),
        shape = DemoShapes.row,
    ) {
        val lines: @Composable () -> Unit = {
            RowLines(
                RowContent(name, fit, fitDescription, maker, size, state, capabilities, fact),
                large,
                still,
                download,
                info = onInfo != null,
                Modifier.fillMaxWidth(),
            )
        }
        if (onInfo == null) {
            lines()
        } else {
            InfoOverLines(lines) { IconAction(DemoIcons.Info, "About $name", onInfo, muted = true, small = true) }
        }
    }
}

/** The row's words and marks, as [ModelRow] takes them. */
private class RowContent(
    val name: String,
    val fit: FitLevel?,
    val fitDescription: String,
    val maker: String?,
    val size: String?,
    val state: String?,
    val capabilities: List<Pair<ImageVector, String>>,
    val fact: String?,
) {
    val makerAndSize: String get() = listOfNotNull(maker, size).joinToString(" · ")
}

/** The row's inner inset, a little more than a card's (D123). */
private val RowInset = DemoSpacing.item + DemoSpacing.inline / 2

/** Where the info action's glyph sits, from [InfoSpot]: before the fit symbol, centred on the lines. */
private val InfoCentreX = VerticalAlignmentLine(::min)
private val InfoCentreY = HorizontalAlignmentLine(::min)

/**
 * The info action's place, beside the fit symbol: an empty box that marks its centre, so the
 * action's 48 dp target is drawn over the row there ([InfoOverLines]) and the row keeps its lines' height and width.
 */
@Composable
private fun InfoSpot() {
    Box(
        Modifier.size(scaledWithText(DemoSize.fitSymbol, DemoSize.fitSymbolMax)).layout { measurable, constraints ->
            val placeable = measurable.measure(constraints)
            val centre = mapOf(InfoCentreX to placeable.width / 2, InfoCentreY to placeable.height / 2)
            layout(placeable.width, placeable.height, centre) { placeable.place(0, 0) }
        },
    )
}

/**
 * The row's [lines] with the info [action] over them, centred on the [InfoSpot]: the target reaches over the lines
 * above and below rather than adding a column or a taller line, and it stays inside this layout, the card's content,
 * so a screen reader gets its whole 48 dp (F130).
 */
@Composable
private fun InfoOverLines(lines: @Composable () -> Unit, action: @Composable () -> Unit) {
    Layout(contents = listOf(lines, action)) { (linesMeasurables, actionMeasurables), constraints ->
        val placed = linesMeasurables.single().measure(constraints)
        val target = actionMeasurables.single().measure(Constraints())
        val x = placed[InfoCentreX]
        val y = placed[InfoCentreY]
        layout(placed.width, placed.height) {
            placed.place(0, 0)
            if (x != AlignmentLine.Unspecified && y != AlignmentLine.Unspecified) {
                target.place(
                    (x - target.width / 2).coerceIn(0, (placed.width - target.width).coerceAtLeast(0)),
                    (y - target.height / 2).coerceIn(0, (placed.height - target.height).coerceAtLeast(0)),
                )
            }
        }
    }
}

/** The row's lines and its download footer: everything but the card and the info action. */
@Composable
private fun RowLines(
    content: RowContent,
    large: Boolean,
    still: Boolean,
    download: (@Composable () -> Unit)?,
    info: Boolean,
    modifier: Modifier,
) {
    Column(modifier) {
        val inset = Modifier.padding(horizontal = RowBleed).fillMaxWidth()
        if (large) {
            Column(inset, verticalArrangement = Arrangement.spacedBy(DemoSpacing.inline)) {
                Name(content.name)
                RightSlot(content, info)
                Text(
                    content.makerAndSize,
                    color = LocalDemoColors.current.muted,
                    style = DemoType.bodySmall,
                    // at large font a long maker would cut the size, the line's fact that matters: it wraps
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                // the icons and marks take a line of their own; a mark arriving or leaving eases its height
                Row(
                    Modifier.animateContentSize(if (still) snap() else rowSizeSpec()),
                    horizontalArrangement = Arrangement.spacedBy(DemoSpacing.inline),
                    verticalAlignment = Alignment.CenterVertically,
                ) { Hints(content) }
            }
        } else {
            // the lines are at least a target tall, so the info action's 48 dp sits whole over them, centred as the
            // fit symbol is, in every row alike (F130)
            Row(
                inset.heightIn(min = DemoSize.target),
                horizontalArrangement = Arrangement.spacedBy(DemoSpacing.block),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(DemoSpacing.inline)) {
                    Name(content.name)
                    Row(
                        Modifier.heightIn(min = scaledWithText(DemoSize.capabilityIcon, DemoSize.capabilityIconMax)),
                        horizontalArrangement = Arrangement.spacedBy(DemoSpacing.inline),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            content.makerAndSize,
                            Modifier.weight(1f, fill = false),
                            color = LocalDemoColors.current.muted,
                            style = DemoType.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Hints(content)
                    }
                }
                RightSlot(content, info)
            }
        }
        CompositionLocalProvider(LocalEdgeBleed provides RowBleed) { DownloadFooter(download, still) }
    }
}

/** The name: Regular, in the ink; two lines before it is ever cut. */
@Composable
private fun Name(name: String) {
    Text(
        name,
        color = LocalDemoColors.current.ink,
        style = DemoType.titleRegular,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
}

/** Line 2's quiet end: the capability icons, the fact, and the state's mark, fading through as it changes. */
@Composable
private fun Hints(content: RowContent) {
    if (content.capabilities.isNotEmpty() || content.fact != null) CapabilityIcons(content.capabilities, content.fact)
    FadeThrough(content.state) { shown -> if (shown != null) QuietItem(shown) }
}

/** The fit symbol, with the info action's place before it when the row has one. */
@Composable
private fun RightSlot(content: RowContent, info: Boolean) {
    if (info) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(DemoSpacing.item),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            InfoSpot()
            FitBox(content.fit, content.fitDescription)
        }
    } else {
        FitBox(content.fit, content.fitDescription)
    }
}

/** How far a row's footer reaches past the text's edges: a small glyph's inset in its 48 dp target. */
private val RowBleed = (DemoSize.target - DemoSize.iconSmall) / 2

/**
 * The download footer under the lines, a [DemoSpacing.block] below them. Present from the first frame of a row in a
 * download state. It unfolds and then fades in when a download starts (its glyphs are never shown cut by the growing
 * row), and fades and folds away together when the download lands or goes, keeping its last panel while it leaves.
 */
@Composable
private fun DownloadFooter(download: (@Composable () -> Unit)?, still: Boolean) {
    val last = remember { mutableStateOf(download) }
    SideEffect { if (download != null) last.value = download }
    // the list motion's order (D120): the footer unfolds once what leaves has faded and its panel fades in from
    // halfway; leaving, the panel fades out before the fold starts, so its ✕ is never drawn half cut
    AnimatedVisibility(
        visible = download != null,
        enter = if (still) EnterTransition.None else unfoldEnter(),
        exit = if (still) ExitTransition.None else foldExit(),
    ) {
        Column {
            Spacer(Modifier.height(DemoSpacing.block))
            (download ?: last.value)?.invoke()
        }
    }
}

/** The fit symbol's box, its size held while the fit is unknown so the line doesn't change when it lands. */
@Composable
private fun FitBox(fit: FitLevel?, description: String, modifier: Modifier = Modifier) {
    Box(
        modifier.size(scaledWithText(DemoSize.fitSymbol, DemoSize.fitSymbolMax)),
        contentAlignment = Alignment.Center,
    ) {
        if (fit != null) FitSymbol(fit, description)
    }
}
