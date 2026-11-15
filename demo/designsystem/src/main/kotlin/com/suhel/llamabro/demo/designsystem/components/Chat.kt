package com.suhel.llamabro.demo.designsystem.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
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
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.suhel.llamabro.demo.designsystem.R
import com.suhel.llamabro.demo.designsystem.theme.DemoMotion
import com.suhel.llamabro.demo.designsystem.theme.DemoShapes
import com.suhel.llamabro.demo.designsystem.theme.DemoSize
import com.suhel.llamabro.demo.designsystem.theme.DemoSpacing
import com.suhel.llamabro.demo.designsystem.theme.DemoType
import com.suhel.llamabro.demo.designsystem.theme.LocalDemoColors
import com.suhel.llamabro.demo.designsystem.theme.animationsDisabled
import com.suhel.llamabro.demo.designsystem.theme.onPhotoScrim
import com.suhel.llamabro.demo.designsystem.theme.photoScrim
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
                modifier =
                    Modifier
                        .size(
                            width = DemoSize.messageImageWidth,
                            height = DemoSize.messageImageHeight,
                        ).clip(DemoShapes.card),
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
                    .padding(horizontal = DemoSpacing.item, vertical = DemoSpacing.compact),
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
                .padding(horizontal = DemoSpacing.item, vertical = DemoSpacing.compact),
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
        // a real 48 dp row, the label centred in it: a touch target that reaches past its row is cut by the parent's
        // bounds, and accessibility services (and the checks) see only what's left (F130). The row's height above and
        // below the line is the gap to what follows
        Box(
            Modifier
                .heightIn(min = DemoSize.target)
                .clip(DemoShapes.chip)
                .clickable(role = Role.Button, onClickLabel = label, onClick = onToggle)
                .semantics { contentDescription = label },
            contentAlignment = Alignment.CenterStart,
        ) {
            val turn by animateFloatAsState(
                if (state.expanded) QUARTER_TURN else 0f,
                tween(DemoMotion.STANDARD_MILLIS, easing = DemoMotion.standard),
                label = "chevron",
            )
            Row(Modifier.padding(end = DemoSpacing.inline), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    label,
                    if (state.seconds == null) Modifier.shimmer() else Modifier,
                    color = colors.muted,
                    style = DemoType.bodyMedium,
                )
                Icon(
                    DemoIcons.ChevronRight,
                    contentDescription = null,
                    tint = colors.muted,
                    modifier = Modifier.size(DemoSize.iconSmall).graphicsLayer { rotationZ = turn },
                )
            }
        }
        if (state.expanded && state.text.isNotEmpty()) {
            Text(
                state.text,
                Modifier
                    // the reply follows a block below the open thought
                    .padding(bottom = DemoSpacing.block)
                    .fillMaxWidth()
                    .background(colors.subtleFill, DemoShapes.card)
                    .padding(DemoSpacing.item),
                color = colors.muted,
                style = DemoType.thought,
            )
        }
    }
}

private const val QUARTER_TURN = 90f

/**
 * A soft band of ink sweeping across muted text, again and again: the text is still being made ("Thinking"). It draws
 * over the text only, on the same loop as the wake hairline, and holds still with animations off.
 */
@Composable
private fun Modifier.shimmer(): Modifier {
    if (animationsDisabled()) return this
    val ink = LocalDemoColors.current.ink
    val sweep = rememberLoop(DemoMotion.SWEEP_MILLIS)
    return graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            val band = size.width * SHIMMER_WIDTH_FRACTION
            val start = -band + sweep.value * (size.width + band)
            drawRect(
                Brush.horizontalGradient(listOf(Color.Transparent, ink, Color.Transparent), start, start + band),
                blendMode = BlendMode.SrcAtop,
            )
        }
}

/** The band is wider on a short word than the wake line's is on the screen. */
private const val SHIMMER_WIDTH_FRACTION = 0.6f

/**
 * The actions under a reply (copy, share, regenerate, details): small [IconAction]s whose glyphs line up with the
 * reply's text edge. Each glyph sits in the middle of its 48 dp target, so the row starts half the difference earlier,
 * mirrored in RTL.
 */
