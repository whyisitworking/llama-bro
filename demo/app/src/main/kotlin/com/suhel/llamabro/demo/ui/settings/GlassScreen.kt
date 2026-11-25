package com.suhel.llamabro.demo.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.suhel.llamabro.demo.designsystem.components.BarsVeilInstead
import com.suhel.llamabro.demo.designsystem.components.BottomBar
import com.suhel.llamabro.demo.designsystem.components.GlassFillsInstead
import com.suhel.llamabro.demo.designsystem.components.GlassPreview
import com.suhel.llamabro.demo.designsystem.components.GroupScope
import com.suhel.llamabro.demo.designsystem.components.GroupedList
import com.suhel.llamabro.demo.designsystem.components.ListRow
import com.suhel.llamabro.demo.designsystem.components.Notice
import com.suhel.llamabro.demo.designsystem.components.Section
import com.suhel.llamabro.demo.designsystem.components.SectionNote
import com.suhel.llamabro.demo.designsystem.components.SegmentedSwitch
import com.suhel.llamabro.demo.designsystem.components.SliderRow
import com.suhel.llamabro.demo.designsystem.theme.BarGlass
import com.suhel.llamabro.demo.designsystem.theme.GlassPress
import com.suhel.llamabro.demo.designsystem.theme.GlassStyle
import com.suhel.llamabro.demo.designsystem.theme.GlassTheme
import com.suhel.llamabro.demo.designsystem.theme.GlassTier
import com.suhel.llamabro.demo.designsystem.theme.LocalGlass
import com.suhel.llamabro.demo.designsystem.theme.LocalGlassTier
import com.suhel.llamabro.demo.designsystem.theme.animationsDisabled
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Settings › Appearance › Glass (D152): a slider for every value of the app's glass (the chips', the panels' and the
 * bars'), each saved and applied across the app as it is let go ([onChange]), so the look is tuned by eye; a sample
 * chip and panel float at the bottom ([GlassPreview]) over the sliders as they scroll. Copy values puts them on the
 * clipboard as the Kotlin of [GlassTheme.Default], and Reset forgets them ([onChange] with null). How glass answers a
 * finger (D155) comes first, with its own Reset, since the sample is there to press and drag while it is tuned.
 */
@Composable
fun GlassScreen(onChange: (GlassTheme?) -> Unit, onBack: () -> Unit) {
    val glass = LocalGlass.current
    val context = LocalContext.current
    SettingsPage(
        "Glass",
        onBack,
        Modifier.testTag("glass_settings"),
        bottomBar = { BottomBar { GlassPreview() } },
    ) {
        Notice("Each change applies across the app when you let go, and is kept. Scroll to see it in the glass below.")
        if (LocalGlassTier.current == GlassTier.Light) {
            Notice(
                "This phone draws lighter glass, to keep scrolling smooth: a coarser blur under the bars, which " +
                    "draw the glass on them (Automatic), or no blur inside chips and panels that draw their own.",
            )
        }
        Section("Glass on the bars") {
            val choices = listOf(null, false, true)
            SegmentedSwitch(
                listOf("Automatic", "Each its own", "Drawn by the bar"),
                choices.indexOf(glass.mergedLens),
                { onChange(glass.copy(mergedLens = choices[it])) },
                Modifier.fillMaxWidth().testTag("glass_merged"),
            )
        }
        // first among the values: the sample at the bottom is right there to press and drag while tuning them (D155)
        Section(
            "Press: how glass answers a finger",
            action = "Reset".takeIf { glass.press != GlassPress.Default },
            onAction = { onChange(glass.copy(press = GlassPress.Default)) },
        ) {
            SectionNote(
                if (animationsDisabled()) {
                    "Animations are off on this phone, so pressed glass only lights up: it doesn't swell or move."
                } else {
                    "Press and drag the chip or the panel at the bottom: it swells, lights up under your finger and " +
                        "pulls after it, then springs back when you let go."
                },
            )
            GroupedList { pressSliders(glass.press) { onChange(glass.copy(press = it)) } }
        }
        Section("Chips: icon buttons, the title") {
            GroupedList { styleSliders("chip", glass.chip) { onChange(glass.copy(chip = it)) } }
        }
        Section("Panels: the message bar, fields, tabs, FAB") {
            GroupedList { styleSliders("panel", glass.panel) { onChange(glass.copy(panel = it)) } }
        }
        Section("Bars: the blur under the top and bottom") {
            GroupedList { barSliders(glass.bar) { onChange(glass.copy(bar = it)) } }
        }
        Section("Values") {
            GroupedList {
                row {
                    ListRow("Copy values", subtitle = "As code, to make them the app's defaults", onClick = {
                        val clipboard = context.getSystemService(ClipboardManager::class.java)
                        clipboard?.setPrimaryClip(ClipData.newPlainText("Glass", describe(glass)))
                    })
                }
                row { ListRow("Reset to defaults", onClick = { onChange(null) }) }
            }
        }
    }
}

