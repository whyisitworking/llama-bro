package com.suhel.llamabro.demo.designsystem.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import com.suhel.llamabro.demo.designsystem.R
import com.suhel.llamabro.demo.designsystem.theme.DemoMotion
import com.suhel.llamabro.demo.designsystem.theme.DemoShapes
import com.suhel.llamabro.demo.designsystem.theme.DemoSize
import com.suhel.llamabro.demo.designsystem.theme.DemoSpacing
import com.suhel.llamabro.demo.designsystem.theme.DemoType
import com.suhel.llamabro.demo.designsystem.theme.LocalDemoColors
import com.suhel.llamabro.demo.designsystem.theme.LocalGlass
import com.suhel.llamabro.demo.designsystem.theme.animationsDisabled
import com.suhel.llamabro.demo.designsystem.theme.onPhotoScrim
import com.suhel.llamabro.demo.designsystem.theme.photoScrim
import com.suhel.llamabro.demo.designsystem.theme.subtleFill

/**
 * Long-press for a message's actions (copy, edit, share, regenerate), with a haptic tick; a plain tap does nothing. It
 * is not a click: a clickable bubble whose text has scrolled out of view reads to a screen reader as a button with no
 * label, and its double tap would do nothing. A screen reader offers the actions by name instead. The touch area is at
 * least 48 dp even when a short reply is not; the drawn size doesn't change.
 */
private fun Modifier.messageActions(onLongClick: (() -> Unit)?, touchArea: Boolean): Modifier =
    if (onLongClick == null) {
        // a reply still being written has no actions yet but keeps the saved reply's size (D146)
        if (touchArea) minimumInteractiveComponentSize() else this
    } else {
        composed {
            val label = stringResource(R.string.ds_message_actions)
            val haptics = LocalHapticFeedback.current
            val action by rememberUpdatedState(onLongClick)
            // the action and the touch go before the minimum size, so both cover the 48 dp it makes, not only the
            // bubble inside it
            Modifier
                .semantics {
                    onLongClick(label) {
                        action()
                        true
                    }
                }.pointerInput(haptics) {
                    detectTapGestures(
                        onLongPress = {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            action()
                        },
                    )
                }.minimumInteractiveComponentSize()
        }
    }

/**
 * The user's message: a soft neutral bubble (the theme's `bubble` colour) on the right with ink text, its tail corner
 * bottom end, at most 80% of the row; attached [images] above it, each opening with [onImageClick] (its index) when
 * given; the [time] in its corner (the chat list layout only). A voice message (D76) is its [voice] clip in a bubble of
 * the same shape, played and paused with [onVoiceToggle], the time after its length, with its [caption] under it in
 * muted [DemoType.bodyMedium] when voice mode captioned it (D100).
 */
