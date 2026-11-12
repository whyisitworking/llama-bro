package com.suhel.llamabro.demo.designsystem.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.suhel.llamabro.demo.designsystem.theme.DemoMotion
import com.suhel.llamabro.demo.designsystem.theme.LocalDemoColors
import com.suhel.llamabro.demo.designsystem.theme.animationsDisabled

/**
 * The model waking up (pd-design 4.1.4): a fine accent hairline across the top of the reply area. While [active] a
 * 30%-wide gradient sweeps across it every 1,400 ms; with animations off it is a static hairline. Not a progress
 * indicator: no spinner, no percentage, no progress semantics. Inactive, it keeps its 2 dp and draws nothing.
 * The sweep is read in the draw phase only, so it never recomposes.
 */
@Composable
fun WakeHairline(active: Boolean, modifier: Modifier = Modifier) {
    val colors = LocalDemoColors.current
    val still = animationsDisabled()
    // while it sweeps it says so (a state, not a progress: there is no value to read)
    val base =
        modifier
            .testTag(DsTags.WAKE_HAIRLINE)
            .semantics { if (active) stateDescription = "Waking up" }
            .fillMaxWidth()
            .height(2.dp)
            .clip(RoundedCornerShape(1.dp))
    when {
        !active -> {
            Box(base)
        }

        still -> {
            Box(
                base.drawBehind {
                    drawRect(Brush.horizontalGradient(listOf(Color.Transparent, colors.accent, Color.Transparent)))
                },
            )
        }

        else -> {
            val sweep = rememberSweep()
            Box(
                base.drawBehind {
                    val band = size.width * DemoMotion.SWEEP_WIDTH_FRACTION
                    val start = -band + sweep.value * (size.width + band)
                    drawRect(
                        Brush.horizontalGradient(
                            listOf(Color.Transparent, colors.accent, Color.Transparent),
                            startX = start,
                            endX = start + band,
                        ),
                    )
                },
            )
        }
    }
}

@Composable
private fun rememberSweep(): State<Float> =
    rememberInfiniteTransition(label = "wake").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec =
            infiniteRepeatable(
                tween(DemoMotion.SWEEP_MILLIS, easing = DemoMotion.sweep),
                RepeatMode.Restart,
            ),
        label = "sweep",
    )

/**
 * The download ring beside a model's name while it arrives: the accent arc over a soft accent track, easing to each
 * new [fraction]. [label] ("Arriving · 38% · about 40 s") is what a screen reader says.
 */
@Composable
fun DownloadRing(fraction: Float, modifier: Modifier = Modifier, label: String? = null, size: Dp = 28.dp) {
    val colors = LocalDemoColors.current
    val shown by animateFloatAsState(
        fraction.coerceIn(0f, 1f),
        tween(DemoMotion.STANDARD_MILLIS, easing = DemoMotion.standard),
        label = "ring",
    )
    Canvas(
        modifier.size(size).semantics {
            progressBarRangeInfo = ProgressBarRangeInfo(fraction.coerceIn(0f, 1f), 0f..1f)
            if (label != null) contentDescription = label
        },
    ) {
        val stroke = 3.5.dp.toPx()
        val inset = stroke / 2
        val arcSize = Size(this.size.width - stroke, this.size.height - stroke)
        drawArc(colors.accentSoft, 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(stroke))
        drawArc(
            colors.accent,
            -90f,
            360f * shown,
            false,
            Offset(inset, inset),
            arcSize,
            style = Stroke(stroke, cap = StrokeCap.Round),
        )
    }
}

/** A thin progress bar (a download on its row, in the library): the accent over the hairline, 4 dp. */
@Composable
fun ThinBar(fraction: Float, modifier: Modifier = Modifier) {
    Bar(fraction, 4.dp, modifier)
}

/** How much of the phone's storage is used, as a 6 dp bar ([used] of [total] bytes). */
@Composable
fun StorageBar(used: Long, total: Long, modifier: Modifier = Modifier) {
    Bar(if (total > 0) used.toFloat() / total else 0f, 6.dp, modifier)
}

@Composable
private fun Bar(fraction: Float, height: Dp, modifier: Modifier) {
    val colors = LocalDemoColors.current
    val f = fraction.coerceIn(0f, 1f)
    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(height / 2))
            .background(colors.hairline)
            .semantics { progressBarRangeInfo = ProgressBarRangeInfo(f, 0f..1f) },
    ) {
        Box(Modifier.fillMaxWidth(f).fillMaxHeight().background(colors.accent))
    }
}
