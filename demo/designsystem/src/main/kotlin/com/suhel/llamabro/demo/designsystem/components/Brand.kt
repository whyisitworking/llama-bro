package com.suhel.llamabro.demo.designsystem.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import com.suhel.llamabro.demo.designsystem.R
import com.suhel.llamabro.demo.designsystem.theme.DemoBrand
import com.suhel.llamabro.demo.designsystem.theme.DemoMotion
import com.suhel.llamabro.demo.designsystem.theme.DemoSize
import com.suhel.llamabro.demo.designsystem.theme.DemoType
import com.suhel.llamabro.demo.designsystem.theme.LocalDemoColors
import com.suhel.llamabro.demo.designsystem.theme.animationsDisabled

/** The two sizes the logo is drawn at. */
enum class LogoSize(internal val size: Dp) {
    /** First run's hero. */
    Hero(DemoSize.markHero),

    /** An empty chat. */
    Empty(DemoSize.markEmpty),
}

/** The logo at one of the app's two sizes. */
@Composable
fun Logo(size: LogoSize, modifier: Modifier = Modifier) {
    Logo(size.size, modifier)
}

/**
 * The logo, the app icon's llama alone (D161), [size] tall: in the dark theme the icon's own artwork, its outline
 * glowing violet and amber as on its night; in the light theme, where that white glow would vanish, its traced outline
 * in the ink. 56 dp on the first run, 72 dp in an empty chat. Decorative: the words beside it say what it is for.
 */
@Composable
fun Logo(size: Dp, modifier: Modifier = Modifier) {
    val colors = LocalDemoColors.current
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        // the icon's frame is 108 units with the llama 68 tall in its middle: drawn larger, the llama fills the size
        val frame = Modifier.requiredSize(size * ICON_FRAME / LLAMA_HEIGHT)
        if (colors.isDark) {
            Image(painterResource(R.drawable.logo_llama_glow), contentDescription = null, modifier = frame)
        } else {
            Image(
                painterResource(R.drawable.logo_llama_outline),
                contentDescription = null,
                modifier = frame,
                colorFilter = ColorFilter.tint(colors.ink),
            )
        }
    }
}

/** The adaptive icon's frame, and the llama's height in it, in the icon's units (`ic_launcher_monochrome.xml`). */
private const val ICON_FRAME = 108f
private const val LLAMA_HEIGHT = 68f

/** The serif greeting of the first run ("Good evening"), and the serif titles of whole screens ("Chats"). */
@Composable
fun Greeting(text: String, modifier: Modifier = Modifier, textAlign: TextAlign = TextAlign.Center) {
    Text(text, modifier, color = LocalDemoColors.current.ink, style = DemoType.displayHero, textAlign = textAlign)
}

/**
 * The serif greeting of an empty chat ("What's on your mind?"), and nothing else: an error, an empty list or a search
 * with no results is a [Notice], one plain line where the content would be.
 */
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

/**
 * The logo at the voice view's size (D100, D161), alive with the conversation: still while the [phase] is
 * [VoicePhase.Ready]; while [VoicePhase.Listening] it swells with the voice's [level] (0 to 1) and a warm glow behind
 * it brightens with it; while [VoicePhase.Thinking] that glow breathes; while [VoicePhase.Speaking] it moves
 * with the reply's [level]. With animations off it holds still in every phase, and the screen's state line carries the
 * meaning.
 */
@Composable
fun VoiceLogo(phase: VoicePhase, level: Float, modifier: Modifier = Modifier) {
    val still = animationsDisabled()
    val clamped = level.coerceIn(0f, 1f)
    val swell =
        when {
            still -> 0f
            phase == VoicePhase.Listening -> LISTEN_SWELL * clamped
            phase == VoicePhase.Speaking -> SPEAK_SWELL * clamped
            else -> 0f
        }
    val scale by animateFloatAsState(
        1f + swell,
        tween(DemoMotion.SWAP_MILLIS, easing = DemoMotion.standard),
        label = "logo",
    )
    // listening, the glow follows the voice, eased so it swells and fades rather than flickering with each sample
    val heard by animateFloatAsState(
        if (phase == VoicePhase.Listening && !still) LISTEN_GLOW_FLOOR + (1f - LISTEN_GLOW_FLOOR) * clamped else 0f,
        tween(DemoMotion.STANDARD_MILLIS, easing = DemoMotion.standard),
        label = "logo heard",
    )
    val breathing = phase == VoicePhase.Thinking && !still
    val breath =
        if (breathing) {
            rememberInfiniteTransition(label = "logo glow")
                .animateFloat(
                    0f,
                    1f,
                    infiniteRepeatable(
                        tween(DemoMotion.LOGO_BREATH_MILLIS / 2, easing = DemoMotion.standard),
                        RepeatMode.Reverse,
                    ),
                    label = "logo glow strength",
                ).value
        } else {
            0f
        }
    val glow = maxOf(heard, breath)
    val size = DemoSize.logoVoice
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        if (glow > 0f) {
            Canvas(Modifier.fillMaxSize()) {
                val warm = DemoBrand.amber.copy(alpha = GLOW_ALPHA * glow)
                drawCircle(Brush.radialGradient(listOf(warm, warm.copy(alpha = 0f))))
            }
        }
        Logo(
            size,
            Modifier.graphicsLayer {
                scaleX = scale
                scaleY = scale
            },
        )
    }
}

private const val LISTEN_SWELL = 0.09f
private const val SPEAK_SWELL = 0.06f

/** How strong the listening glow is in silence, as a share of its fullest: it shows the logo hears, voice or none. */
private const val LISTEN_GLOW_FLOOR = 0.3f

/** How bright the glow behind the logo, listening or thinking, is at its fullest. */
private const val GLOW_ALPHA = 0.35f
