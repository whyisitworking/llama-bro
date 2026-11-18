package com.suhel.llamabro.demo.designsystem.components

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.suhel.llamabro.demo.designsystem.R
import com.suhel.llamabro.demo.designsystem.theme.DemoMotion
import com.suhel.llamabro.demo.designsystem.theme.DemoShapes
import com.suhel.llamabro.demo.designsystem.theme.DemoSize
import com.suhel.llamabro.demo.designsystem.theme.DemoSpacing
import com.suhel.llamabro.demo.designsystem.theme.DemoType
import com.suhel.llamabro.demo.designsystem.theme.LocalDemoColors

/**
 * A single-line field on the surface with the soft shadow: a 56 dp pill (Material's search bar) with a 24 dp
 * [icon] at its start, 16 dp from the edge and 16 dp from the text. A search ("Search any model", "Search chats")
 * keeps the magnifier; a field for something else names its own icon (the token's key).
 */
@Composable
fun SearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    onSearch: (() -> Unit)? = null,
    icon: ImageVector = DemoIcons.Search,
    secret: Boolean = false,
) {
    val colors = LocalDemoColors.current
    val shape = CircleShape
    Row(
        modifier
            .softShadow(shape, colors)
            .clip(shape)
            .background(colors.surface)
            .heightIn(min = DemoSize.field)
            .padding(horizontal = DemoSpacing.item),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DemoSpacing.item),
    ) {
        Icon(icon, contentDescription = null, tint = colors.muted, modifier = Modifier.size(DemoSize.icon))
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.weight(1f),
            singleLine = true,
            textStyle = DemoType.body.copy(color = colors.ink),
            cursorBrush = SolidColor(colors.accent),
            // a secret (the token) is masked, never suggested or corrected and ends with Done; a search with Search
            visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions =
                if (secret) {
                    KeyboardOptions(
                        autoCorrectEnabled = false,
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done,
                    )
                } else {
                    KeyboardOptions(imeAction = ImeAction.Search)
                },
            keyboardActions = KeyboardActions(onSearch = { onSearch?.invoke() }),
            decorationBox = { field ->
                Box {
                    if (value.isEmpty()) Text(placeholder, color = colors.muted, style = DemoType.body, maxLines = 1)
                    field()
                }
            },
        )
    }
}

/** A multi-line text box for longer writing (custom instructions), on the surface with the soft shadow. */
@Composable
fun TextArea(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    minLines: Int = 4,
) {
    val colors = LocalDemoColors.current
    val shape = DemoShapes.card
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        minLines = minLines,
        textStyle = DemoType.body.copy(color = colors.ink),
        cursorBrush = SolidColor(colors.accent),
        decorationBox = { field ->
            Box(
                Modifier
                    .softShadow(shape, colors)
                    .clip(shape)
                    .background(colors.surface)
                    .padding(DemoSpacing.item),
            ) {
                if (value.isEmpty()) Text(placeholder, color = colors.muted, style = DemoType.body)
                field()
            }
        },
    )
}

/** How hard the model thinks before it answers (demo design §4.4). */
enum class ThinkLevel { Off, Quick, Balanced, Deep }

/**
 * What the message bar shows. [think] is null when the model can't think (no Think bulb); [canAttach] shows the
 * **+**; [busy] turns send into stop; [hasAttachment] (a photo is attached) lets send act without text. Send is
 * quiet and does nothing while there is nothing to send, the way the common chat bars do. [canRecord] (a model that
 * hears, D76) shows the mic while the text is empty: in send's place, or beside it when photos alone could be sent.
 * [recording], while a voice message is recorded or waits, takes the text's place.
 */
@Immutable
data class ComposerState(
    val text: String,
    val canAttach: Boolean,
    val think: ThinkLevel?,
    val busy: Boolean,
    val hasAttachment: Boolean = false,
    val canRecord: Boolean = false,
    val recording: RecordingState? = null,
    val canTalk: Boolean = false,
) {
    /** Send can act: there is something to send (text or a photo), or a reply is coming and the button is stop. */
    val canAct: Boolean get() = busy || text.isNotBlank() || hasAttachment

    /** The mic shows: the model hears, nothing is typed, no reply is coming and nothing is being recorded. */
    val showsMic: Boolean get() = canRecord && recording == null && !busy && text.isEmpty()

    /**
     * The voice-mode button shows in send's place (D100): the model speaks, and there is nothing to send, no reply is
     * coming and nothing is being recorded.
     */
    val showsTalk: Boolean get() = canTalk && recording == null && !busy && text.isEmpty() && !hasAttachment
}

/** What the message bar's voice controls do (D76): start recording, throw the recording away, or send it. */
@Immutable
data class VoiceActions(
    val onRecord: () -> Unit,
    val onDiscard: () -> Unit,
    val onSend: () -> Unit,
    val onTalk: () -> Unit = {},
) {
    companion object {
        val None = VoiceActions({}, {}, {})
    }
}

