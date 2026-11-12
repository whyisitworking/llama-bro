package com.suhel.llamabro.demo.designsystem.components

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.suhel.llamabro.demo.designsystem.R
import com.suhel.llamabro.demo.designsystem.theme.DemoMotion
import com.suhel.llamabro.demo.designsystem.theme.DemoShapes
import com.suhel.llamabro.demo.designsystem.theme.DemoSpacing
import com.suhel.llamabro.demo.designsystem.theme.DemoType
import com.suhel.llamabro.demo.designsystem.theme.LocalDemoColors
import com.suhel.llamabro.demo.designsystem.theme.subtleFill

/** Long-press for a message's actions (copy, edit, share, regenerate); a plain tap does nothing. */
@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.longPress(onLongClick: (() -> Unit)?): Modifier =
    // the touch area is at least 48 dp even when a short reply is not (the drawn size doesn't change)
    if (onLongClick == null) {
        this
    } else {
        minimumInteractiveComponentSize().combinedClickable(onClick = {}, onLongClick = onLongClick)
    }

/**
 * The user's message: an ink bubble on the right with canvas text, its tail corner bottom end, at most 80% of the
 * row; attached [images] above it; the [time] in its corner (the chat list layout only).
 */
@Composable
fun UserBubble(
    text: String,
    modifier: Modifier = Modifier,
    images: List<ImageBitmap> = emptyList(),
    time: String? = null,
    onLongClick: (() -> Unit)? = null,
) {
    val colors = LocalDemoColors.current
    Column(
        modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(DemoSpacing.inline),
    ) {
        for (image in images) {
            Image(
                image,
                contentDescription = null,
                modifier = Modifier.size(width = 168.dp, height = 126.dp).clip(DemoShapes.card),
                contentScale = ContentScale.Crop,
            )
        }
        if (text.isNotEmpty() || time != null) {
            Column(
                Modifier
                    .bubbleIn(end = true)
                    .clip(DemoShapes.userBubble)
                    .background(colors.bubble)
                    .longPress(onLongClick)
                    .padding(horizontal = DemoSpacing.item, vertical = 12.dp),
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(DemoSpacing.inline),
            ) {
                if (text.isNotEmpty()) Text(text, color = colors.ink, style = DemoType.body)
                if (time != null) Text(time, color = colors.muted, style = DemoType.bodySmall)
            }
        }
    }
}

/**
 * A reply in the chat list layout: a raised surface bubble on the left, its tail corner bottom start, at most 80%
 * of the row, with the [time] (and " · [speed]") in its corner.
 */
@Composable
fun ModelBubble(
    content: @Composable () -> Unit,
    time: String,
    modifier: Modifier = Modifier,
    speed: String? = null,
    onLongClick: (() -> Unit)? = null,
) {
    val colors = LocalDemoColors.current
    Box(modifier.fillMaxWidth()) {
        Column(
            Modifier
                .bubbleIn(end = false)
                .softShadow(DemoShapes.modelBubble, colors)
                .clip(DemoShapes.modelBubble)
                .background(colors.surface)
                .longPress(onLongClick)
                .padding(horizontal = DemoSpacing.item, vertical = 12.dp),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(DemoSpacing.inline),
        ) {
            Box(Modifier.align(Alignment.Start)) { content() }
            Text(if (speed != null) "$time · $speed" else time, color = colors.muted, style = DemoType.bodySmall)
        }
    }
}

/** A reply in the sidebar layout: written on the page, full width, with the [speed] line ("38 words/s") under it. */
@Composable
fun OpenReply(
    content: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    speed: String? = null,
    onLongClick: (() -> Unit)? = null,
) {
    val colors = LocalDemoColors.current
    Column(
        modifier.fillMaxWidth().longPress(onLongClick),
        verticalArrangement = Arrangement.spacedBy(DemoSpacing.inline),
    ) {
        content()
        if (speed != null) Text(speed, color = colors.muted, style = DemoType.bodySmall)
    }
}

/**
 * A reply's thinking: [seconds] is null while it thinks; [expanded] shows [text]. While thinking the live text can
 * be opened too.
 */
@Immutable
data class ThoughtState(val seconds: Int?, val expanded: Boolean, val text: String)

/**
 * "Thought for 4 s ›" folded above a reply ("Thinking ›" while it thinks); tapping opens the thoughts, muted and
 * italic.
 */
@Composable
fun ThoughtFold(state: ThoughtState, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    val colors = LocalDemoColors.current
    val label =
        if (state.seconds != null) {
            stringResource(R.string.ds_thought_for, state.seconds)
        } else {
            stringResource(R.string.ds_thinking)
        }
    Column(
        modifier.fillMaxWidth().animateContentSize(tween(DemoMotion.STANDARD_MILLIS, easing = DemoMotion.standard)),
    ) {
        Row(
            Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable(role = Role.Button, onClick = onToggle)
                .heightIn(min = 48.dp)
                .padding(end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, color = colors.muted, style = DemoType.bodyMedium)
            Icon(
                DemoIcons.ChevronRight,
                contentDescription = null,
                tint = colors.muted,
                modifier = Modifier.size(18.dp).graphicsLayer { rotationZ = if (state.expanded) 90f else 0f },
            )
        }
        if (state.expanded && state.text.isNotEmpty()) {
            Text(
                state.text,
                Modifier
                    .fillMaxWidth()
                    .background(colors.subtleFill, DemoShapes.card)
                    .padding(DemoSpacing.item),
                color = colors.muted,
                style = DemoType.thought,
            )
        }
    }
}

