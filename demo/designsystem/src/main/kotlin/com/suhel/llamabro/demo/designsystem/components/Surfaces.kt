package com.suhel.llamabro.demo.designsystem.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.suhel.llamabro.demo.designsystem.R
import com.suhel.llamabro.demo.designsystem.theme.DemoColors
import com.suhel.llamabro.demo.designsystem.theme.DemoMotion
import com.suhel.llamabro.demo.designsystem.theme.DemoShapes
import com.suhel.llamabro.demo.designsystem.theme.DemoSize
import com.suhel.llamabro.demo.designsystem.theme.DemoSpacing
import com.suhel.llamabro.demo.designsystem.theme.DemoType
import com.suhel.llamabro.demo.designsystem.theme.LocalDemoColors
import com.suhel.llamabro.demo.designsystem.theme.animationsDisabled
import com.suhel.llamabro.demo.designsystem.theme.scrim
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import androidx.compose.ui.semantics.text as titleText

/**
 * What a model's fit says, as a word and its colour ([FitText]): [Great] a sage green, [Slow] a sand brown, [TooBig] a
 * grey; "runs well" is [Plain], the muted ink. A fit is never a card fill (D48): a card says *selected* with its border
 * and nothing else, so one signal means one thing. [Accent] is ink text for emphasis, against [Plain]'s muted one
 * (the accent is the ink, D69).
 */
enum class CardTint { Plain, Great, Slow, TooBig, Accent }

/** The text colour that goes with the fit (a fit label's colour). */
internal fun CardTint.ink(colors: DemoColors): Color =
    when (this) {
        CardTint.Plain -> colors.muted
        CardTint.Great -> colors.greatInk
        CardTint.Slow -> colors.slowInk
        CardTint.TooBig -> colors.tooBigInk
        CardTint.Accent -> colors.accent
    }

/**
 * A card: 12 dp corners on the surface (the canvas on a sheet), no edge, 16 dp of padding inside. A card on the
 * canvas has the soft shadow. [selected] outlines it 2 dp in the accent: the one signal that a card is chosen.
 * [edgeLabel] ("Default", "Recommended") is a small tonal label on its own line at the top end, inside the card, so it
 * never covers the card's own text.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DemoCard(
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    edgeLabel: String? = null,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(DemoSpacing.item),
    shape: Shape = DemoShapes.card,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = LocalDemoColors.current
    val onSheet = LocalOnSheet.current
    Column(
        modifier
            .testTag(DsTags.CARD_SURFACE)
            .then(if (selected) Modifier.semantics { this.selected = true } else Modifier)
            .then(if (!onSheet) Modifier.softShadow(shape, colors) else Modifier)
            .clip(shape)
            .background(if (onSheet) colors.canvas else colors.surface)
            .then(if (selected) Modifier.border(DemoSize.selectedStroke, colors.accent, shape) else Modifier)
            .then(
                if (onClick != null || onLongClick != null) {
                    Modifier.combinedClickable(onClick = onClick ?: {}, onLongClick = onLongClick)
                } else {
                    Modifier
                },
            ).padding(contentPadding),
    ) {
        if (edgeLabel != null) {
            EdgeChip(edgeLabel, Modifier.align(Alignment.End))
            Spacer(Modifier.height(DemoSpacing.inline))
        }
        content()
    }
}

/**
 * The small label at a card's top end or a row's end: "Default" in the switcher, "Recommended" on a quant. A tonal
 * [StatusPill], so it no longer outweighs the name beside it; a long label ellipsises.
 */
@Composable
fun EdgeChip(text: String, modifier: Modifier = Modifier) {
    StatusPill(text, modifier)
}

