package com.suhel.llamabro.demo.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.suhel.llamabro.demo.designsystem.R
import com.suhel.llamabro.demo.designsystem.theme.DemoShapes
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
            .heightIn(min = 56.dp)
            .padding(horizontal = DemoSpacing.item),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DemoSpacing.item),
    ) {
        Icon(icon, contentDescription = null, tint = colors.muted, modifier = Modifier.size(24.dp))
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
 * quiet and does nothing while there is nothing to send, the way the common chat bars do.
 */
@Immutable
data class ComposerState(
    val text: String,
    val canAttach: Boolean,
    val think: ThinkLevel?,
    val busy: Boolean,
    val hasAttachment: Boolean = false,
) {
    /** Send can act: there is something to send (text or a photo), or a reply is coming and the button is stop. */
    val canAct: Boolean get() = busy || text.isNotBlank() || hasAttachment
}

/**
 * The message bar: the text on top, a row of buttons under it (the layout of the leading chat apps, whose composers
 * grow into a text area over a tool row). One outlined surface with a 28 dp corner (Material's extra large). The text
 * starts 16 dp from the edge, 16 dp below the top, and grows to six lines. The row under it has **+** at the start
 * and, at the end, a **Think** icon (when the model can think) just left of send (stop while a reply is coming). All
 * three are 40 dp circles in 48 dp targets, 8 dp from the bar's edge and 8 dp from each other, so they sit
 * concentric with the corner (20 + 8 = 28). Think is a lightbulb: a quiet circle when off, the soft accent when
 * on (its level is in its description and in the menu it opens); send is quiet while there is nothing to send.
 * The bar is as wide as its parent: a screen puts it on the gutter.
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
) {
    val colors = LocalDemoColors.current
    val shape = DemoShapes.composer
    Column(
        modifier
            .fillMaxWidth()
            .softShadow(shape, colors, elevation = 6.dp, lightAlpha = 0.08f)
            .clip(shape)
            .background(colors.surface)
            .border(1.dp, colors.hairline, shape),
    ) {
        BasicTextField(
            value = state.text,
            onValueChange = onTextChange,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(
                        start = DemoSpacing.item,
                        end = DemoSpacing.item,
                        top = DemoSpacing.item,
                        bottom = DemoSpacing.inline,
                    ),
            textStyle = DemoType.body.copy(color = colors.ink),
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
        // 4 dp round the 48 dp targets: a 40 dp circle is 8 dp from the bar's bottom, start and end
        Row(
            Modifier.fillMaxWidth().padding(start = IconButtonInset, end = IconButtonInset, bottom = IconButtonInset),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (state.canAttach) {
                BarIconButton(onClick = onAttach) {
                    BarCircle(colors.hairline) {
                        Icon(
                            DemoIcons.Plus,
                            contentDescription = stringResource(R.string.ds_attach),
                            tint = colors.ink,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            if (state.think != null) {
                val on = state.think != ThinkLevel.Off
                BarIconButton(onClick = onThink) {
                    BarCircle(if (on) colors.accentSoft else colors.hairline) {
                        Icon(
                            DemoIcons.Think,
                            contentDescription = thinkLabel(state.think),
                            tint = if (on) colors.accent else colors.ink,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                }
            }
            val busy = state.busy
            BarIconButton(onClick = if (busy) onStop else onSend, enabled = state.canAct) {
                // filled navy when it can act; the hairline's tonal circle with muted ink when there is nothing to send
                BarCircle(if (state.canAct) colors.accent else colors.hairline) {
                    Icon(
                        if (busy) DemoIcons.Stop else DemoIcons.Send,
                        contentDescription = stringResource(if (busy) R.string.ds_stop else R.string.ds_send),
                        tint = if (state.canAct) colors.canvas else colors.muted,
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
        }
    }
}

/** A 40 dp circle of the bar's button row, filled with [fill]. */
@Composable
private fun BarCircle(fill: androidx.compose.ui.graphics.Color, content: @Composable () -> Unit) {
    Box(Modifier.size(40.dp).background(fill, CircleShape), contentAlignment = Alignment.Center) { content() }
}

/** The bar's edge beside a button: 4 dp, so the 40 dp circle in its 48 dp target is 8 dp from the edge. */
private val IconButtonInset = 4.dp

/**
 * An icon button of the bar: its content (a 24 dp glyph, or a 40 dp circle) centered in a 48 dp touch target, the
 * way Material's `IconButton` is, with the press clipped to a 40 dp circle so no larger ring shows. In a tall bar it
 * sits in the button row under the text.
 */
@Composable
private fun BarIconButton(onClick: () -> Unit, enabled: Boolean = true, content: @Composable () -> Unit) {
    Box(
        Modifier
            .minimumInteractiveComponentSize()
            .size(40.dp)
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
