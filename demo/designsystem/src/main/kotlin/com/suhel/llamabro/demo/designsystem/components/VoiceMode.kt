package com.suhel.llamabro.demo.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import com.suhel.llamabro.demo.designsystem.R
import com.suhel.llamabro.demo.designsystem.theme.DemoSize
import com.suhel.llamabro.demo.designsystem.theme.DemoSpacing
import com.suhel.llamabro.demo.designsystem.theme.DemoType
import com.suhel.llamabro.demo.designsystem.theme.LocalDemoColors
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter

/** Where a voice conversation is (D100): waiting for the person, hearing them, before the reply, or speaking it. */
enum class VoicePhase { Ready, Listening, Thinking, Speaking }

/**
 * What the voice view shows (D100): the [model]'s name and its [voice] (null when the model has none to name), the
 * [phase], the voice's [level] (0 to 1: the microphone's while listening, the reply's while speaking), the person's
 * last words when captioned ([userWords]), and the reply's [caption] so far. A [note] (that voice mode starts fresh,
 * without the chat's earlier messages) shows quietly above the words.
 */
@Immutable
data class VoiceViewState(
    val model: String,
    val voice: String?,
    val phase: VoicePhase,
    val level: Float = 0f,
    val userWords: String? = null,
    val caption: String = "",
    val note: String? = null,
)

/** What the voice view's controls do: close it, start and end a turn (hold to talk), and stop the reply. */
@Immutable
data class VoiceViewActions(
    val onClose: () -> Unit,
    val onTalkStart: () -> Unit,
    val onTalkEnd: () -> Unit,
    val onStop: () -> Unit,
)

/**
 * Voice mode's full-screen view (demo design §4.8, D100). A top bar with close at the start and the model and its
 * voice centred; the [VoiceOrb] in the upper middle with one muted state line under it; the person's last words and
 * the reply's caption under that, the caption's older sentences muted and fading at the top; and at the bottom the
 * hold-to-talk button, centred, with Stop at its start while a reply is coming, and a hint under them. A [notice] (a
 * limit line: the model didn't load, the microphone was refused) sits on the gutter above the controls.
 */