/**
 * The app's bottom sheet: Material's modal sheet with the grab handle, 28 dp top corners, the surface colour and the
 * design's scrim, opened fully. The sheet owns the gutter: its parts are [Gap.Item] apart on the 16 dp edge and a part
 * never pads itself to reach it. Plain cards inside sit on the canvas colour. An action that closes the sheet goes
 * through [state]'s [DemoSheetState.hideThenDismiss], so the sheet slides down first. The scrim's shade follows the
 * sheet's own position, so it is still there while the sheet slides away, not gone before the sheet is.
 *
 * An [expandable] sheet (a model's card, with its page) has one owner of its height (D119): it opens at
 * [DemoSize.sheetCompactHeight] (or the screen's height, if less) from its first frame, so it rises straight to its
 * top and content that lands later scrolls in room already there. Scrolled or pulled up,
 * it grows to the status bar's edge over [DemoMotion.ENTER_MILLIS], never in one frame. [pinned] (its
 * action) sits in a [DemoSize.sheetFoot] foot outside the scroll, in either height.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DemoSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    expandable: Boolean = false,
    pinned: (@Composable ColumnScope.() -> Unit)? = null,
    state: DemoSheetState = rememberDemoSheetState(),
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = LocalDemoColors.current
    val sheetState = state.sheet
    // a plain sheet whose content grows after it opens (a chat menu: a line becomes a list) was found parked at its
    // first, short height with the new content cut off below the screen (F118): whenever its content's height changes,
    // expand again to the new height. An expandable sheet never does: its height has one owner, ExpandableBody
    var contentHeight by remember { mutableIntStateOf(0) }
    LaunchedEffect(contentHeight) {
        if (contentHeight > 0) {
            withFrameNanos { } // let the sheet register the new height first, also while it is still opening
            // not while it is being dismissed: expanding then would cancel the hide and swallow the dismissal
            if (sheetState.targetValue != SheetValue.Hidden || !sheetState.isVisible) sheetState.expand()
        }
    }
    val scope = rememberCoroutineScope()
    val window = LocalWindowInfo.current.containerSize
    val scrim = colors.scrim
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        modifier =
            modifier.drawBehind {
                // drawn before the sheet's clip, so it covers the window behind the sheet; Material's own scrim fades
                // on a clock of its own and was gone while the sheet was still sliding down
                val offset = runCatching { sheetState.requireOffset() }.getOrNull() ?: return@drawBehind
                val shade = scrimShade(offset, size.height, window.height.toFloat())
                drawRect(
                    scrim,
                    topLeft = Offset(-(window.width - size.width) / 2f, 0f),
                    size = Size(window.width.toFloat(), window.height.toFloat()),
                    alpha = shade,
                )
            },
        sheetState = sheetState,
        shape = DemoShapes.sheet,
        containerColor = colors.surface,
        contentColor = colors.ink,
        tonalElevation = 0.dp,
        // the scrim is drawn above, from the sheet's position; Material's still takes the tap that closes the sheet
        scrimColor = Color.Transparent,
        // Material's handle carries a tooltip and takes focus whenever the sheet opens without touch (models audit
        // S27); the sheet draws its own
        dragHandle = null,
        // an expandable sheet takes no top inset: Material pads its content by the status bar until it knows the
        // sheet's offset (it consumes the top inset by `offset`, NaN before the first placement), so the first
        // measured height was the compact height plus the status bar and the sheet dropped by that a frame later.
        // Its full height is figured from the insets instead ([fullBodyPx])
        contentWindowInsets = {
            if (expandable) {
                WindowInsets.safeDrawing.only(
                    WindowInsetsSides.Bottom,
                )
            } else {
                BottomSheetDefaults.windowInsets
            }
        },
    ) {
        GrabHandle(
            Modifier.align(Alignment.CenterHorizontally),
            onDismiss = { scope.launch { state.hideThenDismiss(onDismissRequest) } },
        )
        if (expandable) {
            ExpandableBody(content, pinned)
        } else {
            SheetBody(content, Modifier.onSizeChanged { contentHeight = it.height }, pinned)
        }
    }
}

/**
 * How much of the scrim shows for a sheet whose top is at [offset] in a window [windowHeight] tall, the sheet being
 * [sheetHeight] tall: all of it once the sheet is wholly up, none once it is wholly down, in between in proportion.
 */
internal fun scrimShade(offset: Float, sheetHeight: Float, windowHeight: Float): Float =
    if (sheetHeight <= 0f) 0f else ((windowHeight - offset) / sheetHeight).coerceIn(0f, 1f)

/** A [DemoSheet]'s state, for the actions that close it. */
@OptIn(ExperimentalMaterial3Api::class)
@Stable
class DemoSheetState internal constructor(internal val sheet: SheetState) {
    /** Whether the sheet is on screen (also while it slides). */
    val isVisible: Boolean get() = sheet.isVisible

