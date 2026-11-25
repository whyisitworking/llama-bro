package com.suhel.llamabro.demo.designsystem.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedContentTransitionScope.SlideDirection
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.zIndex
import com.suhel.llamabro.demo.designsystem.R
import com.suhel.llamabro.demo.designsystem.theme.DemoMotion
import com.suhel.llamabro.demo.designsystem.theme.DemoSize
import com.suhel.llamabro.demo.designsystem.theme.DemoSpacing
import com.suhel.llamabro.demo.designsystem.theme.DemoType
import com.suhel.llamabro.demo.designsystem.theme.LocalDemoColors
import com.suhel.llamabro.demo.designsystem.theme.animationsDisabled
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * A message list's row (its [row] scope) that fades in when it arrives (a sent message, a day, the reply starting)
 * and glides when the rows above it change size; the rows of a chat that opens are already there and do not fade.
 * [appear] is false for a row that replaces one already on screen (a finished reply taking the place of the streaming
 * one), which would otherwise blink. Instant when [still] (animations off); the list reads that once
 * ([animationsDisabled]) rather than every row.
 */
fun Modifier.messageMotion(row: LazyItemScope, still: Boolean, appear: Boolean = true): Modifier =
    with(row) {
        this@messageMotion.animateItem(
            fadeInSpec = if (appear && !still) tween(DemoMotion.ENTER_MILLIS, easing = DemoMotion.enter) else null,
            placementSpec = if (still) null else tween(DemoMotion.STANDARD_MILLIS, easing = DemoMotion.standard),
            fadeOutSpec = null,
        )
    }

/**
 * A body that changes kind ([target]: a page's mode, say) crossfades from one to the next (models-page design §5,
 * "bodies that change kind crossfade"), in [DemoMotion.CROSSFADE_MILLIS], at once with animations off.
 */
@Composable
fun <T> Crossfaded(
    target: T,
    modifier: Modifier = Modifier,
    label: String = "crossfade",
    content: @Composable (T) -> Unit,
) {
    val fade = tween<Float>(if (animationsDisabled()) 0 else DemoMotion.CROSSFADE_MILLIS)
    AnimatedContent(
        target,
        modifier,
        transitionSpec = { fadeIn(fade) togetherWith fadeOut(fade) },
        label = label,
    ) { shown -> content(shown) }
}

/**
 * Text that changes in place fades through (models-page design §5, D119): the shown words fade out in
 * [DemoMotion.TEXT_OUT_MILLIS], are replaced, and the new ones fade in over [DemoMotion.TEXT_IN_MILLIS]. Only one
 * value is ever composed, so two texts never draw over each other (a crossfade double-exposed "Paused · Q4_K_M" on
 * "Downloading · Q4_K_M"). A [target] with the same [contentKey] as the one shown is drawn at once, without a fade
 * (the bytes moving within a stage). Instant with animations off. Crossfade only icons and fills; text fades through.
 */
@Composable
fun <T> FadeThrough(
    target: T,
    modifier: Modifier = Modifier,
    contentKey: (T) -> Any? = { it },
    /** Whether the height eases between the two values (a body that changes kind, as Basic to Advanced). */
    sizeEases: Boolean = false,
    content: @Composable (T) -> Unit,
) {
    val through = rememberFadeThrough(target, contentKey)
    val size = if (sizeEases) Modifier.animateContentSize(if (through.still) snap() else rowSizeSpec()) else Modifier
    Box(modifier.then(size).graphicsLayer { this.alpha = through.alpha }) { content(through.drawn) }
}

/**
 * A [FadeThrough]'s state, for a component that draws its parts apart (the ring panel draws its ring's amount from the
 * same value as its words, D120): [drawn] is the value to draw now and [alpha] the words' fade.
 */
@Stable
class FadeThroughState<T> internal constructor(
    drawnState: State<T>,
    private val alphaState: State<Float>,
    val still: Boolean,
) {
    val drawn: T by drawnState
    val alpha: Float get() = alphaState.value
}

/**
 * The fade-through of [target] as state ([FadeThrough] draws it): the value shown is kept current while its
 * [contentKey] matches the target's, so a value fading out is the last one drawn, never an older one of its kind
 * ("36 of 739 MB" fading out after "443 of 739 MB" was shown).
 */