@Composable
fun VoiceView(
    state: VoiceViewState,
    actions: VoiceViewActions,
    modifier: Modifier = Modifier,
    notice: (@Composable () -> Unit)? = null,
) {
    val colors = LocalDemoColors.current
    Column(
        modifier
            .fillMaxSize()
            .background(colors.canvas)
            .statusBarsPadding()
            .navigationBarsPadding(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        VoiceTopBar(state.model, state.voice, actions.onClose)
        Spacer(Modifier.weight(1f))
        VoiceOrb(state.phase, state.level)
        Spacer(Modifier.height(DemoSpacing.hero))
        Text(
            stringResource(state.phase.line()),
            Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            color = colors.muted,
            style = DemoType.label,
        )
        Spacer(Modifier.height(DemoSpacing.hero))
        VoiceWords(
            state.note,
            state.userWords,
            state.caption,
            Modifier
                .weight(2f)
                .widthIn(max = DemoSize.contentMaxWidth)
                .fillMaxWidth()
                .padding(horizontal = DemoSpacing.section),
        )
        if (notice != null) {
            Box(
                Modifier
                    .widthIn(max = DemoSize.contentMaxWidth)
                    .fillMaxWidth()
                    .padding(horizontal = DemoSpacing.gutter, vertical = DemoSpacing.block),
            ) { notice() }
        }
        // a tap starts a turn that the next tap sends; a hold sends on release (D100): the button keeps which one
        var tapped by rememberTurnTapped(state.phase)
        VoiceControls(state.phase, tapped, { tapped = it }, actions)
        Text(
            stringResource(hint(state.phase, tapped)),
            Modifier.padding(top = DemoSpacing.block, bottom = DemoSpacing.section),
            color = colors.muted,
            style = DemoType.bodySmall,
        )
    }
}

@Composable
private fun VoiceTopBar(model: String, voice: String?, onClose: () -> Unit) {
    val colors = LocalDemoColors.current
    Box(Modifier.fillMaxWidth().height(DemoSize.topBar).padding(horizontal = DemoSpacing.inline)) {
        IconAction(
            DemoIcons.Close,
            stringResource(R.string.ds_voice_close),
            onClose,
            Modifier.align(Alignment.CenterStart),
        )
        Column(
            Modifier.align(Alignment.Center).padding(horizontal = DemoSize.target),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(model, color = colors.ink, style = DemoType.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (voice != null) {
                Text(
                    stringResource(R.string.ds_voice_name, voice),
                    color = colors.muted,
                    style = DemoType.bodySmall,
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * The person's last words in muted [DemoType.bodyMedium], then the reply's caption in [DemoType.voiceCaption]: its
 * last sentence in ink, the ones before it muted. The newest text stays in view, and the oldest fades out at the top.
 */
@Composable
private fun VoiceWords(note: String?, userWords: String?, caption: String, modifier: Modifier = Modifier) {
    val colors = LocalDemoColors.current
    val scroll = rememberScrollState()
    LaunchedEffect(caption, userWords) { scroll.scrollTo(scroll.maxValue) }
    val fade = DemoSize.captionFade
    Column(
        modifier
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                if (scroll.value > 0) {
                    // a DstIn mask: only its alpha counts, its colour is never seen
                    @Suppress("DemoRawColour")
                    drawRect(
                        Brush.verticalGradient(listOf(Color.Transparent, Color.Black), startY = 0f, endY = fade.toPx()),
                        blendMode = BlendMode.DstIn,
                    )
                }
            }.verticalScroll(scroll),
        verticalArrangement = Arrangement.spacedBy(DemoSpacing.item),
    ) {
        if (note != null) {
            Text(
                note,
                Modifier.fillMaxWidth(),
                color = colors.muted,
                style = DemoType.bodySmall,
                textAlign = TextAlign.Center,
            )
        }
        if (userWords != null) {
            Text(userWords, color = colors.muted, style = DemoType.bodyMedium)
        }
        if (caption.isNotEmpty()) {
            val split = lastSentenceStart(caption)
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(color = colors.muted)) { append(caption.substring(0, split)) }
                    append(caption.substring(split))
                },
                color = colors.ink,
                style = DemoType.voiceCaption,
            )
        }
    }
}

/**
 * Whether the turn was started by a tap ([TalkButton]): set by the button, and forgotten as the turn leaves
 * [VoicePhase.Listening] (sent, stopped or failed), so the next turn starts as a hold again.
 */
@Composable
internal fun rememberTurnTapped(phase: VoicePhase): MutableState<Boolean> {
    val tapped = remember { mutableStateOf(false) }
    val listening by rememberUpdatedState(phase == VoicePhase.Listening)
    LaunchedEffect(Unit) {
        snapshotFlow { listening }
            .drop(1)
            .filter { !it }
            .collect { tapped.value = false }
    }
    return tapped
}

/** The line under the controls: how to talk now. */
private fun hint(phase: VoicePhase, tapped: Boolean): Int =
    when (phase) {
        VoicePhase.Listening -> if (tapped) R.string.ds_voice_tap_send else R.string.ds_voice_release
        VoicePhase.Speaking -> R.string.ds_voice_interrupt
        VoicePhase.Ready, VoicePhase.Thinking -> R.string.ds_voice_how
    }

@Composable
private fun VoiceControls(phase: VoicePhase, tapped: Boolean, onTapped: (Boolean) -> Unit, actions: VoiceViewActions) {
    val replying = phase == VoicePhase.Thinking || phase == VoicePhase.Speaking
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            if (replying) StopButton(actions.onStop)
        }
        TalkButton(
            held = phase == VoicePhase.Listening,
            tapped = tapped,
            onTapped = onTapped,
            onStart = actions.onTalkStart,
            onEnd = actions.onTalkEnd,
        )
        Spacer(Modifier.weight(1f))
    }
}

/**
 * The talk button (D100): an 80 dp tonal circle with the mic, filled with the accent while [held] (listening),
 * with a soft halo of it. Two ways to talk: hold it and release to send, or tap it to start and tap again to send. A
 * press shorter than [TAP_MILLIS] is a tap: the turn goes on ([tapped], kept by the caller through [onTapped]) until
 * the next press ends it. A screen reader taps it once to start and once to send.
 */
@Composable
fun TalkButton(
    held: Boolean,
    tapped: Boolean,
    onTapped: (Boolean) -> Unit,
    onStart: () -> Unit,
    onEnd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalDemoColors.current
    val start by rememberUpdatedState(onStart)
    val end by rememberUpdatedState(onEnd)
    val isHeld by rememberUpdatedState(held)
    val isTapped by rememberUpdatedState(tapped)
    val setTapped by rememberUpdatedState(onTapped)
    val label = stringResource(if (held) R.string.ds_voice_talk_send else R.string.ds_voice_talk_start)
    val halo = colors.accent.copy(alpha = HALO_ALPHA)
    Box(
        modifier
            .padding(DemoSpacing.block)
            .size(DemoSize.talkButton)
            .drawBehind {
                if (held) drawCircle(halo, radius = size.minDimension / 2 + DemoSpacing.compact.toPx())
            }.clip(CircleShape)
            .background(if (held) colors.accent else colors.hairline)
            .semantics {
                role = Role.Button
                contentDescription = label
                onClick {
                    if (isHeld) end() else start()
                    true
                }
            }.pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    if (isTapped) {
                        // the tap that sends a tapped turn
                        waitForUpOrCancellation()?.consume()
                        setTapped(false)
                        end()
                        return@awaitEachGesture
                    }
                    start()
                    val up = waitForUpOrCancellation()
                    up?.consume()
                    // timed by the touch's own events: a short press is a tap, which leaves the turn listening
                    if (up != null && up.uptimeMillis - down.uptimeMillis < TAP_MILLIS) setTapped(true) else end()
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            DemoIcons.Mic,
            contentDescription = null,
            tint = if (held) colors.onAccent else colors.ink,
            modifier = Modifier.size(DemoSize.talkGlyph),
        )
    }
}