    /** Brings a sheet that is sliding away back up, as a drag that catches it does. */
    suspend fun expand() = sheet.expand()

    /**
     * Slides the sheet down, then calls [onDismiss], as a swipe or the scrim does; an action that dismissed at once
     * made the sheet vanish. A drag that catches the sheet on its way down keeps it open.
     */
    suspend fun hideThenDismiss(onDismiss: () -> Unit) {
        try {
            sheet.hide()
        } finally {
            if (!sheet.isVisible) onDismiss()
        }
    }
}

/** A [DemoSheetState] that opens fully, as every [DemoSheet] does. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun rememberDemoSheetState(): DemoSheetState {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    return remember(sheet) { DemoSheetState(sheet) }
}

/**
 * The sheet's handle: Material's 32 x 4 dp bar centred in a 48 dp box with a label; tapped, it closes the sheet, as
 * Material's does. It shows no tooltip, and takes focus only from a keyboard, ringed in the accent then: a sheet
 * opened without touch focused it, with a tooltip and a grey box. [onDismiss] null draws it inert
 * (previews).
 */
@Composable
private fun GrabHandle(modifier: Modifier = Modifier, onDismiss: (() -> Unit)? = null) {
    val colors = LocalDemoColors.current
    val description = stringResource(R.string.ds_drag_handle)
    val dismissLabel = stringResource(R.string.ds_dismiss)
    val inputMode = LocalInputModeManager.current
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier
            .size(DemoSize.target)
            .then(
                if (onDismiss != null) {
                    Modifier
                        .focusProperties { canFocus = inputMode.inputMode == InputMode.Keyboard }
                        .onFocusChanged { focused = it.isFocused }
                        .clickable(
                            interactionSource = null,
                            indication = null,
                            onClickLabel = dismissLabel,
                            onClick = onDismiss,
                        )
                } else {
                    Modifier
                },
            ).then(if (focused) Modifier.border(DemoSize.selectedStroke, colors.accent, DemoShapes.card) else Modifier)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(
                    width = DemoSize.handleWidth,
                    height = DemoSize.handleHeight,
                ).background(colors.hairline, CircleShape),
        )
    }
}

/**
 * A sheet's body without a scroll of its own: the content, then [pinned] after it. [foot] puts [pinned] in the
 * expandable sheet's [SheetFoot] instead ([SheetFrame] standing in for a model's card); otherwise it follows the
 * content a [Gap.Item] apart, as a plain sheet's last part.
 */
@Composable
private fun SheetBody(
    content: @Composable ColumnScope.() -> Unit,
    modifier: Modifier = Modifier,
    pinned: (@Composable ColumnScope.() -> Unit)? = null,
    foot: Boolean = false,
) {
    val inFoot = foot && pinned != null
    CompositionLocalProvider(LocalOnSheet provides true) {
        Column(
            modifier
                .fillMaxWidth()
                .gutterContainer()
                .navigationBarsPadding()
                // the foot holds its own space under its action, and its own sides (it bleeds past the gutter)
                .padding(bottom = if (inFoot) 0.dp else DemoSpacing.item),
        ) {
            val onGutter = Modifier.fillMaxWidth().padding(horizontal = DemoSpacing.gutter).gutterEdge()
            if (inFoot) {
                Column(onGutter, Arrangement.spacedBy(Gap.Item.size), content = content)
                SheetFoot(checkNotNull(pinned), Modifier.padding(top = Gap.Item.size))
            } else {
                Column(onGutter, Arrangement.spacedBy(Gap.Item.size)) {
                    content()
                    pinned?.invoke(this)
                }
            }
        }
    }
}

/**
 * An expandable sheet's body: the content scrolls in a column [DemoSize.sheetCompactHeight] tall from the first frame
 * (the screen's height when that is less), and the sheet's top is where it settles: nothing that lands later moves it.
 * Once the content is scrolled (pulled up, or brought into view), the body grows
 * until the sheet's top meets the status bar, animated, so the sheet rises rather than jumping there. The
 * scroll's foot fades while there is more under it, so a page's first line under the foot's hairline reads as
 * continuing. [pinned] sits in the foot under the scroll, so it never scrolls away.
 */
