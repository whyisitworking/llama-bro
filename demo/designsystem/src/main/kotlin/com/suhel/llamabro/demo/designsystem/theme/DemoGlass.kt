package com.suhel.llamabro.demo.designsystem.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.pow

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
    /**
     * How gently the blur gives way to the sharp page, 0 to 1 (D163): at 0 its radius follows the fade's strength;
     * higher, it lingers longer as a light blur before the page turns sharp, as the eye sees a small blur far more
     * than the difference between two large ones.
     */
    val softness: Float,
) {
    /**
     * The share of [blur] at [strength] of the fade (1 full, 0 none): the strength raised to a power, 1 at no
     * [softness] and 3 at the most, so the radius shrinks fast while it is large and slowly once it is small.
     */
    fun blurAt(strength: Float): Float = strength.coerceIn(0f, 1f).pow(easing)

    /** The power the fade's strength is raised to for the blur's radius: see [blurAt]. */
    val easing: Float get() = 1f + softness.coerceIn(0f, 1f) * SOFTEST

    /**
     * How many times smaller than the screen the page is copied for this blur (F185, D162): [blur] over [SharpCopy],
     * so a copied pixel stays as small a share of the blur at every setting and the copy's softness never shows. The
     * app's 24 dp reads a copy at half size; 12 dp and less, the page itself; between, sizes between. A [GlassTier.Light]
     * phone copies twice as small, to a quarter at most.
     */
    fun reduction(tier: GlassTier): Float {
        val share = blur / SharpCopy
        return if (tier == GlassTier.Light) (share * 2f).coerceIn(1f, LIGHT_MOST) else share.coerceIn(1f, FULL_MOST)
    }

    private companion object {
        /** The strongest blur read from the page itself, at full resolution. */
        val SharpCopy = 12.dp
        const val FULL_MOST = 2f
        const val LIGHT_MOST = 4f

        /** How much [softness] at its most adds to the power: past a cube, the blur all but ends where it starts. */
        const val SOFTEST = 2f
    }
}

/**
 * How a piece of glass answers a finger (D158): pressed, it swells, a light gathers on its rim around the finger, out
 * to [reach] at [light]'s strength, and its edge bends the page [bend] more (a share of its refraction). Dragged, it
 * stretches along the drag and narrows across it, and moves after the finger only a little, on an elastic limited to
 * [pull]; both give slowly at first and more as the drag goes on, half of each at a drag of [resistance], as a heavy
 * pane would, not a jelly. Let go, swell, stretch and movement all ease back together. The swell and the stretch
 * are lengths measured on the glass's shorter side, so a wide panel grows by as much as a chip, not by a share of its
 * width. With animations off it neither swells nor moves; the light still comes and goes, and follows the finger.
 */