/**
 * The message bar: the text on top, a row of buttons under it (the layout of the leading chat apps, whose composers
 * grow into a text area over a tool row). One outlined surface with a 28 dp corner (Material's extra large). The text
 * starts 16 dp from the edge, 16 dp below the top, and grows to six lines; it starts each sentence with a capital, as a
 * messaging app's field does. 14 dp under the text (8 dp of padding and the 6 dp the 36 dp circle leaves in its
 * target) is the row of buttons: **+** at the start and,
 * at the end, a **Think** icon (when the model can think) just left of send (stop while a reply is coming). All
 * three are 36 dp circles with 20 dp glyphs in 48 dp targets, 10 dp from the bar's edge and 12 dp from each other, so
 * they sit concentric with the corner (18 + 10 = 28). Think is a lightbulb: a quiet circle when off, filled with the
 * send accent when on (its level is in its description and in the menu it opens); send is quiet while there is nothing
 * to send and filled with the send accent when it can act (D73).
 * The bar is as wide as its parent: a screen puts it on the gutter.
 *
 * [attachments], when there are any, sit inside the bar above the text: the bar grows to hold them, so a picture reads
 * as part of the message being written and not as something already sent. They start 16 dp from the bar's top and
 * sides (a 12 dp-cornered thumbnail there is concentric with the bar's 28 dp corner) and scroll sideways when many.
 */
@Composable
fun Composer(
    state: ComposerState,
    placeholder: String,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onAttach: () -> Unit,
    onThink: () -> Unit,
    modifier: Modifier = Modifier,
    attachments: (@Composable () -> Unit)? = null,
    voice: VoiceActions = VoiceActions.None,
) {
    val colors = LocalDemoColors.current
    val shape = DemoShapes.composer
    val recording = state.recording
    Column(
        modifier
            .fillMaxWidth()
            .softShadow(shape, colors, elevation = DemoSize.elevationRaised, lightAlpha = 0.08f)
            .clip(shape)
            .background(colors.surface)
            .border(DemoSize.hairline, colors.hairline, shape)
            .animateContentSize(tween(DemoMotion.STANDARD_MILLIS, easing = DemoMotion.standard)),
    ) {
        if (attachments != null) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(start = DemoSpacing.item, end = DemoSpacing.item, top = DemoSpacing.item),
            ) { attachments() }
        }
        val textPadding =
            Modifier
                .fillMaxWidth()
                .padding(
                    start = DemoSpacing.item,
                    end = DemoSpacing.item,
                    top = if (attachments != null) DemoSpacing.block else DemoSpacing.item,
                    // 8 dp here and the 6 dp a 36 dp circle leaves in its 48 dp target: the buttons sit 14 dp
                    // under the text, and the bar keeps the height it had with 40 dp circles
                    bottom = DemoSpacing.block,
                )
        if (recording != null) {
            RecordingLine(recording, textPadding)
        } else {
            MessageField(state, placeholder, onTextChange, textPadding)
        }
        // 4 dp round the 48 dp targets: a 36 dp circle is 10 dp from the bar's bottom, start and end
        Row(
            Modifier.fillMaxWidth().padding(start = IconButtonInset, end = IconButtonInset, bottom = IconButtonInset),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (recording != null) {
                BarButton(DemoIcons.Close, stringResource(R.string.ds_discard_recording), voice.onDiscard)
            } else if (state.canAttach) {
                BarButton(DemoIcons.Plus, stringResource(R.string.ds_attach), onAttach)
            }
            Spacer(Modifier.weight(1f))
            if (state.think != null) {
                val on = state.think != ThinkLevel.Off
                BarIconButton(onClick = onThink) {
                    BarCircle(if (on) colors.sendAccent else colors.hairline) {
                        Icon(
                            DemoIcons.Think,
                            contentDescription = thinkLabel(state.think),
                            tint = if (on) colors.onSendAccent else colors.ink,
                            modifier = Modifier.size(DemoSize.barGlyph),
                        )
                    }
                }
            }
            BarEnd(state, voice, onSend, onStop)
        }
    }
}

/** The bar's end: send the recording, the voice-mode button (D100), the mic alone (D76), or the mic and send. */
@Composable
private fun BarEnd(state: ComposerState, voice: VoiceActions, onSend: () -> Unit, onStop: () -> Unit) {
    val colors = LocalDemoColors.current
    val recording = state.recording
    when {
        recording != null -> {
            BarIconButton(onClick = voice.onSend) {
                BarCircle(colors.sendAccent) {
                    Icon(
                        DemoIcons.Send,
                        contentDescription = stringResource(R.string.ds_send_recording),
                        tint = colors.onSendAccent,
                        modifier = Modifier.size(DemoSize.barGlyph),
                    )
                }
            }
        }

        state.showsTalk -> {
            // a model that talks: the voice-message mic just before the voice-mode button (D100)
            if (state.showsMic) BarButton(DemoIcons.Mic, stringResource(R.string.ds_record), voice.onRecord)
            BarIconButton(onClick = voice.onTalk) {
                BarCircle(colors.sendAccent) {
                    Icon(
                        DemoIcons.VoiceMode,
                        contentDescription = stringResource(R.string.ds_voice_mode),
                        tint = colors.onSendAccent,
                        modifier = Modifier.size(DemoSize.barGlyph),
                    )
                }
            }
        }

        state.showsMic && !state.hasAttachment -> {
            BarButton(DemoIcons.Mic, stringResource(R.string.ds_record), voice.onRecord)
        }

        else -> {
            if (state.showsMic) BarButton(DemoIcons.Mic, stringResource(R.string.ds_record), voice.onRecord)
            SendButton(state, onSend, onStop)
        }
    }
}