/** A quiet day label between messages ("Today"). */
@Composable
fun DayLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier.fillMaxWidth(),
        color = LocalDemoColors.current.muted,
        style = DemoType.bodySmall,
        textAlign = TextAlign.Center,
    )
}

/** The divider a mid-chat switch leaves ("Switched to Deep"): hairlines either side of a muted caption. */
@Composable
fun SwitchDivider(text: String, modifier: Modifier = Modifier) {
    val colors = LocalDemoColors.current
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f).heightIn(min = 1.dp, max = 1.dp).background(colors.hairline))
        Text(text, Modifier.padding(horizontal = DemoSpacing.block), color = colors.muted, style = DemoType.bodySmall)
        Box(Modifier.weight(1f).heightIn(min = 1.dp, max = 1.dp).background(colors.hairline))
    }
}

/** The line under a message held for a model that isn't ready ("Swift will answer the moment it's here"). */
@Composable
fun HeldNote(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier.fillMaxWidth(),
        color = LocalDemoColors.current.muted,
        style = DemoType.bodySmall,
        textAlign = TextAlign.End,
    )
}

/**
 * An honest limit, said once in one plain line with its fix (demo design §4.4): the [text] and, when there is one,
 * an [action] button.
 */
@Composable
fun LimitLine(
    text: String,
    action: String?,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
    secondAction: String? = null,
    onSecondAction: () -> Unit = {},
) {
    val colors = LocalDemoColors.current
    DemoCard(
        modifier = modifier.fillMaxWidth(),
        contentPadding =
            PaddingValues(
                start = DemoSpacing.item,
                end = DemoSpacing.block,
                top = DemoSpacing.block,
                bottom = DemoSpacing.block,
            ),
    ) {
        if (secondAction != null && action != null) {
            // two actions do not fit beside the line: the line, then the actions on the end of their own row
            Column {
                Text(
                    text,
                    Modifier.padding(top = DemoSpacing.block, end = DemoSpacing.block),
                    color = colors.ink,
                    style = DemoType.bodyMedium,
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    QuietButton(text = secondAction, onClick = onSecondAction)
                    Spacer(Modifier.width(DemoSpacing.block))
                    QuietButton(text = action, onClick = onAction)
                }
            }
        } else {
            Row(Modifier.heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(text, Modifier.weight(1f), color = colors.ink, style = DemoType.bodyMedium)
                if (action != null) {
                    Spacer(Modifier.width(DemoSpacing.block))
                    QuietButton(text = action, onClick = onAction)
                }
            }
        }
    }
}

/** Starter prompts of an empty chat ("Plan my week"), as soft chips; tapping one hands its text to [onPick]. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StarterChips(items: List<String>, onPick: (String) -> Unit, modifier: Modifier = Modifier) {
    val colors = LocalDemoColors.current
    FlowRow(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(DemoSpacing.block, Alignment.CenterHorizontally),
    ) {
        for (item in items) {
            Box(
                Modifier
                    .minimumInteractiveComponentSize()
                    .clickable(role = Role.Button, onClick = { onPick(item) }),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    item,
                    Modifier
                        .softShadow(DemoShapes.chip, colors)
                        .background(colors.surface, DemoShapes.chip)
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    color = colors.ink,
                    style = DemoType.label,
                )
            }
        }
    }
}

/** A picture attached to the message being written: 64 dp, with a ✕ at its top end to take it back. */
@Composable
fun Thumbnail(
    picture: ImageBitmap?,
    description: String,
    removeLabel: String,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier.size(64.dp).clip(DemoShapes.card)) {
        picture?.let {
            Image(
                it,
                contentDescription = description,
                Modifier.size(64.dp),
                contentScale = ContentScale.Crop,
            )
        }
        IconAction(DemoIcons.Close, removeLabel, onRemove, Modifier.align(Alignment.TopEnd))
    }
}

/**
 * The "back to the latest message" button of a chat scrolled away from its end: a 40 dp raised circle (in a 48 dp
 * target) with a down arrow, 8 dp above the bottom of the list. The list shows it only while there is more below.
 */
@Composable
fun JumpToLatest(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = LocalDemoColors.current
    Box(modifier.padding(bottom = DemoSpacing.block).minimumInteractiveComponentSize()) {
        Box(
            Modifier
                .size(40.dp)
                .softShadow(CircleShape, colors)
                .clip(CircleShape)
                .background(colors.surface)
                .border(1.dp, colors.hairline, CircleShape)
                .clickable(role = Role.Button, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                DemoIcons.ChevronDown,
                contentDescription = stringResource(R.string.ds_jump_to_latest),
                tint = colors.ink,
                modifier = Modifier.size(24.dp),
            )
        }
    }
}
