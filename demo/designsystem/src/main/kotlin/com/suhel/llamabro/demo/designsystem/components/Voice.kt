package com.suhel.llamabro.demo.designsystem.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.LayoutDirection
import com.suhel.llamabro.demo.designsystem.R
import com.suhel.llamabro.demo.designsystem.theme.DemoSize
import com.suhel.llamabro.demo.designsystem.theme.DemoSpacing
import com.suhel.llamabro.demo.designsystem.theme.DemoType
import com.suhel.llamabro.demo.designsystem.theme.LocalDemoColors
import com.suhel.llamabro.demo.designsystem.theme.animationsDisabled

/**
 * A voice message being recorded, or one whose recording stopped and waits to be sent or discarded (D76): the input
 * [levels] so far, 0 to 1, oldest first; the [elapsed] time as shown ("0:42"); [live] while the microphone is
 * listening; [nearEnd] in the last seconds before the length limit, when the time turns ink.
 */
@Immutable
data class RecordingState(
    val levels: List<Float>,
    val elapsed: String,
    val live: Boolean,
    val nearEnd: Boolean = false,
)

/**
 * Dictation in the message bar (D140): the [text] heard so far (it streams in, or comes a piece at each pause), the
 * input [levels] and the [elapsed] time. [preparing] while the model gets ready and nothing is heard yet; [listening]
 * while the microphone is on, false once stopped while the last text comes in.
 */
@Immutable
data class DictationBar(
    val text: String,
    val levels: List<Float>,
    val elapsed: String,
    val listening: Boolean,
    val preparing: Boolean = false,
)

/**
 * What replaces the message bar's text while dictating (D140): the words so far in body type (up to six lines, as the
 * field), and under them a strip of the input levels with the time. Before any word, a muted line says what is
 * happening. It announces itself once when it starts; the words are read when they settle, not while they stream.
 */
@Composable
internal fun DictationLine(state: DictationBar, modifier: Modifier = Modifier) {
    val colors = LocalDemoColors.current
    val still = animationsDisabled()
    val status =
        stringResource(
            when {
                state.preparing -> R.string.ds_dictation_preparing
                state.listening -> R.string.ds_dictating
                else -> R.string.ds_dictation_finishing
            },
        )
    Column(modifier, verticalArrangement = Arrangement.spacedBy(DemoSpacing.inline)) {
        if (state.text.isBlank()) {
            Text(
                status,
                color = colors.muted,
                style = DemoType.body,
                maxLines = 1,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        } else {
            Text(state.text, color = colors.ink, style = DemoType.body, maxLines = DICTATION_LINES)
        }
        Row(
            Modifier.clearAndSetSemantics { contentDescription = "$status, ${state.elapsed}" },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(DemoSpacing.item),
        ) {
            val levels = if (state.listening && still) emptyList() else state.levels
            Canvas(Modifier.weight(1f).height(DemoSize.levelHeight)) {
                drawLevels(
                    levels,
                    ink = if (state.listening) colors.ink else colors.muted,
                    played = colors.ink,
                    fit = false,
                )
            }
            Text(state.elapsed, color = colors.muted, style = DemoType.label, maxLines = 1)
        }
    }
}

/** As many lines as the message field shows. */
private const val DICTATION_LINES = 6

/**
 * A voice message in the chat (D76): its recorded [levels] (0 to 1, any count, drawn across a fixed width), its
 * [length] ("0:42"), whether it is [playing] and how far, [progress] 0 to 1, the played part drawn in ink.
 */
@Immutable
data class VoiceClip(
    val levels: List<Float>,
    val length: String,
    val playing: Boolean = false,
    val progress: Float = 0f,
)

/**
 * What replaces the message bar's text while a voice message is recorded: the levels, newest at the end, and the time.
 * One line of body text tall, so the bar keeps its height. The line announces when recording starts and stops; with
 * animations off the bars hold still.
 */
@Composable
internal fun RecordingLine(state: RecordingState, modifier: Modifier = Modifier) {
    val colors = LocalDemoColors.current
    val still = animationsDisabled()
    val announce = stringResource(if (state.live) R.string.ds_recording else R.string.ds_recording_stopped)
    Row(
        modifier.semantics {
            contentDescription = announce
            liveRegion = LiveRegionMode.Polite
        },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DemoSpacing.item),
    ) {
        val levels = if (state.live && still) emptyList() else state.levels
        Canvas(Modifier.weight(1f).height(DemoSize.levelHeight)) {
            drawLevels(levels, ink = if (state.live) colors.ink else colors.muted, played = colors.ink, fit = false)
        }
        Text(
            state.elapsed,
            color = if (state.nearEnd) colors.ink else colors.muted,
            style = DemoType.label,
            maxLines = 1,
        )
    }
}

