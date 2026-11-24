package com.suhel.llamabro.demo.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.suhel.llamabro.demo.designsystem.components.GroupScope
import com.suhel.llamabro.demo.designsystem.components.GroupedList
import com.suhel.llamabro.demo.designsystem.components.ListRow
import com.suhel.llamabro.demo.designsystem.components.Notice
import com.suhel.llamabro.demo.designsystem.components.Section
import com.suhel.llamabro.demo.designsystem.components.SliderRow
import com.suhel.llamabro.demo.designsystem.theme.BarGlass
import com.suhel.llamabro.demo.designsystem.theme.GlassStyle
import com.suhel.llamabro.demo.designsystem.theme.GlassTheme
import java.util.Locale
import kotlin.math.roundToInt

/**
 * The glass the app draws, tunable while it runs (debug builds only, D151): the shell provides [theme] as the app's
 * glass, and the Glass screen changes it. It lasts until the app closes; the values worth keeping are copied from the
 * screen into [GlassTheme.Default].
 */
@Stable
class GlassTuner {
    var theme by mutableStateOf(GlassTheme.Default)
}

/** The app's [GlassTuner], where the build has one (debug); null in a release build, which draws the default glass. */
val LocalGlassTuner = staticCompositionLocalOf<GlassTuner?> { null }

/**
 * Settings › Glass (debug builds only, D151): a slider for every value of the app's glass (the chips', the panels' and
 * the bars'), applied across the app as each is let go, so the look is tuned by eye on the phone; Copy values puts them
 * on the clipboard to be made the defaults, and Reset brings the defaults back.
 */
@Composable
fun GlassScreen(onBack: () -> Unit) {
    val tuner = LocalGlassTuner.current ?: return
    val glass = tuner.theme
    val context = LocalContext.current
    SettingsPage("Glass", onBack, Modifier.testTag("glass_settings")) {
        Notice("Debug build only. Each change applies across the app when you let go, and lasts until the app closes.")
        Section("Chips: icon buttons, the title") {
            GroupedList { styleSliders("chip", glass.chip) { tuner.theme = glass.copy(chip = it) } }
        }
        Section("Panels: the message bar, fields, tabs, FAB") {
            GroupedList { styleSliders("panel", glass.panel) { tuner.theme = glass.copy(panel = it) } }
        }
        Section("Bars: the blur under the top and bottom") {
            GroupedList { barSliders(glass.bar) { tuner.theme = glass.copy(bar = it) } }
        }
        Section("Values") {
            GroupedList {
                row {
                    ListRow("Copy values", subtitle = "To make them the defaults", onClick = {
                        val clipboard = context.getSystemService(ClipboardManager::class.java)
                        clipboard?.setPrimaryClip(ClipData.newPlainText("Glass", describe(glass)))
                    })
                }
                row { ListRow("Reset to defaults", onClick = { tuner.theme = GlassTheme.Default }) }
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
    share("$kind-bare", "Tint without blur", style.bareVeil) { onChange(style.copy(bareVeil = it)) }
}

/** Sliders for the bars' [bar] glass. */
private fun GroupScope.barSliders(bar: BarGlass, onChange: (BarGlass) -> Unit) {
    dp("bar-blur", "Blur", bar.blur, MAX_BAR_BLUR) { onChange(bar.copy(blur = it)) }
    dp("bar-dispersion", "Colour split", bar.dispersion, MAX_BAR_SPLIT) { onChange(bar.copy(dispersion = it)) }
    share("bar-veil", "Tint", bar.veil) { onChange(bar.copy(veil = it)) }
    share("bar-bare", "Tint without blur", bar.bareVeil) { onChange(bar.copy(bareVeil = it)) }
    dp("bar-fade", "Reach past the bar", bar.fade, MAX_FADE) { onChange(bar.copy(fade = it)) }
    share("bar-hold", "Full blur over", bar.hold) { onChange(bar.copy(hold = it)) }
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
    return "chip = ${style(glass.chip)}\n" +
        "panel = ${style(glass.panel)}\n" +
        "bar = BarGlass(blur = ${trim(b.blur.value)}.dp, dispersion = ${trim(b.dispersion.value)}.dp, " +
        "veil = ${b.veil}f, bareVeil = ${b.bareVeil}f, fade = ${trim(b.fade.value)}.dp, hold = ${b.hold}f)"
}

private const val MAX_EDGE = 24f
private const val MAX_BEND = 32f
private const val MAX_SPLIT = 2f
private const val MAX_BLUR = 32f
private const val MAX_RIM = 4f
private const val MAX_BAR_BLUR = 48f
private const val MAX_BAR_SPLIT = 20f
private const val MAX_FADE = 64f
private const val HALF = 0.5f
private const val HUNDREDTH = 0.01f
