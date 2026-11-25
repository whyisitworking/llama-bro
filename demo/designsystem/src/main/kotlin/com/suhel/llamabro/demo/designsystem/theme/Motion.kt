package com.suhel.llamabro.demo.designsystem.theme

import android.content.ContentResolver
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.animation.core.CubicBezierEasing
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

/**
 * The motion spec, Material 3's durations and easings (demo design §8.11, D58). Things that arrive take
 * [ENTER_MILLIS] on [enter] (emphasized decelerate: fast, then settling); things that leave go quicker, [EXIT_MILLIS]
 * on [exit] (emphasized accelerate), since the eye has stopped following them. A change in place (a fold opening, a
 * bar growing, a colour) takes [STANDARD_MILLIS] on [standard]. Loops: the wake sweep, the thinking shimmer and the
 * typing dots, all still when animations are off ([animationsDisabled]).
 */
object DemoMotion {
    const val STANDARD_MILLIS = 250
    const val CROSSFADE_MILLIS = 300

    /** Material's medium2 and short3: an arrival, and a departure. */
    const val ENTER_MILLIS = 300
    const val EXIT_MILLIS = 150

    /**
     * Text that changes in place fades through (D119): the old words fade out in [TEXT_OUT_MILLIS], then the new ones
     * fade in over [TEXT_IN_MILLIS], so two texts never show at once (Material's fade through, short2 and short4 less
     * a frame). A list's arriving item waits [TEXT_OUT_MILLIS] too, so it never shares a slot with one still leaving.
     */
    const val TEXT_OUT_MILLIS = 90
    const val TEXT_IN_MILLIS = 120

    /** A small thing swapping in place: the send icon turning into stop (Material's short4). */
    const val SWAP_MILLIS = 200
    const val SWEEP_MILLIS = 1_400

    /** Voice mode's thinking: the orb's highlight turns once in this time (D100). */
    const val ORB_TURN_MILLIS = 2_400

    /** One wave across the three typing dots, each a fifth of the cycle after the one before. */
    const val DOTS_MILLIS = 1_200
    const val DOTS_STAGGER = 0.2f

    /** A button arriving grows from this share of its size while it fades in (Material's FAB). */
    const val POP_SCALE = 0.8f

    /**
     * Pressed glass (D155) swells on a spring that lands just past full ([PRESS_DAMPING], [PRESS_STIFFNESS]) and
     * settles back on a softer one ([RELEASE_DAMPING], [RELEASE_STIFFNESS]); a tap holds the press [PRESS_HOLD_MILLIS]
     * at least, or a quick one, released the frame it lands, would show nothing.
     */
    const val PRESS_DAMPING = 0.6f
    const val PRESS_STIFFNESS = 700f
    const val RELEASE_DAMPING = 0.5f
    const val RELEASE_STIFFNESS = 350f
    const val PRESS_HOLD_MILLIS = 90L

    /** The sweep's gradient covers this share of the width. */
    const val SWEEP_WIDTH_FRACTION = 0.30f
    val standard: Easing = FastOutSlowInEasing
    val enter: Easing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
    val exit: Easing = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)
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

/** The system's animator duration scale; 0 when the person turned animations off. */
internal fun ContentResolver.animatorDurationScale(): Float =
    Settings.Global.getFloat(this, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