@Immutable
data class GlassPress(
    /** How much bigger pressed glass is drawn, as its shorter side sees it: 1 for not at all. */
    val swell: Float,
    /** How bright the light around the finger is, 0 to 1. */
    val light: Float,
    /** How far from the finger that light reaches. */
    val reach: Dp,
    /** How much more the pressed glass's edge bends the page, as a share of its refraction. */
    val bend: Float,
    /** How far dragged glass can be pulled from its place, however far the finger goes: the elastic's limit. */
    val pull: Dp = Default.pull,
    /** How much longer dragged glass grows along the drag, at most, as a share of its shorter side; narrower across. */
    val stretch: Float = Default.stretch,
    /** How far a finger drags glass for half its stretch and half its pull: longer is stiffer. */
    val resistance: Dp = Default.resistance,
) {
    /** How strongly the edge bends at [press], as a factor of the glass's refraction. */
    fun bendAt(press: Float): Float = 1f + bend * press.coerceAtLeast(0f)

    /** How bright the light around the finger is at [press]: never past [light]. */
    fun lightAt(press: Float): Float = (light * press).coerceIn(0f, light)

    /**
     * Where glass of [base] size is drawn, in its own bounds' pixels, [press] of the way in and dragged [drag] pixels
     * by the finger, its pull limit [pullPx] pixels: grown by the swell on every side alike, stretched along the drag
     * and moved that little toward it as the elastic gives, half at a drag of [resistancePx]. [still] (animations
     * off), it stays as it is.
     */
    @Suppress("LongParameterList") // the press, the drag and the lengths it is measured against
    fun pose(
        base: Size,
        press: Float,
        drag: Offset,
        still: Boolean,
        pullPx: Float,
        resistancePx: Float,
    ): GlassPose {
        val side = base.minDimension
        val grow = (swell - 1f) * side * press.coerceIn(0f, 1f)
        // at rest exactly so, not a negative zero's offset: the glass then draws its content as is
        if (still || (grow == 0f && drag == Offset.Zero)) return GlassPose(Offset.Zero, base, 1f, 1f)
        val swollen = Size(base.width + grow, base.height + grow)
        // the content grows as the glass's longer side does: a chip's with it, a wide panel's text barely
        val content = 1f + grow / base.maxDimension.coerceAtLeast(1f)
        val far = drag.getDistance()
        if (far == 0f) {
            return GlassPose(Offset(-grow / 2f, -grow / 2f), swollen, content, content)
        }
        // squared, the elastic resists the first of a drag and gives more as it goes on, never past its limits
        val give = if (resistancePx > 0f) far * far / (resistancePx * resistancePx + far * far) else 1f
        val toward = drag / far
        // along the drag longer, across it narrower: how much of the drag is across, less how much is down
        val lean = toward.x * toward.x - toward.y * toward.y
        val reach = stretch * side * give
        val size = Size(swollen.width + reach * lean, swollen.height - reach * lean)
        val moved = toward * (pullPx * give)
        val topLeft = Offset((base.width - size.width) / 2f + moved.x, (base.height - size.height) / 2f + moved.y)
        return GlassPose(topLeft, size, content * size.width / swollen.width, content * size.height / swollen.height)
    }

    companion object {
        val Default =
            GlassPress(
                swell = 1.1f,
                light = 0.6f,
                reach = 64.dp,
                bend = 0.35f,
                pull = 2.dp,
                stretch = 0.05f,
                resistance = 64.dp,
            )
    }
}

/**
 * Where pressed glass is drawn (D158), in its bounds' pixels: its swollen, moved and stretched rectangle ([topLeft],
 * [size]), and the scale its content is drawn at across ([scaleX]) and down ([scaleY]).
 */
@Immutable
data class GlassPose(val topLeft: Offset, val size: Size, val scaleX: Float, val scaleY: Float) {
    /** How far the glass's middle has moved from its bounds' middle. */
    fun moved(base: Size): Offset =
        Offset(topLeft.x + size.width / 2f - base.width / 2f, topLeft.y + size.height / 2f - base.height / 2f)
}

/**
 * The glass of every kind the app draws: [chip] (icon buttons, the title), [panel] (fields, tabs, a FAB), [bar],
 * and how a pressed one answers ([press]). With [mergedLens], a floating bar draws the chips and panels on it in its
 * own blur passes, instead of each drawing its lens in two passes of its own: a quarter less CPU, a touch softer (D154,
 * F187). Null leaves it to the phone's [GlassTier] ([merges]).
 */
@Immutable
data class GlassTheme(
    val chip: GlassStyle,
    val panel: GlassStyle,
    val bar: BarGlass,
    val mergedLens: Boolean? = null,
    val press: GlassPress = GlassPress.Default,
) {
    /**
     * Whether the bars draw the glass on them (D154): as [mergedLens] says, else on a [GlassTier.Light] phone only,
     * where the passes it saves matter most; a [GlassTier.Full] phone keeps the sharper glass of its own.
     */
    fun merges(tier: GlassTier): Boolean = mergedLens ?: (tier == GlassTier.Light)

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
                            // a light blur lingers before the page turns sharp: a linear radius snaps into focus
                            softness = 0.5f,
                        ),
                )
            }
    }
}

/**
 * How much glass a phone can afford to draw (D153), measured on a flagship only (F183, F185, F186). [Full]: every
 * pass. [Light], for a weaker phone: a chip's or a panel's lens bends the page without its own blur pass, and a bar's
 * blur reads a copy of the page twice as small ([BarGlass.reduction]); the look is the same glass, a little plainer.
 */
enum class GlassTier { Full, Light }

/** The phone's [GlassTier]; the app provides it at its root. */
val LocalGlassTier = staticCompositionLocalOf { GlassTier.Full }

/** The glass for the subtree: [GlassTheme.Default] unless a screen provides another. */
val LocalGlass = staticCompositionLocalOf { GlassTheme.Default }