@Composable
private fun ExpandableBody(
    content: @Composable ColumnScope.() -> Unit,
    pinned: (@Composable ColumnScope.() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val scroll = rememberScrollState()
    val density = LocalDensity.current
    // the full height is the window's less the status bar, the handle and the navigation bar: from the window and the
    // insets, never from the sheet's live position, which a scroll during the opening slide or a drag would overshoot
    val window = LocalWindowInfo.current.containerSize.height
    val statusBar = WindowInsets.statusBars.getTop(density)
    val navigationBar = WindowInsets.navigationBars.getBottom(density)
    val handle = with(density) { DemoSize.target.roundToPx() }
    val fullPx = fullBodyPx(window, statusBar, navigationBar, handle)
    val stillNow = rememberUpdatedState(animationsDisabled())
    val growth = remember { SheetGrowth { stillNow.value } }
    var full by growth.full
    LaunchedEffect(scroll) {
        snapshotFlow { scroll.value > 0 }.first { it }
        full = true
    }
    val compact = DemoSize.sheetCompactHeight
    val target = if (full) with(density) { fullPx.toDp() }.coerceAtLeast(compact) else compact
    val screenPx =
        with(density) {
            LocalConfiguration.current.screenHeightDp.dp
                .roundToPx()
        }
    val compactPx = with(density) { compact.roundToPx() }
    val still = animationsDisabled()
    val height by animateDpAsState(
        target,
        if (still) snap() else tween(DemoMotion.ENTER_MILLIS, easing = DemoMotion.standard),
        label = "sheet height",
    )
    CompositionLocalProvider(LocalOnSheet provides true) {
        Column(
            modifier
                .fillMaxWidth()
                // Material anchors the sheet's top at the window's height less the sheet's first measured height and
                // shows it as soon as that height isn't 0. A first pass in a window not yet given its full height
                // anchored the top near the screen's top, and the sheet rose there and dropped a frame later (D119):
                // until the window has room for the compact height, the body measures 0, so the show waits and its
                // first target is the compact height's
                .layout { measurable, constraints ->
                    if (sheetHeld(constraints.maxHeight, compactPx, screenPx)) {
                        layout(constraints.maxWidth, 0) {}
                    } else {
                        val placeable = measurable.measure(constraints)
                        layout(placeable.width, placeable.height) { placeable.place(0, 0) }
                    }
                }.height(height)
                .gutterContainer()
                .navigationBarsPadding()
                .padding(bottom = if (pinned == null) DemoSpacing.item else 0.dp),
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    // where the scroll shows its content, for a part that asks whether it is in view (SheetGrowth)
                    .onGloballyPositioned { growth.viewport = it.boundsInRoot() }
                    .edgeFade({ scroll.canScrollForward })
                    .verticalScroll(scroll)
                    .padding(horizontal = DemoSpacing.gutter)
                    .gutterEdge()
                    // the page's end keeps an item's gap from the foot's hairline
                    .padding(bottom = if (pinned != null) Gap.Item.size else 0.dp)
                    .testTag(DsTags.SHEET_SCROLL),
                Arrangement.spacedBy(Gap.Item.size),
            ) {
                CompositionLocalProvider(LocalSheetGrowth provides growth) {
                    content()
                }
            }
            if (pinned != null) SheetFoot(pinned, ruled = { scroll.canScrollForward })
        }
    }
}

/**
 * Whether an expandable sheet's body holds itself at 0 (so Material doesn't show the sheet yet): while the window gives
 * it less than the compact height ([bodyPx]) and less than nine tenths of the screen ([screenPx]), the window has not
 * been given its height. A screen shorter than the compact height shows the sheet at the screen's height.
 */
internal fun sheetHeld(maxHeightPx: Int, bodyPx: Int, screenPx: Int): Boolean =
    maxHeightPx != Constraints.Infinity && maxHeightPx < bodyPx &&
        maxHeightPx * HELD_SHARE_DEN < screenPx * HELD_SHARE_NUM

private const val HELD_SHARE_NUM = 9
private const val HELD_SHARE_DEN = 10

/**
 * An expandable sheet's body at full height: the window less the status bar (the sheet's top meets it), the
 * handle above the body and the navigation bar under it. Never from the sheet's live position.
 */
internal fun fullBodyPx(windowPx: Int, statusBarPx: Int, navigationBarPx: Int, handlePx: Int): Int =
    windowPx - statusBarPx - handlePx - navigationBarPx