@Composable
fun UserBubble(
    text: String,
    modifier: Modifier = Modifier,
    time: String? = null,
    images: List<ImageBitmap> = emptyList(),
    onLongClick: (() -> Unit)? = null,
    onImageClick: ((index: Int) -> Unit)? = null,
    voice: VoiceClip? = null,
    onVoiceToggle: () -> Unit = {},
    caption: String? = null,
) {
    val colors = LocalDemoColors.current
    Column(
        modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(DemoSpacing.inline),
    ) {
        val openLabel = stringResource(R.string.ds_open_photo)
        images.forEachIndexed { index, image ->
            Image(
                image,
                contentDescription = stringResource(R.string.ds_photo_position, index + 1, images.size),
                modifier =
                    Modifier
                        .size(
                            width = DemoSize.messageImageWidth,
                            height = DemoSize.messageImageHeight,
                        ).clip(DemoShapes.card)
                        .then(
                            if (onImageClick == null) {
                                Modifier
                            } else {
                                Modifier.clickable(onClickLabel = openLabel, role = Role.Image) { onImageClick(index) }
                            },
                        ),
                contentScale = ContentScale.Crop,
            )
        }
        if (voice != null) {
            VoiceBubble(
                voice,
                onVoiceToggle,
                Modifier
                    .bubbleIn(end = true)
                    .clip(DemoShapes.userBubble)
                    .background(colors.bubble)
                    .messageActions(onLongClick, false)
                    .padding(start = DemoSpacing.inline, end = DemoSpacing.item),
                time = time.takeIf { text.isEmpty() },
            )
            if (!caption.isNullOrEmpty()) {
                Text(
                    caption,
                    Modifier.bubbleIn(end = true).padding(horizontal = DemoSpacing.inline),
                    color = colors.muted,
                    style = DemoType.bodyMedium,
                    textAlign = TextAlign.End,
                )
            }
        }
        if (text.isNotEmpty() || (time != null && voice == null)) {
            Column(
                Modifier
                    .bubbleIn(end = true)
                    .clip(DemoShapes.userBubble)
                    .background(colors.bubble)
                    .messageActions(onLongClick, false)
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
    modifier: Modifier = Modifier,
    time: String? = null,
    speed: String? = null,
    onLongClick: (() -> Unit)? = null,
    /** Lays the reply out as one with actions even without them, so it keeps its size when they arrive (D146). */
    touchArea: Boolean = onLongClick != null,
) {
    val colors = LocalDemoColors.current
    Box(modifier.fillMaxWidth()) {
        Column(
            Modifier
                .bubbleIn(end = false)
                .softShadow(DemoShapes.modelBubble, colors)
                .clip(DemoShapes.modelBubble)
                .background(colors.surface)
                .messageActions(onLongClick, touchArea)
                .padding(horizontal = DemoSpacing.item, vertical = DemoSpacing.compact),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(DemoSpacing.inline),
        ) {
            Box(Modifier.align(Alignment.Start)) { content() }
            if (time != null || speed != null) {
                Text(listOfNotNull(time, speed).joinToString(" · "), color = colors.muted, style = DemoType.bodySmall)
            }
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
    /** Lays the reply out as one with actions even without them, so it keeps its size when they arrive (D146). */
    touchArea: Boolean = onLongClick != null,
) {
    val colors = LocalDemoColors.current
    Column(
        modifier.fillMaxWidth().messageActions(onLongClick, touchArea),
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
 * mirrored in RTL. With [easeIn] the row eases in under the reply when it is first composed, growing from nothing and
 * fading in (D146), at once with animations off; the caller says so only for a reply that has just finished. Without
 * it the row is there at once. A caller that stops composing it removes it at once.
 */
@Composable
fun ReplyActions(modifier: Modifier = Modifier, easeIn: Boolean = true, content: @Composable RowScope.() -> Unit) {
    val still = animationsDisabled()
    val shown = remember { MutableTransitionState(!easeIn) }.apply { targetState = true }
    AnimatedVisibility(
        shown,
        enter =
            if (still) {
                EnterTransition.None
            } else {
                expandVertically(tween(DemoMotion.STANDARD_MILLIS, easing = DemoMotion.standard)) +
                    fadeIn(tween(DemoMotion.STANDARD_MILLIS))
            },
        exit = ExitTransition.None,
    ) {
        Row(
            modifier.fillMaxWidth().offset(x = -(DemoSize.target - DemoSize.iconSmall) / 2),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
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
        Box(Modifier.weight(1f).height(DemoSize.hairline).background(colors.hairline))
        Text(text, Modifier.padding(horizontal = DemoSpacing.block), color = colors.muted, style = DemoType.bodySmall)
        Box(Modifier.weight(1f).height(DemoSize.hairline).background(colors.hairline))
    }
}

/** The line under a message held for a model that isn't ready ("Qwen3.5 0.8B will answer the moment it's here"). */
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
 * an [action] button. With [onDismiss] the line is a note the person can close: a small ✕ at its end. Two actions, or
 * one at large font ([isLargeText]), sit on their own row under the line.
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
        // two actions do not fit beside the line, nor one at large font, where beside it the words get a column a few
        // letters wide: the line, then the actions on the end of their own row
        if (action != null && (secondAction != null || isLargeText())) {
            Column {
                Text(
                    text,
                    Modifier.padding(top = DemoSpacing.block, end = DemoSpacing.block),
                    color = colors.ink,
                    style = DemoType.bodyMedium,
                )
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (secondAction != null) {
                        QuietButton(text = secondAction, onClick = onSecondAction)
                        Spacer(Modifier.width(DemoSpacing.block))
                    }
                    QuietButton(text = action, onClick = onAction)
                    if (onDismiss != null) DismissAction(onDismiss)
                }
            }
        } else {
            Row(Modifier.heightIn(min = DemoSize.target), verticalAlignment = Alignment.CenterVertically) {
                Text(text, Modifier.weight(1f), color = colors.ink, style = DemoType.bodyMedium)
                if (action != null) {
                    Spacer(Modifier.width(DemoSpacing.block))
                    QuietButton(text = action, onClick = onAction)
                }
                if (onDismiss != null) DismissAction(onDismiss)
            }
        }
    }
}

/** The small muted ✕ that closes a [LimitLine] note. */
@Composable
private fun DismissAction(onDismiss: () -> Unit) {
    IconAction(DemoIcons.Close, stringResource(R.string.ds_dismiss), onDismiss, muted = true, small = true)
}

/**
 * Starter prompts of an empty chat ("Plan my week"), or the recent searches over the Models page's results, as soft
 * chips; tapping one hands its text to [onPick]. [centered] for the empty chat's middle; a list's chips start where
 * its rows do.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StarterChips(
    items: List<String>,
    onPick: (String) -> Unit,
    modifier: Modifier = Modifier,
    centered: Boolean = true,
) {
    val colors = LocalDemoColors.current
    FlowRow(
        modifier.fillMaxWidth(),
        horizontalArrangement =
            Arrangement.spacedBy(DemoSpacing.block, if (centered) Alignment.CenterHorizontally else Alignment.Start),
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
 * while it decodes, and a ✕ on a scrim 4 dp in from its top end to take it back. The ✕'s 48 dp target is the
 * picture's top-end corner, inside the picture: a target reaching past it would be clipped to the picture's bounds for
 * accessibility services, which would then report it smaller than 48 dp (F130).
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
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .size(DemoSize.target)
                .clip(DemoShapes.card)
                .clickable(role = Role.Button, onClick = onRemove),
            contentAlignment = Alignment.TopEnd,
        ) {
            Box(
                Modifier
                    .padding(DemoSpacing.inline)
                    .size(DemoSize.badge)
                    .background(colors.photoScrim, CircleShape),
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
        // over a page ([FloatingFrame]'s overlay), glass with a panel's denser veil, so its chevron reads over the bent
        // text under it (D148); elsewhere a raised surface
        val backdrop = LocalBackdrop.current
        val presses = if (backdrop != null) remember { MutableInteractionSource() } else null
        Box(
            Modifier
                .size(DemoSize.control)
                .then(
                    if (backdrop != null) {
                        Modifier.glass(backdrop, LocalGlass.current.panel, interactions = presses).clip(CircleShape)
                    } else {
                        Modifier
                            .softShadow(CircleShape, colors)
                            .clip(CircleShape)
                            .background(colors.surface)
                            .border(DemoSize.hairline, colors.hairline, CircleShape)
                    },
                ).glassClickable(presses, glass = backdrop != null, onClick = onClick),
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