@Composable
fun <T> rememberFadeThrough(target: T, contentKey: (T) -> Any? = { it }): FadeThroughState<T> {
    val still = animationsDisabled()
    val shown = remember { mutableStateOf(target) }
    val latest by rememberUpdatedState(target)
    val alpha = remember { Animatable(1f) }
    val key = contentKey(target)
    // the same kind as the one shown: it becomes the one shown, so a fade-out that starts later fades the newest
    SideEffect { if (contentKey(shown.value) == key) shown.value = target }
    LaunchedEffect(key, still) {
        if (contentKey(shown.value) != key) {
            if (!still) alpha.animateTo(0f, tween(DemoMotion.TEXT_OUT_MILLIS, easing = DemoMotion.exit))
            shown.value = latest
        }
        if (still) {
            alpha.snapTo(1f)
        } else {
            alpha.animateTo(1f, tween(DemoMotion.TEXT_IN_MILLIS, easing = DemoMotion.enter))
        }
    }
    // the same kind as the one shown: draw the newest value of it, so a progress tick isn't held back by a fade
    val drawn = rememberUpdatedState(if (still || contentKey(shown.value) == key) target else shown.value)
    val alphaState = remember(alpha) { derivedStateOf { alpha.value } }
    return remember(drawn, alphaState, still) { FadeThroughState(drawn, alphaState, still) }
}

/**
 * [value]'s part while there is one, in the list motion's order (exit, place, enter; D120): as it comes it waits for
 * what leaves ([DemoMotion.TEXT_OUT_MILLIS]), unfolds, and fades in from halfway through the unfolding, so its words
 * are never drawn cut by a growing edge; as it goes it fades out first and then folds away, keeping its last value
 * while it leaves, so it never appears or vanishes in one frame and is never clipped mid-glyph. Instant with
 * animations off.
 */
@Composable
fun <T : Any> Unfold(value: T?, modifier: Modifier = Modifier, content: @Composable (T) -> Unit) {
    val last = remember { mutableStateOf(value) }
    SideEffect { if (value != null) last.value = value }
    val still = animationsDisabled()
    AnimatedVisibility(
        value != null,
        modifier,
        enter = if (still) EnterTransition.None else unfoldEnter(),
        exit = if (still) ExitTransition.None else foldExit(),
    ) {
        (value ?: last.value)?.let { content(it) }
    }
}

/**
 * A part arriving in the list motion's order: it unfolds once what leaves has faded ([listPlaceSpec]), and its
 * content fades in from halfway through the unfolding ([enterFadeSpec]).
 */
fun unfoldEnter(alignment: Alignment.Vertical = Alignment.Top): EnterTransition =
    expandVertically(listPlaceSpec(), alignment) + fadeIn(enterFadeSpec())

/** A part leaving: its content fades out first, then it folds away ([listPlaceSpec]), never clipped while visible. */
fun foldExit(alignment: Alignment.Vertical = Alignment.Top): ExitTransition =
    fadeOut(exitFadeSpec()) + shrinkVertically(listPlaceSpec(), alignment)

/**
 * A list item's motion on the Models page and wherever items come, go and move: one policy, exit, then place, then
 * enter (D120). A leaving item fades out in [DemoMotion.TEXT_OUT_MILLIS] ([exitFadeSpec]); the items that stay move
 * only after that ([listPlaceSpec]), over [DemoMotion.STANDARD_MILLIS]; an arriving one fades in once they have
 * placed ([listEnterSpec]). So no moving row ever crosses a row still leaving or one already arriving (D120). A
 * row's own size change uses [listPlaceSpec] too, so its neighbours never lag or lead it. A [label] draws under the
 * rows, so a row moving past it covers it. Instant with animations off.
 */
@Composable
fun Modifier.listItemMotion(item: LazyItemScope, label: Boolean = false): Modifier {
    val still = animationsDisabled()
    return with(item) {
        this@listItemMotion
            .zIndex(if (label) 0f else 1f)
            .animateItem(
                fadeInSpec = if (still) null else listEnterSpec(),
                placementSpec = if (still) null else listPlaceSpec(),
                fadeOutSpec = if (still) null else exitFadeSpec(),
            )
    }
}

/** The list motion's first step: what leaves fades out, quickly. */
fun <T> exitFadeSpec(): FiniteAnimationSpec<T> = tween(DemoMotion.TEXT_OUT_MILLIS, easing = DemoMotion.exit)

/** The list motion's second step: what stays moves (and grows or shrinks), once what leaves has faded. */
fun <T> listPlaceSpec(): FiniteAnimationSpec<T> =
    tween(DemoMotion.STANDARD_MILLIS, delayMillis = DemoMotion.TEXT_OUT_MILLIS, easing = DemoMotion.standard)

/** The list motion's last step: an arriving item fades in once everything has moved into place. */
fun <T> listEnterSpec(): FiniteAnimationSpec<T> =
    tween(
        DemoMotion.TEXT_IN_MILLIS,
        delayMillis = DemoMotion.TEXT_OUT_MILLIS + DemoMotion.STANDARD_MILLIS,
        easing = DemoMotion.enter,
    )