/** The bar's text: body type, sentences capitalised, up to six lines, the [placeholder] while empty. */
@Composable
private fun MessageField(
    state: ComposerState,
    placeholder: String,
    onTextChange: (String) -> Unit,
    modifier: Modifier,
) {
    val colors = LocalDemoColors.current
    BasicTextField(
        value = state.text,
        onValueChange = onTextChange,
        modifier = modifier,
        textStyle = DemoType.body.copy(color = colors.ink),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        cursorBrush = SolidColor(colors.accent),
        maxLines = 6,
        decorationBox = { field ->
            Box {
                if (state.text.isEmpty()) {
                    Text(placeholder, color = colors.muted, style = DemoType.body, maxLines = 1)
                }
                field()
            }
        },
    )
}

/** Send, which fills with the send accent when it can act and cross-fades into stop while a reply is coming (D73). */
@Composable
private fun SendButton(state: ComposerState, onSend: () -> Unit, onStop: () -> Unit) {
    val colors = LocalDemoColors.current
    val busy = state.busy
    BarIconButton(onClick = if (busy) onStop else onSend, enabled = state.canAct) {
        // filled with the send accent when it can act; the hairline's tonal circle with muted ink when there
        // is nothing to send. The fill eases between the two, and send cross-fades into stop, so it changes
        // in place
        val fill by animateColorAsState(
            if (state.canAct) colors.sendAccent else colors.hairline,
            tween(DemoMotion.STANDARD_MILLIS, easing = DemoMotion.standard),
            label = "send fill",
        )
        val tint by animateColorAsState(
            if (state.canAct) colors.onSendAccent else colors.muted,
            tween(DemoMotion.STANDARD_MILLIS, easing = DemoMotion.standard),
            label = "send tint",
        )
        BarCircle(fill) {
            Crossfade(busy, animationSpec = tween(DemoMotion.SWAP_MILLIS), label = "send or stop") { stop ->
                Icon(
                    if (stop) DemoIcons.Stop else DemoIcons.Send,
                    contentDescription = stringResource(if (stop) R.string.ds_stop else R.string.ds_send),
                    tint = tint,
                    modifier = Modifier.size(DemoSize.barGlyph),
                )
            }
        }
    }
}

/** A tonal button of the bar (+, the mic, discard): an ink glyph on the hairline's circle. */
@Composable
private fun BarButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    val colors = LocalDemoColors.current
    BarIconButton(onClick = onClick) {
        BarCircle(colors.hairline) {
            Icon(icon, contentDescription = label, tint = colors.ink, modifier = Modifier.size(DemoSize.barGlyph))
        }
    }
}

/** A 36 dp circle of the bar's button row, filled with [fill]. */
@Composable
private fun BarCircle(fill: androidx.compose.ui.graphics.Color, content: @Composable () -> Unit) {
    Box(
        Modifier.size(DemoSize.barCircle).background(fill, CircleShape),
        contentAlignment = Alignment.Center,
    ) { content() }
}

/** The bar's edge beside a button: 4 dp, so the 36 dp circle in its 48 dp target is 10 dp from the edge. */
private val IconButtonInset = DemoSpacing.inline

/**
 * An icon button of the bar: its 36 dp circle centered in a 48 dp touch target, the way Material's `IconButton` is,
 * with the press clipped to the circle so no larger ring shows. It sits in the button row under the text.
 */
@Composable
private fun BarIconButton(onClick: () -> Unit, enabled: Boolean = true, content: @Composable () -> Unit) {
    Box(
        Modifier
            .minimumInteractiveComponentSize()
            .size(DemoSize.barCircle)
            .clip(CircleShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { content() }
}

@Composable
private fun thinkLabel(level: ThinkLevel): String =
    when (level) {
        ThinkLevel.Off -> stringResource(R.string.ds_think)
        ThinkLevel.Quick -> stringResource(R.string.ds_think_with_level, stringResource(R.string.ds_think_quick))
        ThinkLevel.Balanced -> stringResource(R.string.ds_think_with_level, stringResource(R.string.ds_think_balanced))
        ThinkLevel.Deep -> stringResource(R.string.ds_think_with_level, stringResource(R.string.ds_think_deep))
    }
