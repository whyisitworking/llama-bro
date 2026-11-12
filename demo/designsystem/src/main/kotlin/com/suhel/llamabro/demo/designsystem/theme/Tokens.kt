package com.suhel.llamabro.demo.designsystem.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** pd-design 5.2's table, verbatim. "Runs well" has no tint of its own: it is the plain surface. */
@Immutable
class DemoColors(
    val canvas: Color,
    val surface: Color,
    val ink: Color,
    val muted: Color,
    val hairline: Color,
    /** The user's message bubble (D49): a soft neutral, lifted from the canvas in both themes; text on it is [ink]. */
    val bubble: Color,
    val accent: Color,
    val accentSoft: Color,
    val greatTint: Color,
    val greatInk: Color,
    val slowTint: Color,
    val slowInk: Color,
    val tooBigTint: Color,
    val tooBigInk: Color,
    /** A destructive action ("Remove"): not the sand of "slow here", which is a fit and not a warning. */
    val danger: Color,
    /** Picks the orb's gradient and the shadow strength; not part of the table. */
    val isDark: Boolean,
)

val Light =
    DemoColors(
        canvas = Color(0xFFF7F6F3),
        surface = Color(0xFFFFFFFF),
        ink = Color(0xFF17181C),
        muted = Color(0xFF55565C),
        hairline = Color(0xFFE6E4DF),
        bubble = Color(0xFFE9E7E1),
        accent = Color(0xFF25304D),
        accentSoft = Color(0xFFE4E7EF),
        greatTint = Color(0xFFEDF0EC),
        greatInk = Color(0xFF3E5641),
        slowTint = Color(0xFFF3EEE5),
        slowInk = Color(0xFF73572F),
        tooBigTint = Color(0xFFEFEFEF),
        tooBigInk = Color(0xFF6E6E73),
        danger = Color(0xFFA23B2F),
        isDark = false,
    )

val Dark =
    DemoColors(
        canvas = Color(0xFF121316),
        surface = Color(0xFF1B1C20),
        ink = Color(0xFFECEBE8),
        muted = Color(0xFFA5A6AC),
        hairline = Color(0xFF26272C),
        bubble = Color(0xFF2A2C31),
        accent = Color(0xFFB9C4DE),
        accentSoft = Color(0xFF252A38),
        greatTint = Color(0xFF1A1F1B),
        greatInk = Color(0xFFA9C3AA),
        slowTint = Color(0xFF221E18),
        slowInk = Color(0xFFD3B588),
        tooBigTint = Color(0xFF1E1E21),
        tooBigInk = Color(0xFF8C8C92),
        danger = Color(0xFFE59A90),
        isDark = true,
    )

val LocalDemoColors = staticCompositionLocalOf { Light }