/** Sliders for a chip's or a panel's [style], keyed by [kind]. */
private fun GroupScope.styleSliders(kind: String, style: GlassStyle, onChange: (GlassStyle) -> Unit) {
    dp("$kind-bezel", "Edge width", style.bezel, MAX_EDGE) { onChange(style.copy(bezel = it)) }
    dp("$kind-refraction", "Refraction", style.refraction, MAX_BEND) { onChange(style.copy(refraction = it)) }
    share("$kind-dispersion", "Colour split", style.dispersion, MAX_SPLIT) { onChange(style.copy(dispersion = it)) }
    dp("$kind-blur", "Blur", style.blur, MAX_BLUR) { onChange(style.copy(blur = it)) }
    share("$kind-veil", "Tint", style.veil) { onChange(style.copy(veil = it)) }
    share("$kind-rim-light", "Rim light", style.rimLight) { onChange(style.copy(rimLight = it)) }
    dp("$kind-rim", "Rim width", style.rim, MAX_RIM, step = HALF) { onChange(style.copy(rim = it)) }
    // the fill drawn where a lens can't be: only a phone that draws it gets its slider
    if (GlassFillsInstead) {
        share("$kind-bare", "Fill without a lens", style.bareVeil) {
            onChange(style.copy(bareVeil = it))
        }
    }
}

/** Sliders for the bars' [bar] glass. */
private fun GroupScope.barSliders(bar: BarGlass, onChange: (BarGlass) -> Unit) {
    dp("bar-blur", "Blur", bar.blur, MAX_BAR_BLUR) { onChange(bar.copy(blur = it)) }
    dp("bar-dispersion", "Colour split", bar.dispersion, MAX_BAR_SPLIT) { onChange(bar.copy(dispersion = it)) }
    share("bar-veil", "Tint", bar.veil) { onChange(bar.copy(veil = it)) }
    if (BarsVeilInstead) share("bar-bare", "Tint without blur", bar.bareVeil) { onChange(bar.copy(bareVeil = it)) }
    dp("bar-fade", "Reach past the bar", bar.fade, MAX_FADE) { onChange(bar.copy(fade = it)) }
    share("bar-hold", "Full blur over", bar.hold) { onChange(bar.copy(hold = it)) }
}

/**
 * Sliders for how pressed glass answers ([press], D155): the swell as the share it grows by, so 0 is none and the
 * slider's travel is all in the range worth trying.
 */
