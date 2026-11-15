package com.suhel.llamabro.demo.designsystem.components

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.suhel.llamabro.demo.designsystem.theme.DemoColors
import com.suhel.llamabro.demo.designsystem.theme.DemoSize

/**
 * Marks the area a layout primitive keeps inside the gutter. A test walks the screens and sheets and asserts that every
 * marked area starts 16 dp from the left edge and ends 16 dp from the right, so a screen that strayed from the
 * gutter fails there and not in a reviewer's eye.
 */
val DsGutterKey = SemanticsPropertyKey<Boolean>("DsGutter")

/**
 * Marks the box the gutter is measured from: the screen's or the sheet's own edge, which on a tablet is not the
 * window's.
 */
val DsGutterContainerKey = SemanticsPropertyKey<Boolean>("DsGutterContainer")

/** Puts the [DsGutterKey] mark on this area; an inner node of the primitive, inside its padding. */
internal fun Modifier.gutterEdge(): Modifier = semantics { this[DsGutterKey] = true }

/** Puts the [DsGutterContainerKey] mark on this box; the outer node of the primitive, whose padding is the gutter. */
internal fun Modifier.gutterContainer(): Modifier = semantics { this[DsGutterContainerKey] = true }

/** Test tags the module's own tests read. */
internal object DsTags {
    const val CARD_SURFACE = "ds_card_surface"
    const val WAKE_HAIRLINE = "ds_wake_hairline"
}

/** True inside a [DemoSheet]: a plain card there sits on the canvas colour, as rows on a sheet do in the design. */
internal val LocalOnSheet = staticCompositionLocalOf { false }

/**
 * The soft shadow: 2 dp, black at 5% in light and 35% in dark, never a tonal tint. [elevation] and
 * [lightAlpha] raise it for the floating pieces (composer, FAB), as the design does.
 */
internal fun Modifier.softShadow(
    shape: Shape,
    colors: DemoColors,
    elevation: Dp = DemoSize.elevationSoft,
    lightAlpha: Float = 0.05f,
): Modifier {
    val tint = Color.Black.copy(alpha = if (colors.isDark) 0.35f else lightAlpha)
    return shadow(elevation = elevation, shape = shape, clip = false, ambientColor = tint, spotColor = tint)
}

/** Lays a bubble out at most [fraction] of the row wide, against the end ([end]) or the start of the row. */
internal fun Modifier.bubbleIn(end: Boolean, fraction: Float = 0.8f): Modifier =
    layout { measurable, constraints ->
        val placeable =
            measurable.measure(
                constraints.copy(minWidth = 0, maxWidth = (constraints.maxWidth * fraction).toInt()),
            )
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else placeable.width
        layout(width, placeable.height) { placeable.placeRelative(if (end) width - placeable.width else 0, 0) }
    }
