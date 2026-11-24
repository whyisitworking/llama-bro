package com.suhel.llamabro.demo.designsystem.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * demo design §8.6's table, with the Models page's too-big ink no dimmer than [muted] (D117: it is a fit, not a
 * disabled mark), in a softer neutral palette: a cool grey light theme and a dimmed dark one, both with a lowered white
 * point (D148): no near-white ground in light, no near-white text in dark. Cards and fields are
 * their fill alone, with no edge (D122). "Runs well" has no tint of its own: it is the plain surface.
 */
@Immutable
@Suppress("LongParameterList") // the palette: one parameter per colour role
class DemoColors(
    val canvas: Color,
    val surface: Color,
    /**
     * The Sidebar drawer's ground (D148): a step lighter than [canvas] in both themes, so the panel the page slides
     * off reads as its own layer and not as more of the page; [surface] fills on it still stand out.
     */
    val panel: Color,
    val ink: Color,
    /**
     * The assistant's reply body in the chat: a step softer than [ink], so a long reply reads calmer; its headings and
     * bold stay the ink.
     */
    val reply: Color,
    val muted: Color,
    /**
     * The least important text and the capability icons (D123): a step fainter than [muted], still 4.5:1 on the canvas
     * and the surface in both themes.
     */
    val soft: Color,
    val hairline: Color,
    /** The user's message bubble (D49): a soft neutral, lifted from the canvas in both themes; text on it is [ink]. */
    val bubble: Color,
    /**
     * The one primary tone (D125): a quiet grey a step off the ink, filling every strong action (Get, Start a chat,
     * Send, the mic and voice buttons, the download ring) and drawing every selection outline. The app is monochrome;
     * the fit symbols' green and amber are its only colour, because they carry meaning.
     */
    val accent: Color,
    /** Content on an [accent] fill (a button's label, the send glyph): near-white in light, near-black in dark. */
    val onAccent: Color,
    val accentSoft: Color,
    val greatInk: Color,
    val slowInk: Color,
    val tooBigInk: Color,
    /** A destructive action ("Remove"): not the sand of "slow here", which is a fit and not a warning. */
    val danger: Color,
    /** The theme-dependent choices that are not table colours (shadow, scrim, switch track, subtle fill). */
    val isDark: Boolean,
)

val LightDemoColors =
    DemoColors(
        canvas = Color(0xFFEBEBEE),
        surface = Color(0xFFF4F4F6),
        panel = Color(0xFFF0F0F3),
        ink = Color(0xFF1D1D1F),
        reply = Color(0xFF2C2C2E),
        muted = Color(0xFF606065),
        soft = Color(0xFF6A6A6F),
        hairline = Color(0xFFDCDCE1),
        bubble = Color(0xFFE0E0E5),
        accent = Color(0xFF3A3A3D),
        onAccent = Color(0xFFF4F4F6),
        accentSoft = Color(0xFFE1E1E6),
        greatInk = Color(0xFF3E5641),
        slowInk = Color(0xFF73572F),
        tooBigInk = Color(0xFF5A5A5F),
        danger = Color(0xFFA23B2F),
        isDark = false,
    )

val DarkDemoColors =
    DemoColors(
        canvas = Color(0xFF0B0B0C),
        surface = Color(0xFF161618),
        panel = Color(0xFF131315),
        ink = Color(0xFFD5D5D9),
        reply = Color(0xFFC2C2C7),
        muted = Color(0xFF98989D),
        soft = Color(0xFF808085),
        hairline = Color(0xFF242426),
        bubble = Color(0xFF222224),
        accent = Color(0xFFBBBBC0),
        onAccent = Color(0xFF161618),
        accentSoft = Color(0xFF28282B),
        greatInk = Color(0xFFA9C3AA),
        slowInk = Color(0xFFD3B588),
        tooBigInk = Color(0xFFAAAAB0),
        danger = Color(0xFFE59A90),
        isDark = true,
    )

val LocalDemoColors = staticCompositionLocalOf { LightDemoColors }
