package com.suhel.llamabro.demo.designsystem.theme

import android.content.ContentResolver
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext

/** Reference C, settled here: standard 250 ms ease; layout switch crossfade 300 ms; the wake sweep 1,400 ms linear. */
object DemoMotion {
    const val STANDARD_MILLIS = 250
    const val CROSSFADE_MILLIS = 300
    const val SWEEP_MILLIS = 1_400

    /** The sweep's gradient covers this share of the width. */
    const val SWEEP_WIDTH_FRACTION = 0.30f
    val standard: Easing = FastOutSlowInEasing
    val sweep: Easing = LinearEasing
}

/**
 * The animator duration scale to use instead of the system's, or null to read the system's. Tests set it, because
 * they must never change the phone's setting.
 */
internal val LocalAnimatorDurationScale = staticCompositionLocalOf<Float?> { null }

/**
 * True when the user turned animations off (`ANIMATOR_DURATION_SCALE == 0`): no sweep, a static hairline.
 * The setting is read once per composition entry and again only when it changes (a [ContentObserver]),
 * never on each recomposition.
 */
@Composable
fun animationsDisabled(): Boolean {
    val override = LocalAnimatorDurationScale.current
    val resolver = LocalContext.current.contentResolver
    var scale by remember(resolver) { mutableFloatStateOf(resolver.animatorDurationScale()) }
    DisposableEffect(resolver) {
        val observer =
            object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) {
                    scale = resolver.animatorDurationScale()
                }
            }
        resolver.registerContentObserver(
            Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE),
            false,
            observer,
        )
        onDispose { resolver.unregisterContentObserver(observer) }
    }
    return (override ?: scale) == 0f
}

private fun ContentResolver.animatorDurationScale(): Float =
    Settings.Global.getFloat(this, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
