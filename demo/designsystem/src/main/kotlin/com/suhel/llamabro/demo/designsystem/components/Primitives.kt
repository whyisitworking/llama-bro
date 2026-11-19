package com.suhel.llamabro.demo.designsystem.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.suhel.llamabro.demo.designsystem.theme.DemoMotion
import com.suhel.llamabro.demo.designsystem.theme.DemoSize
import com.suhel.llamabro.demo.designsystem.theme.DemoSpacing

/**
 * The widest a screen's content gets: Material's own cap for a sheet (640 dp). A phone is narrower, so nothing changes
 * there; on a tablet or a phone on its side the content is a readable column in the middle, its gutter measured from
 * the column's edge, and not a line of text 700 dp long.
 */
val ContentMaxWidth = DemoSize.contentMaxWidth

/**
 * The gaps a screen may use, by what they mean (the design system v2 spec, section 2). A screen names a [Gap] and never
 * a number, so the same relationship is the same distance on every screen: the gap *around* a group is larger than
 * the gap *inside* it, and a reader sees what belongs together.
 */
enum class Gap(internal val size: Dp) {
    /** No gap: rows of one list that carry their own height. */
    None(0.dp),

    /** Inside one unit: a title and its subtitle, a message and its meta line, a label and its field. */
    Inline(DemoSpacing.inline),

    /** Between the blocks of one unit: a reply's paragraph, list and code block; the cards of one list. */
    Block(DemoSpacing.block),

    /** Between units: two replies of one speaker, the parts of a card, the blocks of a sheet. */
    Item(DemoSpacing.item),

    /** Between sections, and between turns (the question and its answer). */
    Section(DemoSpacing.section),

    /** Around a hero or an empty state. */
    Hero(DemoSpacing.hero),
}

/** [content] stacked top to bottom with [gap] between the parts. */
@Composable
fun Stack(
    gap: Gap,
    modifier: Modifier = Modifier,
    horizontalAlignment: Alignment.Horizontal = Alignment.Start,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier, Arrangement.spacedBy(gap.size), horizontalAlignment, content)
}

/** [content] side by side with [gap] between the parts, centred on the cross axis unless told otherwise. */
@Composable
fun Inline(
    gap: Gap,
    modifier: Modifier = Modifier,
    verticalAlignment: Alignment.Vertical = Alignment.CenterVertically,
    content: @Composable RowScope.() -> Unit,
) {
    Row(modifier, Arrangement.spacedBy(gap.size), verticalAlignment, content)
}

/** A vertical gap of its own, where the parts either side are not in one [Stack]. */
@Composable
fun GapAbove(gap: Gap) {
    Spacer(Modifier.height(gap.size))
}

/**
 * A screen: the [topBar], then the body on the gutter with an [Gap.Item] above its first part and below its last,
 * its parts [Gap.Section] apart. The body scrolls unless [scrolls] is false (a screen that scrolls a list of its own
 * inside, or has nothing to scroll). The screen owns the gutter: a part never pads itself to reach it.
 */
@Composable
fun Screen(
    topBar: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    scrolls: Boolean = true,
    gap: Gap = Gap.Section,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        topBar()
        Column(
            Modifier
                .weight(1f)
                .widthIn(max = ContentMaxWidth)
                .fillMaxWidth()
                .gutterContainer()
                .then(if (scrolls) Modifier.verticalScroll(rememberScrollState()) else Modifier)
                .padding(horizontal = DemoSpacing.gutter)
                .padding(top = DemoSpacing.item, bottom = DemoSpacing.item)
                .navigationBarsPadding(),
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .then(if (scrolls) Modifier else Modifier.fillMaxHeight())
                    .gutterEdge(),
                Arrangement.spacedBy(gap.size),
                content = content,
            )
        }
    }
}

/** The tallest a list may be inside a sheet before it scrolls: about six rows of a model's files. */
fun Modifier.sheetListMaxHeight(): Modifier = heightIn(max = DemoSize.sheetListMaxHeight)

/**
 * A lazy list inside a screen that already has the gutter ([Screen]): its items [gap] apart, no edge padding of its
 * own. For a list too long to compose at once that sits under a field or a header.
 */
@Composable
fun LazyStack(gap: Gap, modifier: Modifier = Modifier, content: LazyListScope.() -> Unit) {
    LazyColumn(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(gap.size), content = content)
}

/**
 * A labelled section: the upper-case [label] (with an optional [action] at its end), [Gap.Inline] above its content,
 * and the content's parts [gap] apart. The label belongs to what follows it, so it sits closer to its own content than
 * to the section above: put sections in a [Stack] or a [Screen] with [Gap.Section] between them. The label starts where
 * the text inside the cards and rows starts ([SectionLabel]); on a sheet it is [Gap.Block] above its content.
 */