/** A part's content fading in from halfway through its unfolding. */
fun <T> enterFadeSpec(): FiniteAnimationSpec<T> =
    tween(
        DemoMotion.TEXT_IN_MILLIS,
        delayMillis = DemoMotion.TEXT_OUT_MILLIS + DemoMotion.STANDARD_MILLIS / 2,
        easing = DemoMotion.enter,
    )

/**
 * A screen arriving (a navigation), along one axis (D157, Material's shared axis): it slides in from the end a
 * [DemoMotion.SCREEN_SLIDE_SHARE] of the screen's width, or from the start when going [back], settling over
 * [DemoMotion.ENTER_MILLIS]; it waits [DemoMotion.TEXT_OUT_MILLIS] for the one leaving to fade out, then fades in, so
 * two screens' text never draw over each other (D119). The way it slides follows the layout's direction. None when
 * [still].
 */
fun <S> AnimatedContentTransitionScope<S>.screenEnter(still: Boolean, back: Boolean = false): EnterTransition =
    if (still) {
        EnterTransition.None
    } else {
        fadeIn(tween(DemoMotion.TEXT_IN_MILLIS, delayMillis = DemoMotion.TEXT_OUT_MILLIS, easing = DemoMotion.enter)) +
            slideIntoContainer(
                if (back) SlideDirection.End else SlideDirection.Start,
                tween(DemoMotion.ENTER_MILLIS, easing = DemoMotion.enter),
                initialOffset = { (it * DemoMotion.SCREEN_SLIDE_SHARE).roundToInt() },
            )
    }

/**
 * A screen leaving, along the same axis (D157): it fades out before the next fades in, sliding half as far the other
 * way as it goes. None when [still].
 */
fun <S> AnimatedContentTransitionScope<S>.screenExit(still: Boolean, back: Boolean = false): ExitTransition =
    if (still) {
        ExitTransition.None
    } else {
        fadeOut(tween(DemoMotion.TEXT_OUT_MILLIS, easing = DemoMotion.exit)) +
            slideOutOfContainer(
                if (back) SlideDirection.End else SlideDirection.Start,
                tween(DemoMotion.EXIT_MILLIS, easing = DemoMotion.exit),
                targetOffset = { (it * DemoMotion.SCREEN_SLIDE_SHARE / 2f).roundToInt() },
            )
    }

/**
 * How a row's size changes and its neighbours move: one spec for both, the list motion's place step
 * ([listPlaceSpec]), so neither outruns the other and both wait for what fades out.
 */
fun <T> rowSizeSpec(): FiniteAnimationSpec<T> = listPlaceSpec()

/**
 * Something that comes and goes above the message bar (a note, a problem, the editing line): it unfolds from its top
 * and fades in, and folds away quicker. [value] is what to show, or null; while it folds away it keeps showing the last
 * value, so the line does not go blank before it leaves. Instant with animations off.
 */
@Composable
fun <T : Any> Reveal(value: T?, modifier: Modifier = Modifier, content: @Composable (T) -> Unit) {
    val last = remember { mutableStateOf(value) }
    SideEffect { if (value != null) last.value = value }
    val still = animationsDisabled()
    AnimatedVisibility(
        visible = value != null,
        modifier = modifier,
        enter =
            if (still) {
                EnterTransition.None
            } else {
                fadeIn(tween(DemoMotion.ENTER_MILLIS, easing = DemoMotion.enter)) +
                    expandVertically(tween(DemoMotion.ENTER_MILLIS, easing = DemoMotion.enter), Alignment.Top)
            },
        exit =
            if (still) {
                ExitTransition.None
            } else {
                fadeOut(tween(DemoMotion.EXIT_MILLIS, easing = DemoMotion.exit)) +
                    shrinkVertically(tween(DemoMotion.EXIT_MILLIS, easing = DemoMotion.exit), Alignment.Top)
            },
    ) {
        (value ?: last.value)?.let { content(it) }
    }
}

/**
 * Three muted dots in a wave, where a reply will be, while the model reads the prompt (or wakes) and before its first
 * word: the chat apps' typing indicator. One line of a reply's text tall, so the first words take its place without
 * a jump. With animations off the dots hold still.
 */
@Composable
fun TypingDots(modifier: Modifier = Modifier) {
    val colors = LocalDemoColors.current
    val label = stringResource(R.string.ds_replying)
    val phase = if (animationsDisabled()) null else rememberLoop(DemoMotion.DOTS_MILLIS)
    val line = with(LocalDensity.current) { DemoType.replyBody.lineHeight.toDp() }
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
