package com.suhel.llamabro.demo.designsystem.components

import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import com.suhel.llamabro.demo.designsystem.theme.DemoBrand
import com.suhel.llamabro.demo.designsystem.theme.DemoMotion
import com.suhel.llamabro.demo.designsystem.theme.DemoSize
import com.suhel.llamabro.demo.designsystem.theme.DemoType
import com.suhel.llamabro.demo.designsystem.theme.LocalDemoColors
import com.suhel.llamabro.demo.designsystem.theme.animationsDisabled
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** The icon's warmth: amber at the lit point falling to rose at the far edge, the same in both themes. */
private val Stops = arrayOf(0.00f to DemoBrand.amber, 1.00f to DemoBrand.rose)

/** The two sizes the orb is drawn at. */
enum class OrbSize(internal val size: Dp) {
    /** First run's hero. */
    Hero(DemoSize.markHero),

    /** An empty chat. */
    Empty(DemoSize.markEmpty),
}

/** The orb at one of the app's two sizes. */
@Composable
fun Orb(size: OrbSize, modifier: Modifier = Modifier) {
    Orb(size.size, modifier)
}

/**
 * The orb: a radial gradient centred at (34%, 28%), amber falling to rose, with a soft rose shadow. It is the app's
 * only hue outside the fit words and Remove (D69). 56 dp on the first run, 40 dp in an empty chat.
 */
@Composable
fun Orb(size: Dp, modifier: Modifier = Modifier) {
    val colors = LocalDemoColors.current
    // Android's shadow is fainter than CSS's at the same alpha, hence 0.22 light and 0.35 dark; elevation = a fifth of
    // the size.
    val tint = DemoBrand.rose.copy(alpha = if (colors.isDark) 0.35f else 0.22f)
    Canvas(
        modifier
            .size(size)
            .shadow(elevation = size / 5, shape = CircleShape, ambientColor = tint, spotColor = tint),
    ) {
        val center = Offset(this.size.width * 0.34f, this.size.height * 0.28f)
        // CSS's "farthest-corner": the last stop sits at the corner farthest from the centre
        val radius = hypot(this.size.width - center.x, this.size.height - center.y)
        drawCircle(
            Brush.radialGradient(
                colorStops = Stops,
                center = center,
                radius = radius,
            ),
        )
    }
}

/** The serif greeting of the first run ("Good evening"), and the serif titles of whole screens ("Chats"). */
@Composable
fun Greeting(text: String, modifier: Modifier = Modifier, textAlign: TextAlign = TextAlign.Center) {
    Text(text, modifier, color = LocalDemoColors.current.ink, style = DemoType.displayHero, textAlign = textAlign)
}

/** The serif line of an empty state ("What's on your mind?"). */
@Composable
fun EmptyLine(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier,
        color = LocalDemoColors.current.ink,
        style = DemoType.displayHero,
        textAlign = TextAlign.Center,
    )
}

/**
 * A model's name in serif, with a second line in sans under it when there is one: its maker ("by Alibaba").
 */
@Composable
fun ModelName(name: String, modifier: Modifier = Modifier, realName: String? = null, centered: Boolean = false) {
    val colors = LocalDemoColors.current
    Column(modifier, horizontalAlignment = if (centered) Alignment.CenterHorizontally else Alignment.Start) {
        Text(name, color = colors.ink, style = DemoType.sheetTitle)
        if (realName != null) Text(realName, color = colors.muted, style = DemoType.bodyMedium)
    }
}

/** What the voice view's orb is doing (D100). */
enum class OrbMood { Still, Listening, Thinking, Speaking }

/**
 * The orb at the voice view's size (D100), alive with the conversation: [Still] when ready; while [Listening] it
 * swells with the voice's [level] (0 to 1) and a hairline ring spreads from it; while [Thinking] a soft highlight turns
 * inside it; while [Speaking] it moves with the reply's [level]. With animations off it holds still in every mood, and
 * the screen's state line carries the meaning.
 */
@Composable
fun VoiceOrb(mood: OrbMood, level: Float, modifier: Modifier = Modifier) {
    val colors = LocalDemoColors.current
    val still = animationsDisabled()
    val clamped = level.coerceIn(0f, 1f)
    val swell =
        when {
            still -> 0f
            mood == OrbMood.Listening -> LISTEN_SWELL * clamped
            mood == OrbMood.Speaking -> SPEAK_SWELL * clamped
            else -> 0f
        }
    val scale by animateFloatAsState(
        1f + swell,
        tween(DemoMotion.SWAP_MILLIS, easing = DemoMotion.standard),
        label = "orb",
    )
    val turning = mood == OrbMood.Thinking && !still
    val angle =
        if (turning) {
            val turn = rememberInfiniteTransition(label = "orb turn")
            turn
                .animateFloat(
                    0f,
                    FULL_TURN,
                    infiniteRepeatable(tween(DemoMotion.ORB_TURN_MILLIS, easing = DemoMotion.sweep)),
                    label = "orb angle",
                ).value
        } else {
            START_ANGLE
        }
    val tint = DemoBrand.rose.copy(alpha = if (colors.isDark) 0.35f else 0.22f)
    val size = DemoSize.orbVoice
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        if (mood == OrbMood.Listening && !still) {
            Canvas(Modifier.fillMaxSize()) {
                val radius = this.size.minDimension / 2 * (RING_FROM + RING_SPREAD * clamped)
                drawCircle(
                    colors.muted.copy(alpha = RING_ALPHA * (1f - clamped / 2)),
                    radius = radius,
                    style = Stroke(DemoSize.hairline.toPx()),
                )
            }
        }
        Canvas(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }.shadow(elevation = size / 5, shape = CircleShape, ambientColor = tint, spotColor = tint),
        ) {
            val center = Offset(this.size.width * 0.34f, this.size.height * 0.28f)
            val radius = hypot(this.size.width - center.x, this.size.height - center.y)
            drawCircle(Brush.radialGradient(colorStops = Stops, center = center, radius = radius))
            if (mood == OrbMood.Thinking) {
                // the light turns about the centre, starting where the gradient is lit
                val r = this.size.minDimension / 2
                val rad = Math.toRadians(angle.toDouble())
                val spot =
                    Offset(
                        this.center.x + r * HIGHLIGHT_ORBIT * cos(rad).toFloat(),
                        this.center.y + r * HIGHLIGHT_ORBIT * sin(rad).toFloat(),
                    )
                drawCircle(
                    Brush.radialGradient(
                        listOf(DemoBrand.highlight, DemoBrand.highlight.copy(alpha = 0f)),
                        center = spot,
                        radius =
                            r * HIGHLIGHT_SIZE,
                    ),
                    radius = r,
                )
            }
        }
    }
}

private const val LISTEN_SWELL = 0.09f
private const val SPEAK_SWELL = 0.06f
private const val RING_FROM = 1.04f
private const val RING_SPREAD = 0.2f
private const val RING_ALPHA = 0.5f
private const val FULL_TURN = 360f

/** Where the turning light starts: up and to the left, where the gradient's lit point is (34%, 28%). */
private const val START_ANGLE = 225f
private const val HIGHLIGHT_ORBIT = 0.45f
private const val HIGHLIGHT_SIZE = 0.85f