/** Stop the reply: a 36 dp tonal circle with the stop glyph, in a 48 dp target. */
@Composable
private fun StopButton(onStop: () -> Unit) {
    val colors = LocalDemoColors.current
    val label = stringResource(R.string.ds_voice_stop)
    Box(
        Modifier
            .size(DemoSize.target)
            .clip(CircleShape)
            .clickable(role = Role.Button, onClickLabel = null, onClick = onStop)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(DemoSize.barCircle).clip(CircleShape).background(colors.hairline),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                DemoIcons.Stop,
                contentDescription = null,
                tint = colors.ink,
                modifier = Modifier.size(DemoSize.barGlyph),
            )
        }
    }
}

private fun VoicePhase.line(): Int =
    when (this) {
        VoicePhase.Ready -> R.string.ds_voice_ready
        VoicePhase.Listening -> R.string.ds_voice_listening
        VoicePhase.Thinking -> R.string.ds_voice_thinking
        VoicePhase.Speaking -> R.string.ds_voice_speaking
    }

/** Where the caption's last sentence starts: after the last sentence end that has text after it, else 0. */
internal fun lastSentenceStart(text: String): Int {
    val trimmed = text.trimEnd()
    var i = trimmed.length - 1
    while (i > 0) {
        if (trimmed[i - 1] in SentenceEnds && trimmed[i].isWhitespace()) return i + 1
        i--
    }
    return 0
}

private val SentenceEnds = setOf('.', '!', '?', '…')

/** A press shorter than this is a tap: it starts a turn that the next tap sends. Material's long-press timeout. */
private const val TAP_MILLIS = 400L

/** The held talk button's halo over the canvas. */
private const val HALO_ALPHA = 0.16f

/**
 * [VoiceView] over the whole window, as voice mode opens over the chat (D100): back closes it. The window has no dim
 * of its own, its system bars draw over the view's canvas, and their icons follow the theme.
 */
@Composable
fun VoiceDialog(
    state: VoiceViewState,
    actions: VoiceViewActions,
    modifier: Modifier = Modifier,
    notice: (@Composable () -> Unit)? = null,
) {
    val dark = LocalDemoColors.current.isDark
    FullScreenDialog(onDismissRequest = actions.onClose, lightBars = !dark) {
        VoiceView(state, actions, modifier, notice)
    }
}
