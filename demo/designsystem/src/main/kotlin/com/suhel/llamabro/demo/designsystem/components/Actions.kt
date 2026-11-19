package com.suhel.llamabro.demo.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import com.suhel.llamabro.demo.designsystem.theme.DemoShapes
import com.suhel.llamabro.demo.designsystem.theme.DemoSize
import com.suhel.llamabro.demo.designsystem.theme.DemoSpacing
import com.suhel.llamabro.demo.designsystem.theme.DemoType
import com.suhel.llamabro.demo.designsystem.theme.LocalDemoColors

/**
 * The one strong button of a screen or sheet ("Get Qwen3.5 0.8B", "Save", "New chat"): the ink primary, [onAccent]
 * text, a 56 dp pill (Material's medium button). Disabled, it is a flat hairline pill with muted text, which still
 * reads as a label (a faded ink pill would put its label on grey).
 */
@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val colors = LocalDemoColors.current
    Box(
        modifier
            .heightIn(min = DemoSize.field)
            .clip(CircleShape)
            .background(if (enabled) colors.accent else colors.hairline)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = DemoSpacing.section, vertical = DemoSpacing.block),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            color = if (enabled) colors.onAccent else colors.muted,
            style = DemoType.title,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * A quiet text action in the ink ("Unload", "Reset to the model's defaults"). With no hue to tell it from body text
 * (D69) it is underlined, unless it is [bordered], which gives it the small hairline outline of the download controls,
 * or destructive, which has its own colour.
 */
@Composable
fun QuietButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    bordered: Boolean = false,
    destructive: Boolean = false,
) {
    val colors = LocalDemoColors.current
    // a bordered button is Material's outlined button: a full pill, 40 dp tall; a quiet one is text on a small corner
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
                        if (destructive) colors.danger else colors.muted,
                        shape,
                    )
                } else {
                    Modifier
                },
            ).clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .then(if (bordered) Modifier.heightIn(min = DemoSize.control) else Modifier)
            .padding(horizontal = if (bordered) DemoSpacing.item else DemoSpacing.block, vertical = DemoSpacing.block),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            color = ink,
            // unbordered, the ink-coloured label has no hue to say it acts, so it is underlined, like a link
            style = if (bordered || destructive) DemoType.label else DemoType.label.underlined(),
            maxLines = 1,
        )
    }
}

/**
 * An icon with a 48 dp touch target; [label] is what a screen reader says. [small] draws the glyph at
 * [DemoSize.iconSmall], for the quiet actions under a reply; the target stays 48 dp.
 */
@Composable
fun IconAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    muted: Boolean = false,
    small: Boolean = false,
) {
    val colors = LocalDemoColors.current
    Box(
        modifier
            .size(DemoSize.target)
            .clip(CircleShape)
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = label,
            tint = if (muted) colors.muted else colors.ink,
            modifier = Modifier.size(if (small) DemoSize.iconSmall else DemoSize.icon),
        )
    }
}

/** The floating "New chat" button of the chat list layout: accent fill, onAccent text and icon. */
@Composable
fun DemoFab(text: String, icon: ImageVector, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = LocalDemoColors.current
    val shape = DemoShapes.fab
    Row(
        modifier
            .softShadow(shape, colors, elevation = DemoSize.elevationRaised, lightAlpha = 0.18f)
            .clip(shape)
            .background(colors.accent)
            .clickable(role = Role.Button, onClick = onClick)
            .heightIn(min = DemoSize.field)
            .padding(horizontal = DemoSpacing.item, vertical = DemoSpacing.block),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DemoSpacing.block),
    ) {
        Icon(icon, contentDescription = null, tint = colors.onAccent, modifier = Modifier.size(DemoSize.icon))
        Text(text, color = colors.onAccent, style = DemoType.title)
    }
}

/** Two or three exclusive options side by side ("Basic" / "Advanced"): the chosen one raised on the surface. */
@Composable
fun SegmentedSwitch(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val colors = LocalDemoColors.current
    val onSheet = LocalOnSheet.current
    // the chosen segment is raised: lighter than the track in either theme (white on the hairline in light; the
    // hairline on the surface in dark). In dark the track is the surface, like the cards around it: the canvas would
    // vanish into the page. On a sheet, whose background is the surface, the track is the canvas.
    val track =
        when {
            onSheet -> colors.canvas
            colors.isDark -> colors.surface
            else -> colors.hairline
        }
    val raised = if (colors.isDark) colors.hairline else colors.surface
    Row(
        modifier
            // segments are 40 dp in a 4 dp inset: a 48 dp control; the track's corner is the segment's plus the inset
            .clip(DemoShapes.card)
            .background(track)
            .padding(DemoSpacing.inline)
            .selectableGroup(),
    ) {
        options.forEachIndexed { index, option ->
            val on = index == selected
            val shape = DemoShapes.chip
            Box(
                Modifier
                    .weight(1f)
                    .heightIn(min = DemoSize.control)
                    .then(
                        if (on) {
                            Modifier
                                .softShadow(
                                    shape,
                                    colors,
                                ).background(raised, shape)
                        } else {
                            Modifier.background(Color.Transparent)
                        },
                    ).clip(shape)
                    .selectable(selected = on, role = Role.Tab, onClick = { onSelect(index) }),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    option,
                    color = if (on) colors.ink else colors.muted,
                    style = DemoType.label,
                )
            }
        }
    }
}

private fun TextStyle.underlined(): TextStyle = copy(textDecoration = TextDecoration.Underline)