@Composable
fun Section(
    label: String,
    modifier: Modifier = Modifier,
    action: String? = null,
    onAction: (() -> Unit)? = null,
    gap: Gap = Gap.Block,
    content: @Composable ColumnScope.() -> Unit,
) {
    val labelGap = if (LocalOnSheet.current) Gap.Block else Gap.Inline
    Column(modifier.fillMaxWidth(), Arrangement.spacedBy(labelGap.size)) {
        SectionLabel(label, action = action, onAction = onAction)
        Column(Modifier.fillMaxWidth(), Arrangement.spacedBy(gap.size), content = content)
    }
}

/**
 * The bottom of a chat screen: the parts stacked a [Gap.Block] apart on the gutter, [Gap.Block] above and below, clear
 * of the navigation bar and the keyboard. The message bar and what sits above it (a limit, an edit line) are in it, so
 * they share the gutter with the messages and never stick out past them.
 */
@Composable
fun BottomBar(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
        Column(
            Modifier
                .widthIn(max = ContentMaxWidth)
                .fillMaxWidth()
                .gutterContainer()
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = DemoSpacing.gutter, vertical = DemoSpacing.block),
        ) {
            Column(Modifier.fillMaxWidth().gutterEdge(), Arrangement.spacedBy(Gap.Block.size), content = content)
        }
    }
}

/**
 * The messages of a chat: a lazy column on the gutter, the turns a [Gap.Section] apart, [Gap.Item] above the first and
 * below the last. Meta lines (time, speed) are inside a message, [Gap.Inline] from its text. Rows move with
 * [messageMotion].
 */
@Composable
fun MessageColumn(
    state: LazyListState,
    modifier: Modifier = Modifier,
    overlay: @Composable BoxScope.() -> Unit = {},
    content: LazyListScope.() -> Unit,
) {
    // the messages fade in when the list first shows (the first message of a new chat, a chat opening), since rows that
    // are there from the start do not fade in by themselves. A preview runs no effects: it starts shown
    val still = LocalInspectionMode.current
    val shown = remember { Animatable(if (still) 1f else 0f) }
    LaunchedEffect(shown) { shown.animateTo(1f, tween(DemoMotion.ENTER_MILLIS, easing = DemoMotion.enter)) }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            modifier
                .fillMaxHeight()
                .widthIn(max = ContentMaxWidth)
                .fillMaxWidth()
                .graphicsLayer { alpha = shown.value },
            state,
            contentPadding =
                PaddingValues(
                    start = DemoSpacing.gutter,
                    end = DemoSpacing.gutter,
                    top = DemoSpacing.item,
                    bottom = DemoSpacing.item,
                ),
            verticalArrangement = Arrangement.spacedBy(Gap.Section.size),
            content = content,
        )
        overlay()
    }
}

/**
 * A body centred in its screen, on the gutter: an empty chat's orb, line and chips; a notice. The parts are [gap]
 * apart.
 */
@Composable
fun Centered(modifier: Modifier = Modifier, gap: Gap = Gap.Section, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.fillMaxSize().padding(horizontal = DemoSpacing.gutter),
        Arrangement.spacedBy(gap.size, Alignment.CenterVertically),
        Alignment.CenterHorizontally,
        content = content,
    )
}

/** A column that scrolls, its parts a [gap] apart: a sheet's list of models. */
@Composable
fun ScrollStack(gap: Gap, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.verticalScroll(rememberScrollState()), Arrangement.spacedBy(gap.size), content = content)
}

/**
 * A home that is a header over a list of full-width rows (the Chat list layout): the [topBar]; the [header] on the
 * gutter, its parts a [Gap.Block] apart with [Gap.Item] under it; then the [body], which is not padded because its
 * rows (a [ChatRow], a [ListRow]) carry their own 16 dp. A [fab] floats at the bottom end, on the gutter.
 */
@Composable
fun ListHomeScreen(
    topBar: @Composable () -> Unit,
    header: @Composable ColumnScope.() -> Unit,
    modifier: Modifier = Modifier,
    fab: (@Composable () -> Unit)? = null,
    body: @Composable ColumnScope.() -> Unit,
) {
    Box(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            topBar()
            Column(Modifier.widthIn(max = ContentMaxWidth).fillMaxWidth().weight(1f)) {
                Column(Modifier.fillMaxWidth().gutterContainer().padding(horizontal = DemoSpacing.gutter)) {
                    Column(Modifier.fillMaxWidth().gutterEdge(), Arrangement.spacedBy(Gap.Block.size), content = header)
                }
                GapAbove(Gap.Item)
                body()
            }
        }
        if (fab != null) {
            Box(Modifier.align(Alignment.BottomEnd).padding(DemoSpacing.gutter)) { fab() }
        }
    }
}

/** The gutter on every side: for a part that sits alone on a canvas, such as a share card or a preview's frame. */
fun Modifier.gutterPadding(): Modifier = padding(DemoSpacing.gutter)

/** The widest the Sidebar layout's drawer gets: Material's 360 dp, less the strip of the screen left to tap away. */
fun Modifier.drawerWidth(): Modifier = widthIn(max = DemoSize.drawerWidth)