private fun GroupScope.pressSliders(press: GlassPress, onChange: (GlassPress) -> Unit) {
    share("press-swell", "Swell", press.swell - 1f, MAX_SWELL) { onChange(press.copy(swell = 1f + it)) }
    share("press-light", "Light at the finger", press.light) { onChange(press.copy(light = it)) }
    dp("press-reach", "Light's reach", press.reach, MAX_REACH) { onChange(press.copy(reach = it)) }
    share("press-bend", "Extra refraction", press.bend, MAX_PRESS_BEND) { onChange(press.copy(bend = it)) }
    dp("press-pull", "Pull when dragged", press.pull, MAX_PULL) { onChange(press.copy(pull = it)) }
    share("press-stretch", "Stretch when dragged", press.stretch, MAX_STRETCH) { onChange(press.copy(stretch = it)) }
}

/** A length from 0 to [max] in steps of [step] dp. */
private fun GroupScope.dp(
    key: String,
    title: String,
    value: Dp,
    max: Float,
    step: Float = 1f,
    onChange: (Dp) -> Unit,
) {
    slider(key, title, value.value, max, step, { "${trim(it)} dp" }) { onChange(it.dp) }
}

/** A share from 0 to [max] (1 unless said) in hundredths. */
private fun GroupScope.share(
    key: String,
    title: String,
    value: Float,
    max: Float = 1f,
    onChange: (Float) -> Unit,
) {
    slider(key, title, value, max, HUNDREDTH, { String.format(Locale.ROOT, "%.2f", it) }, onChange)
}

private fun GroupScope.slider(
    key: String,
    title: String,
    value: Float,
    max: Float,
    step: Float,
    format: (Float) -> String,
    onChange: (Float) -> Unit,
) {
    row(key = key) {
        SliderRow(
            title,
            value.coerceIn(0f, max),
            format,
            0f..max,
            steps = (max / step).roundToInt() - 1,
            onValueChange = { onChange((it / step).roundToInt() * step) },
            Modifier.testTag("glass_$key"),
        )
    }
}

private fun trim(value: Float): String = if (value % 1f == 0f) value.roundToInt().toString() else value.toString()

/** The glass as the Kotlin of [GlassTheme.Default]'s values, to paste into it. */
internal fun describe(glass: GlassTheme): String {
    fun style(s: GlassStyle) =
        "GlassStyle(bezel = ${trim(s.bezel.value)}.dp, refraction = ${trim(s.refraction.value)}.dp, " +
            "dispersion = ${s.dispersion}f, blur = ${trim(s.blur.value)}.dp, veil = ${s.veil}f, " +
            "rimLight = ${s.rimLight}f, rim = ${trim(s.rim.value)}.dp, bareVeil = ${s.bareVeil}f)"
    val b = glass.bar
    val p = glass.press
    return "mergedLens = ${glass.mergedLens}\n" +
        "chip = ${style(glass.chip)}\n" +
        "panel = ${style(glass.panel)}\n" +
        "bar = BarGlass(blur = ${trim(b.blur.value)}.dp, dispersion = ${trim(b.dispersion.value)}.dp, " +
        "veil = ${b.veil}f, bareVeil = ${b.bareVeil}f, fade = ${trim(b.fade.value)}.dp, hold = ${b.hold}f)\n" +
        "press = GlassPress(swell = ${p.swell}f, light = ${p.light}f, reach = ${trim(p.reach.value)}.dp, " +
        "bend = ${p.bend}f, pull = ${trim(p.pull.value)}.dp, stretch = ${p.stretch}f)"
}

private const val MAX_EDGE = 24f
private const val MAX_BEND = 32f
private const val MAX_SPLIT = 2f
private const val MAX_BLUR = 32f
private const val MAX_RIM = 4f
private const val MAX_BAR_BLUR = 48f
private const val MAX_BAR_SPLIT = 20f
private const val MAX_FADE = 64f

// past a quarter, swollen glass crowds what sits beside it: it draws past its bounds (D155)
private const val MAX_SWELL = 0.25f
private const val MAX_PULL = 32f
private const val MAX_STRETCH = 0.3f
private const val MAX_REACH = 96f
private const val MAX_PRESS_BEND = 2f
private const val HALF = 0.5f
private const val HUNDREDTH = 0.01f
