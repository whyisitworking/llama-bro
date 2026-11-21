package com.suhel.llamabro.demo.designsystem.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.unit.LayoutDirection
import com.suhel.llamabro.demo.designsystem.theme.DemoMotion
import com.suhel.llamabro.demo.designsystem.theme.DemoSize
import com.suhel.llamabro.demo.designsystem.theme.LocalDemoColors
import com.suhel.llamabro.demo.designsystem.theme.animationsDisabled
import com.suhel.llamabro.demo.designsystem.theme.ringTrack

/**
 * The model waking up (demo design §4.3): a fine accent line across the top of the reply area, [DemoSize.trackThin]
 * high. Once the load reports how far it has come, [progress] (0 to 1), the line fills: the accent over a soft accent
 * track, as the download ring does, easing to each new value. Before that, a 30%-wide gradient sweeps across it every
 * 1,400 ms; with animations off it is a static line. Inactive, it keeps its height and draws nothing. The sweep and the
 * fill are read in the draw phase only, so neither recomposes.
 */
@Composable
fun WakeHairline(active: Boolean, modifier: Modifier = Modifier, progress: Float? = null) {
    val colors = LocalDemoColors.current
    val still = animationsDisabled()
    val base =
        modifier
            .testTag(DsTags.WAKE_HAIRLINE)
            .semantics {
                if (active) {
                    stateDescription = "Waking up"
                    if (progress != null) progressBarRangeInfo = ProgressBarRangeInfo(progress.coerceIn(0f, 1f), 0f..1f)
                }
            }.fillMaxWidth()
            .height(DemoSize.trackThin)
            .clip(CircleShape)
    when {
        !active -> {
            Box(base)
        }

        progress != null -> {
            val shown =
                animateFloatAsState(
                    progress.coerceIn(0f, 1f),
                    tween(if (still) 0 else DemoMotion.STANDARD_MILLIS, easing = DemoMotion.standard),
                    label = "wake",
                )
            Box(
                base.drawBehind {
                    drawRect(colors.accentSoft)
                    val filled = size.width * shown.value
                    // it fills from the start edge: the right in a right-to-left language
                    val x = if (layoutDirection == LayoutDirection.Rtl) size.width - filled else 0f
                    drawRect(colors.accent, topLeft = Offset(x, 0f), size = size.copy(width = filled))
                },
            )
        }

        still -> {
            Box(
                base.drawBehind {
                    drawRect(Brush.horizontalGradient(listOf(Color.Transparent, colors.accent, Color.Transparent)))
                },
            )
        }

        else -> {
            val sweep = rememberLoop(DemoMotion.SWEEP_MILLIS)
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

/**
 * The download ring beside a model's name while it arrives: the accent arc over a soft accent track, easing to each
 * new [fraction]. [label] ("Arriving · 38% · about 40 s") is what a screen reader says. [frozen] draws the arc in the
 * muted ink: a download that is paused or stopped keeps its amount but is not moving (models-page design §4.5).
 */
@Composable
fun DownloadRing(
    fraction: Float,
    modifier: Modifier = Modifier,
    label: String? = null,
    size: Dp = DemoSize.ring,
    frozen: Boolean = false,
    /** False draws each [fraction] as it comes: a panel whose words say the bytes moves its arc with them. */
    eases: Boolean = true,
) {
    val colors = LocalDemoColors.current
    val animated by animateFloatAsState(
        fraction.coerceIn(0f, 1f),
        tween(DemoMotion.STANDARD_MILLIS, easing = DemoMotion.standard),
        label = "ring",
    )
    val shown = if (eases) animated else fraction.coerceIn(0f, 1f)
    Canvas(
        modifier.size(size).semantics {
            progressBarRangeInfo = ProgressBarRangeInfo(fraction.coerceIn(0f, 1f), 0f..1f)
            if (label != null) contentDescription = label
        },
    ) {
        val stroke = DemoSize.ringStroke.toPx()
        val inset = stroke / 2
        val arcSize = Size(this.size.width - stroke, this.size.height - stroke)
        drawArc(colors.ringTrack, 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(stroke))
        drawArc(
            if (frozen) colors.muted else colors.accent,
            -90f,
            360f * shown,
            false,
            Offset(inset, inset),
            arcSize,
            style = Stroke(stroke, cap = StrokeCap.Round),
        )
    }
}

/**
 * A slot for a bar that is not always there (a search's first page loading): it always takes [DemoSize.track] of
 * height, so the list under it doesn't jump when the bar comes and goes. [active] draws an indeterminate bar, a band
 * of accent sweeping across a soft accent track (still, with animations off); inactive, nothing.
 */
@Composable
fun LoadingSlot(active: Boolean, modifier: Modifier = Modifier) {
    val colors = LocalDemoColors.current
    val still = animationsDisabled()
    val slot = modifier.fillMaxWidth().height(DemoSize.track)
    if (!active) {
        Box(slot)
        return
    }
    val sweep = if (still) null else rememberLoop(DemoMotion.SWEEP_MILLIS)
    Box(
        slot
            .semantics { stateDescription = "Loading" }
            .clip(CircleShape)
            .drawBehind {
                drawRect(colors.accentSoft)
                val band = size.width * DemoMotion.SWEEP_WIDTH_FRACTION
                val start = if (sweep == null) (size.width - band) / 2 else -band + sweep.value * (size.width + band)
                drawRect(colors.accent, topLeft = Offset(start, 0f), size = Size(band, size.height))
            },
    )
}

/** A thin progress bar (a download on its row, in the library): the accent over the hairline, 4 dp. */
@Composable
fun ThinBar(fraction: Float, modifier: Modifier = Modifier) {
    Bar(fraction, DemoSize.track, modifier)
}

/** How much of the phone's storage is used, as a 6 dp bar ([used] of [total] bytes). */
@Composable
fun StorageBar(used: Long, total: Long, modifier: Modifier = Modifier) {
    Bar(if (total > 0) used.toFloat() / total else 0f, DemoSize.trackThick, modifier)
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