/**
 * A sent voice message (D76): a play or pause circle filled with the accent, the clip's levels with the played
 * part in ink, and its length, with the [time] after it when the layout shows times. Read out as one item, "Voice
 * message, 0:42"; the circle is its own button.
 */
@Composable
fun VoiceBubble(clip: VoiceClip, onToggle: () -> Unit, modifier: Modifier = Modifier, time: String? = null) {
    val colors = LocalDemoColors.current
    val description = stringResource(R.string.ds_voice_message, clip.length)
    Row(
        modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DemoSpacing.compact),
    ) {
        Box(
            Modifier
                .minimumInteractiveComponentSize()
                .size(DemoSize.barCircle)
                .clip(CircleShape)
                .background(colors.accent)
                .clickable(role = Role.Button, onClick = onToggle),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (clip.playing) DemoIcons.Pause else DemoIcons.Play,
                contentDescription = stringResource(if (clip.playing) R.string.ds_pause else R.string.ds_play),
                tint = colors.onAccent,
                modifier = Modifier.size(DemoSize.barGlyph),
            )
        }
        Row(
            Modifier.clearAndSetSemantics { contentDescription = description },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(DemoSpacing.compact),
        ) {
            Canvas(Modifier.width(DemoSize.voiceWaveWidth).height(DemoSize.levelHeight)) {
                drawLevels(clip.levels, ink = colors.muted, played = colors.ink, fit = true, progress = clip.progress)
            }
            Text(
                if (time != null) "${clip.length} · $time" else clip.length,
                color = colors.muted,
                style = DemoType.bodySmall,
                maxLines = 1,
            )
        }
    }
}

/**
 * Bars for [levels], centred on the line, at least as tall as they are wide so silence still reads as a row of dots.
 * [fit] spreads the levels over every bar that fits (a whole clip); otherwise the newest levels fill the line from its
 * end (a live recording). Bars before [progress] take [played], the rest [ink]. Time runs in the reading direction.
 */
private fun DrawScope.drawLevels(
    levels: List<Float>,
    ink: Color,
    played: Color,
    fit: Boolean,
    progress: Float = 1f,
) {
    val bar = DemoSize.levelBar.toPx()
    val step = bar + DemoSize.levelGap.toPx()
    val slots = ((size.width + DemoSize.levelGap.toPx()) / step).toInt().coerceAtLeast(1)
    val shown =
        if (fit) {
            List(slots) { i -> levels.peak(i * levels.size / slots, (i + 1) * levels.size / slots) }
        } else {
            List(slots) { 0f } + levels.takeLast(slots)
        }.takeLast(slots)
    val rtl = layoutDirection == LayoutDirection.Rtl
    shown.forEachIndexed { i, level ->
        val height = (size.height * level.coerceIn(0f, 1f)).coerceAtLeast(bar)
        val x = if (rtl) size.width - (i + 1) * step + DemoSize.levelGap.toPx() else i * step
        drawRoundRect(
            color = if ((i + 0.5f) / slots <= progress) played else ink,
            topLeft = Offset(x, (size.height - height) / 2),
            size = Size(bar, height),
            cornerRadius = CornerRadius(bar / 2),
        )
    }
}

/** The loudest level in [from] until [to] (at least one level when there are any), 0 for none. */
private fun List<Float>.peak(from: Int, to: Int): Float =
    if (isEmpty()) {
        0f
    } else {
        val start = from.coerceIn(0, size - 1)
        subList(start, to.coerceIn(start + 1, size)).max()
    }
