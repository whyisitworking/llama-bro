package com.suhel.llamabro.demo.designsystem.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * How a piece of glass looks (D148), after Apple's Liquid Glass: a lens over the page from Android 13, a translucent
 * fill before. Every value is a knob: [GlassTheme.Default] holds the app's, and a subtree can be given others through
 * [LocalGlass].
 */
@Immutable
data class GlassStyle(
    /** How far in from the rim the page is bent: the width of the glass's curved edge. */
    val bezel: Dp,
    /** How far the page is read from, further in, at the very rim: how strongly the edge bends (magnifies) it. */
    val refraction: Dp,
    /**
     * The chromatic aberration: how much more red bends than green at the rim, and blue as much less, as a share of
     * [refraction]. 0 is none; 1 splits red and blue a whole bend apart.
     */
    val dispersion: Float,
    /** The soft blur through the glass, before it bends. */
    val blur: Dp,
    /**
     * How strongly the surface is blended into the lens (an overlay blend in its shader, not a layer over it): low for
     * a chip, higher for a panel with text on it.
     */
    val veil: Float,
    /** How bright the light along the glass's upper rim is, 0 to 1. */
    val rimLight: Float,
    /** How thick that light is. */
    val rim: Dp,
    /** How opaque the glass is where it can't be a lens (before Android 13, or with no page under it). */
    val bareVeil: Float,
)

/**
 * How the page looks under a floating bar (D148): blurred progressively from the screen's edge, its colours split,
 * veiled with the surface, all easing to none past the bar.
 */
@Immutable
data class BarGlass(
    /** The blur at the screen's edge. */
    val blur: Dp,
    /** The chromatic aberration in that blur: how far red and blue split, at its strongest. 0 for none. */
    val dispersion: Dp,
    /** How strongly the surface is blended into the blur (an overlay blend in its shader, along with its strength). */
    val veil: Float,
    /** The veil with no blur under it (before Android 12, or over no page): denser, so text under it stays quiet. */
    val bareVeil: Float,
    /** How far past the bar the blur and the veil reach as they ease out. */
    val fade: Dp,
    /** How far from the screen's edge, as a share of the bar's layer, the blur and the veil stay full. */
    val hold: Float,
) {
    /** Whether [blur] is strong enough to run on a half-resolution copy of the page, its softness hidden (F185). */
    val heavy: Boolean get() = blur >= HeavyBlur

    private companion object {
        val HeavyBlur = 12.dp
    }
}

/** The glass of every kind the app draws: [chip] (icon buttons, the title), [panel] (fields, tabs, a FAB), [bar]. */
@Immutable
data class GlassTheme(val chip: GlassStyle, val panel: GlassStyle, val bar: BarGlass) {
    companion object {
        /** The app's glass: dramatic refraction and colour on a thin edge. */
        val Default =
            GlassStyle(
                bezel = 9.dp,
                refraction = 14.dp,
                dispersion = 0.9f,
                blur = 6.dp,
                veil = 0.35f,
                rimLight = 0.22f,
                rim = 1.dp,
                bareVeil = 0.7f,
            ).let { chip ->
                GlassTheme(
                    chip = chip,
                    // under text: a denser veil and a stronger blur, so what is typed and read on it stays calm
                    // over what passes under (the veil is blended into the glass, keeping its lights and darks)
                    panel = chip.copy(veil = 0.45f, blur = 10.dp),
                    bar =
                        BarGlass(
                            blur = 24.dp,
                            dispersion = 8.dp,
                            // blended in by the shader: lighter than a flat veil, or the dark theme crushes the blur's
                            // colour to near-black
                            veil = 0.35f,
                            bareVeil = 0.8f,
                            // the blur recedes into the bar but is full under its parts: content is sharp until it
                            // nears them, fully frosted beneath them
                            fade = 16.dp,
                            hold = 0.55f,
                        ),
                )
            }
    }
}

/**
 * How much glass a phone can afford to draw (D153), measured on a flagship only (F183, F185, F186). [Full]: every
 * pass. [Light], for a weaker phone: a chip's or a panel's lens bends the page without its own blur pass, and a heavy
 * bar blur runs at a quarter of the resolution instead of half; the look is the same glass, a little plainer.
 */
enum class GlassTier { Full, Light }

/** The phone's [GlassTier]; the app provides it at its root. */
val LocalGlassTier = staticCompositionLocalOf { GlassTier.Full }

/** The glass for the subtree: [GlassTheme.Default] unless a screen provides another. */
val LocalGlass = staticCompositionLocalOf { GlassTheme.Default }