/**
 * An expandable sheet's pinned foot: at least [DemoSize.sheetFoot] tall, one button with 8 dp above and below, and
 * never clipped: a taller action grows it (the card's foot is made one row, exactly 64 dp, by its caller). A hairline
 * marks its top edge while content runs under it ([ruled]), so a table row isn't cut right above the button with
 * nothing between. It bleeds [FootBleed] past the gutter ([LocalEdgeBleed]): a download panel's
 * 48 dp targets fit inside it while their marks meet the gutter (F130); content on the gutter uses [OnGutter].
 */
@Composable
private fun SheetFoot(
    pinned: @Composable ColumnScope.() -> Unit,
    modifier: Modifier = Modifier,
    ruled: () -> Boolean = { false },
) {
    val rule = LocalDemoColors.current.hairline
    Column(
        modifier
            .fillMaxWidth()
            // at least a button's foot; never clipped, so a taller action grows it rather than cut
            .heightIn(min = DemoSize.sheetFoot)
            .drawBehind {
                if (ruled()) {
                    val y = DemoSize.hairline.toPx() / 2
                    drawLine(rule, Offset(0f, y), Offset(size.width, y), DemoSize.hairline.toPx())
                }
            }.testTag(DsTags.SHEET_FOOT)
            .padding(
                horizontal = DemoSpacing.gutter - FootBleed,
                vertical = (DemoSize.sheetFoot - DemoSize.button) / 2,
            ),
        Arrangement.spacedBy(Gap.Block.size, Alignment.CenterVertically),
    ) { CompositionLocalProvider(LocalEdgeBleed provides FootBleed) { pinned() } }
}

/**
 * [content] at the height of the tallest of it and [reserve]: each of [reserve] is measured, never placed (not drawn,
 * not heard, not touched), so a part that changes kind keeps one height (the card's foot at large font, models-page
 * design §4.5). Without [reserve], [content] alone.
 */
@Composable
fun ReserveTallest(
    reserve: List<@Composable () -> Unit>,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    // measured only: never placed, so never drawn or touched, and kept out of the semantics, so never heard
    val silent = reserve.map { part -> @Composable { Box(Modifier.clearAndSetSemantics {}) { part() } } }
    Layout(
        contents = listOf(content) + silent,
        modifier = modifier,
    ) { measurables, constraints ->
        val loose = constraints.copy(minHeight = 0)
        val shown = measurables.first().map { it.measure(loose) }
        val tallest =
            measurables
                .drop(1)
                .flatten()
                .maxOfOrNull { it.measure(loose).height } ?: 0
        val height =
            maxOf(shown.maxOfOrNull { it.height } ?: 0, tallest).coerceIn(constraints.minHeight, constraints.maxHeight)
        layout(constraints.maxWidth, height) {
            shown.forEach { it.place(0, (height - it.height) / 2) }
        }
    }
}

/** How far a sheet's foot reaches past the gutter: an icon's inset in its 48 dp target. */
private val FootBleed = (DemoSize.target - DemoSize.icon) / 2

/**
 * The sheet as [DemoSheet] draws it, in place, without the window: for previews, screenshot tests and the gallery,
 * where a modal window can't be rendered. [pinned] sits in the sheet's foot.
 */
