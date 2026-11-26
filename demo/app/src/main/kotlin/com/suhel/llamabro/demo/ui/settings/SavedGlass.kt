package com.suhel.llamabro.demo.ui.settings

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.suhel.llamabro.demo.designsystem.theme.BarGlass
import com.suhel.llamabro.demo.designsystem.theme.GlassPress
import com.suhel.llamabro.demo.designsystem.theme.GlassStyle
import com.suhel.llamabro.demo.designsystem.theme.GlassTheme
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * The glass a person tuned in Settings › Appearance › Glass (D152), as it is saved: JSON, lengths in dp. A value the
 * saved text lacks (one added since it was saved) or text that can't be read is [GlassTheme.Default]'s.
 */
object SavedGlass {
    private val json = Json { ignoreUnknownKeys = true }

    fun encode(glass: GlassTheme): String =
        json.encode(
            Saved(
                Saved.Style.of(glass.chip),
                Saved.Style.of(glass.panel),
                Saved.Bar.of(glass.bar),
                glass.mergedLens,
                Saved.Press.of(glass.press),
                Saved.Style.of(glass.sheet),
            ),
        )

    fun decode(text: String?): GlassTheme {
        val default = GlassTheme.Default
        val saved = text?.let(::read) ?: return default
        return GlassTheme(
            chip = saved.chip?.over(default.chip) ?: default.chip,
            panel = saved.panel?.over(default.panel) ?: default.panel,
            bar = saved.bar?.over(default.bar) ?: default.bar,
            sheet = saved.sheet?.over(default.sheet) ?: default.sheet,
            mergedLens = saved.mergedLens,
            press = saved.press?.over(default.press) ?: default.press,
        )
    }

    /** [text] read, or null when it can't be: not JSON, or a value of the wrong kind. */
    private fun read(text: String): Saved? =
        try {
            json.decodeFromString(Saved.serializer(), text)
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }

    private fun Json.encode(saved: Saved): String = encodeToString(Saved.serializer(), saved)

    @Serializable
    private data class Saved(
        val chip: Style? = null,
        val panel: Style? = null,
        val bar: Bar? = null,
        val mergedLens: Boolean? = null,
        val press: Press? = null,
        val sheet: Style? = null,
    ) {
        @Serializable
        data class Style(
            val bezel: Float? = null,
            val refraction: Float? = null,
            val dispersion: Float? = null,
            val blur: Float? = null,
            val veil: Float? = null,
            val rimLight: Float? = null,
            val rim: Float? = null,
            val bareVeil: Float? = null,
        ) {
            fun over(base: GlassStyle) =
                GlassStyle(
                    bezel = bezel.dpOr(base.bezel),
                    refraction = refraction.dpOr(base.refraction),
                    dispersion = dispersion ?: base.dispersion,
                    blur = blur.dpOr(base.blur),
                    veil = veil ?: base.veil,
                    rimLight = rimLight ?: base.rimLight,
                    rim = rim.dpOr(base.rim),
                    bareVeil = bareVeil ?: base.bareVeil,
                )

            companion object {
                fun of(s: GlassStyle) =
                    Style(
                        s.bezel.value,
                        s.refraction.value,
                        s.dispersion,
                        s.blur.value,
                        s.veil,
                        s.rimLight,
                        s.rim.value,
                        s.bareVeil,
                    )
            }
        }

        @Serializable
        data class Bar(
            val blur: Float? = null,
            val dispersion: Float? = null,
            val veil: Float? = null,
            val bareVeil: Float? = null,
            val fade: Float? = null,
            val hold: Float? = null,
            val softness: Float? = null,
        ) {
            fun over(base: BarGlass) =
                BarGlass(
                    blur = blur.dpOr(base.blur),
                    dispersion = dispersion.dpOr(base.dispersion),
                    veil = veil ?: base.veil,
                    bareVeil = bareVeil ?: base.bareVeil,
                    fade = fade.dpOr(base.fade),
                    hold = hold ?: base.hold,
                    softness = softness ?: base.softness,
                )

            companion object {
                fun of(b: BarGlass) =
                    Bar(b.blur.value, b.dispersion.value, b.veil, b.bareVeil, b.fade.value, b.hold, b.softness)
            }
        }

        @Serializable
        data class Press(
            val swell: Float? = null,
            val light: Float? = null,
            val reach: Float? = null,
            val bend: Float? = null,
            val pull: Float? = null,
            val stretch: Float? = null,
            val resistance: Float? = null,
        ) {
            fun over(base: GlassPress) =
                GlassPress(
                    swell = swell ?: base.swell,
                    light = light ?: base.light,
                    reach = reach.dpOr(base.reach),
                    bend = bend ?: base.bend,
                    pull = pull.dpOr(base.pull),
                    stretch = stretch ?: base.stretch,
                    resistance = resistance.dpOr(base.resistance),
                )

            companion object {
                fun of(p: GlassPress) =
                    Press(p.swell, p.light, p.reach.value, p.bend, p.pull.value, p.stretch, p.resistance.value)
            }
        }
    }

    private fun Float?.dpOr(base: Dp): Dp = this?.dp ?: base
}