@Composable
fun ReplyActions(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Row(
        modifier.fillMaxWidth().offset(x = -(DemoSize.target - DemoSize.iconSmall) / 2),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
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
        Box(Modifier.weight(1f).height(DemoSize.hairline).background(colors.hairline))
        Text(text, Modifier.padding(horizontal = DemoSpacing.block), color = colors.muted, style = DemoType.bodySmall)
        Box(Modifier.weight(1f).height(DemoSize.hairline).background(colors.hairline))
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
 * an [action] button. With [onDismiss] the line is a note the person can close: a small ✕ at its end.
 */
@Composable
fun LimitLine(
    text: String,
    action: String?,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
    secondAction: String? = null,
    onSecondAction: () -> Unit = {},
    onDismiss: (() -> Unit)? = null,
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
            Row(Modifier.heightIn(min = DemoSize.target), verticalAlignment = Alignment.CenterVertically) {
                Text(text, Modifier.weight(1f), color = colors.ink, style = DemoType.bodyMedium)
                if (action != null) {
                    Spacer(Modifier.width(DemoSpacing.block))
                    QuietButton(text = action, onClick = onAction)
                }
                if (onDismiss != null) {
                    IconAction(
                        DemoIcons.Close,
                        stringResource(R.string.ds_dismiss),
                        onDismiss,
                        muted = true,
                        small = true,
                    )
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
                        .heightIn(min = DemoSize.chip)
                        .padding(horizontal = DemoSpacing.compact)
                        .wrapContentHeight(Alignment.CenterVertically),
                    color = colors.ink,
                    style = DemoType.label,
                )
            }
        }
    }
}

/**
 * A picture attached to the message being written, inside the message bar: 64 dp with a 12 dp corner, a soft fill
 * while it decodes, and a ✕ on a scrim 4 dp in from its top end to take it back. The ✕'s 48 dp target reaches past the
 * picture's corner instead of covering most of it.
 */
@Composable
fun Thumbnail(
    picture: ImageBitmap?,
    description: String,
    removeLabel: String,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalDemoColors.current
    Box(modifier.size(DemoSize.thumbnail)) {
        Box(Modifier.matchParentSize().clip(DemoShapes.card).background(colors.bubble)) {
            picture?.let {
                Image(
                    it,
                    contentDescription = description,
                    Modifier.matchParentSize(),
                    contentScale = ContentScale.Crop,
                )
            }
        }
        Box(Modifier.align(Alignment.TopEnd).padding(DemoSpacing.inline).size(DemoSize.badge)) {
            // a target larger than its box is centred on it: the 48 dp target sits round the 24 dp circle
            Box(
                Modifier
                    .requiredSize(DemoSize.target)
                    .clip(CircleShape)
                    .clickable(role = Role.Button, onClick = onRemove),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier.size(DemoSize.badge).background(colors.photoScrim, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        DemoIcons.Close,
                        contentDescription = removeLabel,
                        tint = colors.onPhotoScrim,
                        modifier = Modifier.size(DemoSize.iconSmall),
                    )
                }
            }
        }
    }
}

/**
 * The "back to the latest message" button of a chat scrolled away from its end: a 40 dp raised circle (in a 48 dp
 * target) with a down arrow, 8 dp above the bottom of the list. It is [visible] while there is more below, growing in
 * and fading like Material's floating button, and shrinking away quicker.
 */
@Composable
fun JumpToLatest(visible: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    AnimatedVisibility(
        visible,
        modifier,
        enter =
            fadeIn(tween(DemoMotion.ENTER_MILLIS, easing = DemoMotion.enter)) +
                scaleIn(tween(DemoMotion.ENTER_MILLIS, easing = DemoMotion.enter), DemoMotion.POP_SCALE),
        exit =
            fadeOut(tween(DemoMotion.EXIT_MILLIS, easing = DemoMotion.exit)) +
                scaleOut(tween(DemoMotion.EXIT_MILLIS, easing = DemoMotion.exit), DemoMotion.POP_SCALE),
    ) { JumpButton(onClick) }
}

@Composable
private fun JumpButton(onClick: () -> Unit) {
    val colors = LocalDemoColors.current
    Box(Modifier.padding(bottom = DemoSpacing.block).minimumInteractiveComponentSize()) {
        Box(
            Modifier
                .size(DemoSize.control)
                .softShadow(CircleShape, colors)
                .clip(CircleShape)
                .background(colors.surface)
                .border(DemoSize.hairline, colors.hairline, CircleShape)
                .clickable(role = Role.Button, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                DemoIcons.ChevronDown,
                contentDescription = stringResource(R.string.ds_jump_to_latest),
                tint = colors.ink,
                modifier = Modifier.size(DemoSize.icon),
            )
        }
    }
}