@Composable
fun SheetFrame(
    modifier: Modifier = Modifier,
    pinned: (@Composable ColumnScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = LocalDemoColors.current
    Column(
        modifier.fillMaxWidth().clip(DemoShapes.sheet).background(colors.surface),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        GrabHandle()
        SheetBody(content, pinned = pinned, foot = true)
    }
}

/**
 * A small question with two answers ("Remove Qwen3.5 0.8B? This frees 0.7 GB."): the surface card on the scrim, the
 * title, one plain line, a primary [confirmLabel] and, when there is a [dismissLabel], a bordered quiet one beside it
 * ("Keep"), never underlined text. A [danger] confirm (it deletes: "Cancel download", "Remove") is drawn in the danger
 * ink, bordered, never the accent. [content], when given, sits between the line and the buttons
 * (the fit symbols' key in "What's a model?"). The window is Material's; the card is [DialogCard].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DemoDialog(
    title: String,
    text: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    dismissLabel: String? = null,
    danger: Boolean = false,
    content: (@Composable () -> Unit)? = null,
) {
    BasicAlertDialog(onDismissRequest = onDismiss, modifier = modifier) {
        DialogCard(title, text, confirmLabel, onConfirm, dismissLabel, onDismiss, danger = danger, content = content)
    }
}

/**
 * The dialog's card on its own, for previews, screenshot tests and the gallery, where a window can't be drawn. The
 * title is a step over the line (title over bodyMedium, at any font size); the line and [content] scroll between the
 * title and the buttons, fading at their foot while there is more, so the buttons stay on screen at large font. At
 * large font ([isLargeText]) the buttons stack full width, the confirm on top, and a label wraps rather than being
 * cut ("Cancel do…").
 */
@Composable
fun DialogCard(
    title: String,
    text: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    dismissLabel: String?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    danger: Boolean = false,
    content: (@Composable () -> Unit)? = null,
) {
    val colors = LocalDemoColors.current
    val scroll = rememberScrollState()
    Column(
        modifier
            .fillMaxWidth()
            .clip(DemoShapes.card)
            .background(colors.surface)
            .padding(DemoSpacing.section),
        verticalArrangement = Arrangement.spacedBy(Gap.Block.size),
    ) {
        // a real step over the line at every font scale: at 2x the non-linear scaling left a 16 sp title barely
        // over a 14 sp line ("What's a model?"), so the title is the screen title's size
        // a no-break space keeps a name with its size on screen; read aloud and found, the
        // title has a plain space
        val spoken =
            if (NO_BREAK_SPACE in title) {
                Modifier.clearAndSetSemantics { titleText = AnnotatedString(title.replace(NO_BREAK_SPACE, ' ')) }
            } else {
                Modifier
            }
        Box(spoken) { Text(title, color = colors.ink, style = DemoType.screenTitle) }
        Column(
            Modifier
                .weight(1f, fill = false)
                .edgeFade({ scroll.canScrollForward })
                .verticalScroll(scroll)
                .testTag(DsTags.DIALOG_SCROLL),
            verticalArrangement = Arrangement.spacedBy(Gap.Block.size),
        ) {
            Text(text, color = colors.muted, style = DemoType.bodyMedium)
            content?.invoke()
        }
        DialogButtons(confirmLabel, onConfirm, dismissLabel, onDismiss, danger)
    }
}

/** The dialog's two buttons, one height: side by side at the end, or stacked full width at large font. */
@Composable
private fun DialogButtons(
    confirmLabel: String,
    onConfirm: () -> Unit,
    dismissLabel: String?,
    onDismiss: () -> Unit,
    danger: Boolean,
) {
    val confirm: @Composable (Modifier) -> Unit = { m ->
        if (danger) {
            QuietButton(confirmLabel, onConfirm, m, bordered = true, destructive = true, maxLines = Int.MAX_VALUE)
        } else {
            PrimaryButton(confirmLabel, onConfirm, m)
        }
    }
    val dismiss: @Composable (Modifier) -> Unit = { m ->
        if (dismissLabel != null) QuietButton(dismissLabel, onDismiss, m, bordered = true, maxLines = Int.MAX_VALUE)
    }
    if (isLargeText()) {
        Column(
            Modifier.fillMaxWidth().padding(top = DemoSpacing.compact),
            verticalArrangement = Arrangement.spacedBy(DemoSpacing.block),
        ) {
            confirm(Modifier.fillMaxWidth())
            dismiss(Modifier.fillMaxWidth())
        }
    } else {
        Row(
            Modifier.fillMaxWidth().padding(top = DemoSpacing.compact),
            horizontalArrangement = Arrangement.spacedBy(DemoSpacing.block, Alignment.End),
        ) {
            dismiss(Modifier)
            confirm(Modifier)
        }
    }
}

/** One of the sheet's four facts ("Thinking", "Can think first, or not"): a plain card with a title and a line. */
@Composable
fun FactCard(title: String, line: String, modifier: Modifier = Modifier) {
    val colors = LocalDemoColors.current
    DemoCard(modifier = modifier) {
        Text(title, color = colors.ink, style = DemoType.label)
        Text(line, color = colors.muted, style = DemoType.bodySmall)
    }
}

private const val NO_BREAK_SPACE = '\u00A0'
