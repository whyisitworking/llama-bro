package com.suhel.llamabro.demo.designsystem.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.suhel.llamabro.demo.designsystem.R
import com.suhel.llamabro.demo.designsystem.theme.DemoMotion
import com.suhel.llamabro.demo.designsystem.theme.DemoSize
import com.suhel.llamabro.demo.designsystem.theme.DemoSpacing
import com.suhel.llamabro.demo.designsystem.theme.DemoType
import com.suhel.llamabro.demo.designsystem.theme.LocalDemoColors
import com.suhel.llamabro.demo.designsystem.theme.animationsDisabled
import kotlin.math.PI
import kotlin.math.sin

/**
 * A message list's row (its [row] scope) that fades in when it arrives (a sent message, a day, the reply starting)
 * and glides when the rows above it change size; the rows of a chat that opens are already there and do not fade.
 * [appear] is false for a row that replaces one already on screen (a finished reply taking the place of the streaming
 * one), which would otherwise blink.
 */
fun Modifier.messageMotion(row: LazyItemScope, appear: Boolean = true): Modifier =
    with(row) {
        this@messageMotion.animateItem(
            fadeInSpec = if (appear) tween(DemoMotion.ENTER_MILLIS, easing = DemoMotion.enter) else null,
            placementSpec = tween(DemoMotion.STANDARD_MILLIS, easing = DemoMotion.standard),
            fadeOutSpec = null,
        )
    }

/**
 * Something that comes and goes above the message bar (a note, a problem, the editing line): it unfolds from its top
 * and fades in, and folds away quicker. [value] is what to show, or null; while it folds away it keeps showing the last
 * value, so the line does not go blank before it leaves.
 */
@Composable
fun <T : Any> Reveal(value: T?, modifier: Modifier = Modifier, content: @Composable (T) -> Unit) {
    val last = remember { mutableStateOf(value) }
    SideEffect { if (value != null) last.value = value }
    AnimatedVisibility(
        visible = value != null,
        modifier = modifier,
        enter =
            fadeIn(tween(DemoMotion.ENTER_MILLIS, easing = DemoMotion.enter)) +
                expandVertically(tween(DemoMotion.ENTER_MILLIS, easing = DemoMotion.enter), Alignment.Top),
        exit =
            fadeOut(tween(DemoMotion.EXIT_MILLIS, easing = DemoMotion.exit)) +
                shrinkVertically(tween(DemoMotion.EXIT_MILLIS, easing = DemoMotion.exit), Alignment.Top),
    ) {
        (value ?: last.value)?.let { content(it) }
    }
}

/**
 * Three muted dots in a wave, where a reply will be, while the model reads the prompt (or wakes) and before its first
 * word: the chat apps' typing indicator. One line of body text tall, so the first words take its place without a jump.
 * With animations off the dots hold still.
 */
@Composable
fun TypingDots(modifier: Modifier = Modifier) {
    val colors = LocalDemoColors.current
    val label = stringResource(R.string.ds_replying)
    val phase = if (animationsDisabled()) null else rememberLoop(DemoMotion.DOTS_MILLIS)
    val line = with(LocalDensity.current) { DemoType.body.lineHeight.toDp() }
    Row(
        modifier.height(line).semantics { contentDescription = label },
        horizontalArrangement = Arrangement.spacedBy(DemoSpacing.inline),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(DOTS) { index ->
            Box(
                Modifier
                    .size(DemoSize.dot)
                    // read in the draw phase: the wave redraws three dots and recomposes nothing
                    .graphicsLayer { alpha = phase?.let { dotAlpha(it.value, index) } ?: DOT_STILL }
                    .background(colors.muted, CircleShape),
            )
        }
    }
}

/** A value going 0 to 1 over [millis], again and again. */
@Composable
internal fun rememberLoop(millis: Int): State<Float> =
    rememberInfiniteTransition(label = "loop").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(millis, easing = LinearEasing), RepeatMode.Restart),
        label = "phase",
    )

/** A dot is faint, then swells once in its part of the cycle, a stagger after the dot before it. */
private fun dotAlpha(phase: Float, index: Int): Float {
    val local = ((phase - index * DemoMotion.DOTS_STAGGER) % 1f + 1f) % 1f
    val rise = if (local < DOT_PULSE) sin(PI * local / DOT_PULSE).toFloat() else 0f
    return DOT_FAINT + (1f - DOT_FAINT) * rise
}

private const val DOTS = 3
private const val DOT_FAINT = 0.3f
private const val DOT_STILL = 0.6f

/** The share of the cycle a dot spends swelling. */
private const val DOT_PULSE = 0.4f
