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
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.suhel.llamabro.demo.designsystem.theme.DemoShapes
import com.suhel.llamabro.demo.designsystem.theme.DemoSpacing
import com.suhel.llamabro.demo.designsystem.theme.DemoType
import com.suhel.llamabro.demo.designsystem.theme.LocalDemoColors

/**
 * The one strong button of a screen or sheet ("Get Swift", "Save", "New chat"): the navy primary, canvas text, a 56 dp
 * pill (Material's medium button). Disabled, it is a flat hairline pill with muted text, which still reads as a label
 * (a faded navy pill would put white text on grey).
 */
@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val colors = LocalDemoColors.current
    Box(
        modifier
            .heightIn(min = 56.dp)
            .clip(CircleShape)
            .background(if (enabled) colors.accent else colors.hairline)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = DemoSpacing.section, vertical = DemoSpacing.block),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            color = if (enabled) colors.canvas else colors.muted,
            style = DemoType.title,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * A quiet text action in the accent ("Get more models", "What's a model?", "Pause"); [bordered] gives it the small
 * hairline outline of the download controls.
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
                        1.dp,
                        if (destructive) colors.danger else colors.muted,
                        shape,
                    )
                } else {
                    Modifier
                },
            ).clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .then(if (bordered) Modifier.heightIn(min = 40.dp) else Modifier)
            .padding(horizontal = if (bordered) DemoSpacing.item else DemoSpacing.block, vertical = DemoSpacing.block),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = ink, style = DemoType.label, maxLines = 1)
    }
}

/** An icon with a 48 dp touch target; [label] is what a screen reader says. */
@Composable
fun IconAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    muted: Boolean = false,
) {
    val colors = LocalDemoColors.current
    Box(
        modifier
            .size(48.dp)
            .clip(CircleShape)
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = label,
            tint = if (muted) colors.muted else colors.ink,
            modifier = Modifier.size(24.dp),
        )
    }
}

/** The floating "New chat" button of the chat list layout: accent fill, canvas text and icon. */
@Composable
fun DemoFab(text: String, icon: ImageVector, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = LocalDemoColors.current
    val shape = DemoShapes.fab
    Row(
        modifier
            .softShadow(shape, colors, elevation = 6.dp, lightAlpha = 0.18f)
            .clip(shape)
            .background(colors.accent)
            .clickable(role = Role.Button, onClick = onClick)
            .heightIn(min = 56.dp)
            .padding(horizontal = DemoSpacing.item, vertical = DemoSpacing.block),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DemoSpacing.block),
    ) {
        Icon(icon, contentDescription = null, tint = colors.canvas, modifier = Modifier.size(24.dp))
        Text(text, color = colors.canvas, style = DemoType.title)
    }
}

/** Two or three exclusive options side by side ("Basic" / "Advanced"): the chosen one raised on the surface. */
@Composable
fun SegmentedSwitch(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val colors = LocalDemoColors.current
    val onSheet = LocalOnSheet.current
    // the chosen segment is raised: lighter than the track in either theme (white on the hairline in light; the
    // hairline on the canvas in dark, where the surface is darker and the chosen one would look sunken)
    val track = if (colors.isDark || onSheet) colors.canvas else colors.hairline
    val raised = if (colors.isDark) colors.hairline else colors.surface
    Row(
        modifier
            .clip(DemoShapes.chip)
            .background(track)
            .padding(3.dp)
            .selectableGroup(),
    ) {
        options.forEachIndexed { index, option ->
            val on = index == selected
            val shape = DemoShapes.chip
            Box(
                Modifier
                    .weight(1f)
                    .heightIn(min = 42.dp)
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
