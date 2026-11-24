package com.suhel.llamabro.demo.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import com.suhel.llamabro.demo.designsystem.theme.DemoColors
import com.suhel.llamabro.demo.designsystem.theme.DemoShapes
import com.suhel.llamabro.demo.designsystem.theme.DemoSize
import com.suhel.llamabro.demo.designsystem.theme.DemoSpacing
import com.suhel.llamabro.demo.designsystem.theme.DemoType
import com.suhel.llamabro.demo.designsystem.theme.LocalDemoColors
import com.suhel.llamabro.demo.designsystem.theme.LocalGlass

/**
 * The one strong button of a screen or sheet ("Get Qwen3.5 0.8B", "Save", "New chat"): the ink primary, [onAccent]
 * text, a [DemoSize.button] pill, the height of every button. Disabled, it is a flat hairline pill
 * with muted text, which still reads as a label (a faded ink pill would put its label on grey). [maxLines] 1 keeps a
 * long label ("Get UD-Q4_K_XL · 3.4 GB · about 8 min") to the button's height, ellipsised.
 */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    maxLines: Int = Int.MAX_VALUE,
    /** A label in Regular rather than Medium: the model card's quiet foot (D123). */
    subtle: Boolean = false,
) {
    val colors = LocalDemoColors.current
    Box(
        modifier
            .heightIn(min = DemoSize.button)
            .clip(CircleShape)
            .background(if (enabled) colors.accent else colors.hairline)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = DemoSpacing.section, vertical = DemoSpacing.block),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            color = if (enabled) colors.onAccent else colors.muted,
            style = if (subtle) DemoType.titleRegular else DemoType.title,
            textAlign = TextAlign.Center,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * A quiet text action in the ink ("Unload", "Reset to the model's defaults"). With no hue to tell it from body text
 * (D69) it is underlined, unless it is [bordered], which gives it the small hairline outline of the download controls,
 * or destructive, which has its own colour. Bordered or not it is [DemoSize.button] tall, the primary's height, and a
 * label too long for its width ellipsises rather than clipping, unless it may wrap ([maxLines]: a
 * dialog's buttons at large font, whose labels are never cut). [subtle] is the model card's quiet secondary (D123): its
 * label Regular at the primary's size, bordered by the faintest line, the hairline (the label says it acts).
 */
@Composable
fun QuietButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    bordered: Boolean = false,
    destructive: Boolean = false,
    maxLines: Int = 1,
    subtle: Boolean = false,
) {
    val colors = LocalDemoColors.current
    // a bordered button is Material's outlined button, a full pill; a quiet one is text on a small corner
    val shape = if (bordered) CircleShape else DemoShapes.chip
    val ink = if (destructive) colors.danger else colors.accent
    Box(
        modifier
            .minimumInteractiveComponentSize()
            .alpha(if (enabled) 1f else 0.38f)
            .clip(shape)
            .then(
                if (bordered) {
                    Modifier.border(
                        DemoSize.hairline,
                        when {
                            destructive -> colors.danger
                            subtle -> colors.hairline
                            else -> colors.muted
                        },
                        shape,
                    )
                } else {
                    Modifier
                },
            ).clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .heightIn(min = DemoSize.button)
            .padding(horizontal = if (bordered) DemoSpacing.item else DemoSpacing.block, vertical = DemoSpacing.block),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            color = ink,
            // unbordered, the ink-coloured label has no hue to say it acts, so it is underlined, like a link
            style =
                when {
                    subtle -> DemoType.titleRegular
                    bordered || destructive -> DemoType.label
                    else -> DemoType.label.underlined()
                },
            textAlign = TextAlign.Center,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * An icon with a 48 dp touch target; [label] is what a screen reader says. [small] draws the glyph at
 * [DemoSize.iconSmall], for the quiet actions under a reply; the target stays 48 dp. A [mark] puts a small dot in the
 * accent at the glyph's top end (a search's filters changed from their defaults) and is what a screen reader hears as
 * the action's state; the dot is the only sign, so it needs no word beside the icon.
 */
@Composable
fun IconAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    muted: Boolean = false,
    small: Boolean = false,
    mark: String? = null,
) {
    val colors = LocalDemoColors.current
    val glyph = if (small) DemoSize.iconSmall else DemoSize.icon
    Box(
        modifier
            .size(DemoSize.target)
            .chipGlass(LocalBackdrop.current.takeIf { LocalGlassChips.current }, LocalGlass.current.chip)
            .clip(CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .then(if (mark != null) Modifier.semantics { stateDescription = mark } else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = label,
            tint = if (muted) colors.muted else colors.ink,
            modifier = Modifier.size(glyph),
        )
        if (mark != null) {
            // on the glyph's top-end corner, inside the target, so the target's size and the row around it hold still
            Box(Modifier.size(glyph), contentAlignment = Alignment.TopEnd) {
                Box(Modifier.size(DemoSize.dot).background(colors.accent, CircleShape))
            }
        }
    }
}

/**
 * One choice among a few short options ("Most downloaded", "Newest"): chips that wrap onto as many lines as they need,
 * the chosen one in the one selection outline (D125), the accent at [DemoSize.selectedStroke], its label in the ink;
 * the others on a hairline with a muted label. Each is a radio button to a screen reader, and a whole touch target.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChoiceChips(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val colors = LocalDemoColors.current
    FlowRow(
        modifier.selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(DemoSpacing.block),
    ) {
        options.forEachIndexed { index, option ->
            val on = index == selected
            Box(
                Modifier
                    .minimumInteractiveComponentSize()
                    .clip(CircleShape)
                    .border(
                        if (on) DemoSize.selectedStroke else DemoSize.hairline,
                        if (on) colors.accent else colors.hairline,
                        CircleShape,
                    ).selectable(selected = on, role = Role.RadioButton, onClick = { onSelect(index) })
                    .heightIn(min = DemoSize.chip)
                    .padding(horizontal = DemoSpacing.compact),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    option,
                    color = if (on) colors.ink else colors.muted,
                    style = DemoType.label,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** The floating "New chat" button of the chat list layout: accent fill, onAccent text and icon. */
@Composable
fun DemoFab(text: String, icon: ImageVector, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = LocalDemoColors.current
    val shape = DemoShapes.fab
    // floating over a page ([FloatingFrame]'s overlay), a pill of glass in the ink (D148); elsewhere the accent's fill
    val backdrop = LocalBackdrop.current
    val ink = if (backdrop != null) colors.ink else colors.onAccent
    Row(
        modifier
            .then(
                if (backdrop != null) {
                    Modifier.glass(backdrop, LocalGlass.current.panel, DemoShapes.fabRadius).clip(shape)
                } else {
                    Modifier
                        .softShadow(shape, colors, elevation = DemoSize.elevationRaised, lightAlpha = 0.18f)
                        .clip(shape)
                        .background(colors.accent)
                },
            ).clickable(role = Role.Button, onClick = onClick)
            .heightIn(min = DemoSize.field)
            .padding(horizontal = DemoSpacing.item, vertical = DemoSpacing.block),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DemoSpacing.block),
    ) {
        Icon(icon, contentDescription = null, tint = ink, modifier = Modifier.size(DemoSize.icon))
        Text(text, color = ink, style = DemoType.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** The fills of a [SegmentedSwitch]: its [track], the chosen segment's [raised] fill and that segment's [edge]. */
@Immutable
internal class SegmentColors(val track: Color, val raised: Color, val edge: Color)

/**
 * The chosen segment is raised, lighter than the track in either theme (white on the hairline in light; the hairline on
 * the surface in dark). In dark the track is the surface, like the cards around it: the canvas would vanish into the
 * page. On a sheet, whose background is the surface, the track is the canvas. A raised fill alone is near-white on
 * near-white, so the chosen segment also has an edge in the accent, which holds 3:1 on the track
 * and the fill in both themes, a control's boundary (WCAG 1.4.11). The edge is a selected card's outline, the same
 * width and tone on the segment's own shape (D125), so "Basic" chosen and "Recommended" chosen read as one signal.
 */
internal fun segmentColors(colors: DemoColors, onSheet: Boolean): SegmentColors =
    SegmentColors(
        track =
            when {
                onSheet -> colors.canvas
                colors.isDark -> colors.surface
                else -> colors.hairline
            },
        raised = if (colors.isDark) colors.hairline else colors.surface,
        edge = colors.accent,
    )

/** Two or three exclusive options side by side ("Basic" / "Advanced"): the chosen one raised and edged on the track. */
@Composable
fun SegmentedSwitch(
    options: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    quiet: Boolean = false,
) {
    val colors = LocalDemoColors.current
    val segment = segmentColors(colors, LocalOnSheet.current)
    val large = isLargeText()
    Row(
        modifier
            // every segment the height of the tallest, so a wrapped label leaves the raised one whole
            .height(IntrinsicSize.Min)
            // segments are 40 dp in a 4 dp inset: a 48 dp control; the track's corner is the segment's plus the inset
            .clip(DemoShapes.card)
            .background(segment.track)
            .padding(DemoSpacing.inline)
            .selectableGroup(),
    ) {
        options.forEachIndexed { index, option ->
            val on = index == selected
            val shape = DemoShapes.chip
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .heightIn(min = DemoSize.control)
                    .then(
                        if (on) {
                            Modifier
                                .softShadow(shape, colors)
                                .background(segment.raised, shape)
                                .border(DemoSize.selectedStroke, segment.edge, shape)
                        } else {
                            Modifier.background(Color.Transparent)
                        },
                    ).clip(shape)
                    .selectable(selected = on, role = Role.Tab, onClick = { onSelect(index) })
                    .padding(horizontal = DemoSpacing.block),
                contentAlignment = Alignment.Center,
            ) {
                // at large font a label wraps ("Not / thinking") rather than lose its end
                Text(
                    option,
                    color = if (on) colors.ink else colors.muted,
                    // quiet (D123): only the chosen segment keeps the label's weight, the one emphasis on the card
                    style = if (quiet && !on) DemoType.bodyMedium else DemoType.label,
                    textAlign = TextAlign.Center,
                    maxLines = if (large) 2 else 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private fun TextStyle.underlined(): TextStyle = copy(textDecoration = TextDecoration.Underline)
