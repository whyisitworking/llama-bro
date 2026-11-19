package com.suhel.llamabro.demo.designsystem.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.suhel.llamabro.demo.designsystem.R
import com.suhel.llamabro.demo.designsystem.theme.DemoColors
import com.suhel.llamabro.demo.designsystem.theme.DemoShapes
import com.suhel.llamabro.demo.designsystem.theme.DemoSize
import com.suhel.llamabro.demo.designsystem.theme.DemoSpacing
import com.suhel.llamabro.demo.designsystem.theme.DemoType
import com.suhel.llamabro.demo.designsystem.theme.LocalDemoColors
import com.suhel.llamabro.demo.designsystem.theme.scrim

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
 * A card: 12 dp corners on the surface (the canvas on a sheet), 16 dp of padding inside. A card on the canvas has the
 * soft shadow. [selected] outlines it 2 dp in the accent: the one signal that a card is chosen. [edgeLabel] ("Default",
 * "Recommended") is a small accent label on its own line at the top end, inside the card, so it never covers the
 * card's own text.
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
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = LocalDemoColors.current
    val onSheet = LocalOnSheet.current
    val shape = DemoShapes.card
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

/** The small accent label at a card's top end: "Default" in the library and switcher, "Recommended" on a quant. */
@Composable
fun EdgeChip(text: String, modifier: Modifier = Modifier) {
    val colors = LocalDemoColors.current
    Text(
        text,
        modifier
            .background(colors.accent, CircleShape)
            .heightIn(min = DemoSize.tag)
            .padding(horizontal = DemoSpacing.block)
            .wrapContentHeight(Alignment.CenterVertically),
        color = colors.onAccent,
        style = DemoType.labelSmall,
        maxLines = 1,
    )
}

/**
 * The app's bottom sheet: Material's modal sheet with the grab handle, 28 dp top corners, the surface colour and the
 * mockups' scrim, opened fully. The sheet owns the gutter: its parts are [Gap.Item] apart on the 16 dp edge and a part
 * never pads itself to reach it. Plain cards inside sit on the canvas colour.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DemoSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = LocalDemoColors.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    // a sheet whose content grows after it opens (a model sheet: "Looking at the sizes…" becomes the size cards) was
    // found parked at its first, short height with the new content cut off below the screen (F118): whenever the
    // content's height changes, expand again to the new height
    var contentHeight by remember { mutableIntStateOf(0) }
    LaunchedEffect(contentHeight) {
        if (contentHeight > 0) {
            withFrameNanos { } // let the sheet register the new height first, also while it is still opening
            // not while it is being dismissed: expanding then would cancel the hide and swallow the dismissal
            if (sheetState.targetValue != SheetValue.Hidden || !sheetState.isVisible) sheetState.expand()
        }
    }
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        sheetState = sheetState,
        shape = DemoShapes.sheet,
        containerColor = colors.surface,
        contentColor = colors.ink,
        tonalElevation = 0.dp,
        scrimColor = colors.scrim,
        dragHandle = { GrabHandle() },
    ) {
        SheetBody(content, Modifier.onSizeChanged { contentHeight = it.height })
    }
}

/**
 * The sheet's handle: Material's 32 x 4 dp bar centred in a 48 dp box with a label. Material makes the handle a
 * button that expands or dismisses the sheet, so the system's checks want 48 dp and a name; 48 dp is Material's own
 * handle area (22 dp above and below the bar).
 */
@Composable
private fun GrabHandle() {
    val colors = LocalDemoColors.current
    val description = stringResource(R.string.ds_drag_handle)
    Box(
        Modifier.size(DemoSize.target).semantics {
            contentDescription = description
        },
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

@Composable
private fun SheetBody(content: @Composable ColumnScope.() -> Unit, modifier: Modifier = Modifier) {
    CompositionLocalProvider(LocalOnSheet provides true) {
        Column(
            modifier
                .fillMaxWidth()
                .gutterContainer()
                .navigationBarsPadding()
                .padding(start = DemoSpacing.gutter, end = DemoSpacing.gutter, bottom = DemoSpacing.item),
        ) {
            Column(Modifier.fillMaxWidth().gutterEdge(), Arrangement.spacedBy(Gap.Item.size), content = content)
        }
    }
}

/**
 * The sheet as [DemoSheet] draws it, in place, without the window: for previews, screenshot tests and the gallery,
 * where a modal window can't be rendered.
 */
@Composable
fun SheetFrame(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val colors = LocalDemoColors.current
    Column(
        modifier.fillMaxWidth().clip(DemoShapes.sheet).background(colors.surface),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        GrabHandle()
        SheetBody(content)
    }
}

/**
 * A small question with two answers ("Remove Qwen3.5 0.8B? This frees 0.7 GB."): the surface card on the scrim, the
 * title, one plain line, a primary [confirmLabel] and, when there is a [dismissLabel], a quiet one beside it ("Keep").
 * The window is Material's; the card is [DialogCard].
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
) {
    BasicAlertDialog(onDismissRequest = onDismiss, modifier = modifier) {
        DialogCard(title, text, confirmLabel, onConfirm, dismissLabel, onDismiss)
    }
}

/** The dialog's card on its own, for previews, screenshot tests and the gallery, where a window can't be drawn. */
@Composable
fun DialogCard(
    title: String,
    text: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    dismissLabel: String?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalDemoColors.current
    Column(
        modifier
            .fillMaxWidth()
            .clip(DemoShapes.card)
            .background(colors.surface)
            .padding(DemoSpacing.section),
        verticalArrangement = Arrangement.spacedBy(Gap.Block.size),
    ) {
        Text(title, color = colors.ink, style = DemoType.title)
        Text(text, color = colors.muted, style = DemoType.body)
        Row(
            Modifier.fillMaxWidth().padding(top = DemoSpacing.compact),
            horizontalArrangement = Arrangement.spacedBy(DemoSpacing.block, Alignment.End),
        ) {
            if (dismissLabel != null) QuietButton(dismissLabel, onDismiss)
            PrimaryButton(confirmLabel, onConfirm)
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
