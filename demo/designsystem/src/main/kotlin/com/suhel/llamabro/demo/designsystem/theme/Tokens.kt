package com.suhel.llamabro.demo.designsystem.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** demo design §8.6's table, verbatim. "Runs well" has no tint of its own: it is the plain surface. */
@Immutable
class DemoColors(
    val canvas: Color,
    val surface: Color,
    val ink: Color,
    val muted: Color,
    val hairline: Color,
    /** The user's message bubble (D49): a soft neutral, lifted from the canvas in both themes; text on it is [ink]. */
    val bubble: Color,
    /** The primary: the ink itself, so the app has no hue of its own. */
    val accent: Color,
    /** Content on an [accent] fill (a primary button's label, the send glyph): white in light, near-black in dark. */
    val onAccent: Color,
    val accentSoft: Color,
    val greatTint: Color,
    val greatInk: Color,
    val slowTint: Color,
    val slowInk: Color,
    val tooBigTint: Color,
    val tooBigInk: Color,
    /** A destructive action ("Remove"): not the sand of "slow here", which is a fit and not a warning. */
    val danger: Color,
    /** The theme-dependent choices that are not table colours (shadow, scrim, switch track, subtle fill). */
    val isDark: Boolean,
)

val Light =
    DemoColors(
        canvas = Color(0xFFFAFAFA),
        surface = Color(0xFFFFFFFF),
        ink = Color(0xFF0D0D0D),
        muted = Color(0xFF5D5D5D),
        hairline = Color(0xFFE5E5E5),
        bubble = Color(0xFFEFEFEF),
        accent = Color(0xFF0D0D0D),
        onAccent = Color(0xFFFFFFFF),
        accentSoft = Color(0xFFEBEBEB),
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
        canvas = Color(0xFF0A0A0A),
        surface = Color(0xFF141414),
        ink = Color(0xFFECECEC),
        muted = Color(0xFFA8A8A8),
        hairline = Color(0xFF222222),
        bubble = Color(0xFF1E1E1E),
        accent = Color(0xFFECECEC),
        onAccent = Color(0xFF0A0A0A),
        accentSoft = Color(0xFF262626),
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
