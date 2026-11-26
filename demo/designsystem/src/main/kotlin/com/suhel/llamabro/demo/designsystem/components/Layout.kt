package com.suhel.llamabro.demo.designsystem.components

import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.AnchoredDraggableDefaults
import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.foundation.gestures.DraggableAnchors
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.anchoredDraggable
import androidx.compose.foundation.gestures.animateTo
import androidx.compose.foundation.gestures.snapTo
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.suhel.llamabro.demo.designsystem.theme.DemoColors
import com.suhel.llamabro.demo.designsystem.theme.DemoMotion
import com.suhel.llamabro.demo.designsystem.theme.DemoShapes
import com.suhel.llamabro.demo.designsystem.theme.DemoSize
import com.suhel.llamabro.demo.designsystem.theme.DemoSpacing
import com.suhel.llamabro.demo.designsystem.theme.DemoType
import com.suhel.llamabro.demo.designsystem.theme.LocalDemoColors
import com.suhel.llamabro.demo.designsystem.theme.LocalGlass
import com.suhel.llamabro.demo.designsystem.theme.animationsDisabled
import com.suhel.llamabro.demo.designsystem.theme.scrim
import kotlin.math.roundToInt

/**
 * A screen's top bar, 64 dp (Material's small app bar): an optional [start] (menu, back), the [title] taking the rest,
 * an optional [end] (new chat, the download ring). Its actions are glass chips you can see (D148), so the bar keeps
 * the page's 16 dp gutter, not Material's 4 dp for a bare icon: a chip's edge, or a plain title's first letter, lines
 * up with the cards and the fields under it; the title keeps 12 dp from a chip beside it. [divider] draws the hairline
 * under it.
 */
@Composable
fun TopBar(
    title: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    start: (@Composable () -> Unit)? = null,
    end: (@Composable RowScope.() -> Unit)? = null,
    divider: Boolean = false,
) {
    val colors = LocalDemoColors.current
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = DemoSize.topBar)
            .then(
                if (divider) {
                    Modifier.drawBehind {
                        val stroke = DemoSize.hairline.toPx()
                        val y = size.height - stroke / 2
                        drawLine(colors.hairline, Offset(0f, y), Offset(size.width, y), strokeWidth = stroke)
                    }
                } else {
                    Modifier
                },
            ).padding(horizontal = DemoSpacing.gutter),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // on a floating bar its actions and a title that opens something are chips of glass (D148)
        CompositionLocalProvider(LocalGlassChips provides true) {
            if (start != null) start()
            Box(
                Modifier.weight(1f).padding(
                    start = if (start != null) DemoSpacing.compact else 0.dp,
                    end = if (end != null) DemoSpacing.compact else 0.dp,
                ),
            ) { title() }
            if (end != null) end()
        }
    }
}

/**
 * The title of a [TopBar]: [title] ("Qwen3.5 0.8B", "Portfolio bio") with an optional [subtitle] ("On this phone",
 * "Gemma 3 4B"). [chevron] adds the ⌄ that says it opens something (the model switcher); [onClick] makes it tappable.
 */
@Composable
fun TopBarTitle(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    chevron: Boolean = false,
    onClick: (() -> Unit)? = null,
    /** The Models page's title (D123): light and a step larger, naming the page without weight. */
    light: Boolean = false,
    /** How many lines the title may wrap to before it is cut: 2 for a model's name, which must be read whole (D150). */
    lines: Int = 1,
    /**
     * Small (D159): the title in [DemoType.titleSmall] and the subtitle, dimmer, in [DemoType.bodySmall], each on one
     * line, the subtitle on two at a large font, so a model's name under a chat's is still read whole.
     */
    compact: Boolean = false,
) {
    val colors = LocalDemoColors.current
    // on a floating bar a title that opens something is a raised pill of its own (D148); a page's name is just text
    val backdrop = LocalBackdrop.current.takeIf { onClick != null && LocalGlassChips.current }
    val chip = backdrop != null
    val presses = if (chip) remember { MutableInteractionSource() } else null
    Column(
        modifier
            .chipGlass(backdrop, LocalGlass.current.chip, presses)
            .clip(if (chip) CircleShape else DemoShapes.card)
            .then(if (onClick != null) Modifier.glassClickable(presses, glass = chip, onClick = onClick) else Modifier)
            .heightIn(min = DemoSize.target)
            .padding(vertical = DemoSpacing.inline)
            .then(if (chip) Modifier.padding(horizontal = DemoSpacing.item) else Modifier),
        verticalArrangement = Arrangement.Center,
    ) {
        val chevronOnTitle = chevron && subtitle == null
        Row(verticalAlignment = Alignment.CenterVertically) {
            // a model's name wraps rather than lose its end (always where [lines] allows, at large font anywhere): the
            // name is never what gets cut first
            Text(
                title,
                Modifier.weight(1f, fill = false),
                color = colors.ink,
                style =
                    when {
                        compact -> DemoType.titleSmall
                        light -> DemoType.screenTitleLight
                        else -> DemoType.screenTitle
                    },
                maxLines = maxOf(lines, if (isLargeText()) 2 else 1),
                overflow = TextOverflow.Ellipsis,
            )
            if (chevronOnTitle) {
                Icon(
                    DemoIcons.ChevronDown,
                    contentDescription = null,
                    tint = colors.ink,
                    modifier = Modifier.size(DemoSize.iconSmall),
                )
            }
        }
        if (subtitle != null) TopBarSubtitle(subtitle, chevron, compact)
    }
}

/** A [TopBarTitle]'s second line, dimmer, with the chevron after it when it has one; [compact] as there (D159). */
@Composable
private fun TopBarSubtitle(subtitle: String, chevron: Boolean, compact: Boolean) {
    val colors = LocalDemoColors.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            subtitle,
            Modifier.weight(1f, fill = false),
            color = colors.muted,
            style = if (compact) DemoType.bodySmall else DemoType.bodyMedium,
            maxLines = if (compact && isLargeText()) 2 else 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (chevron) {
            Icon(
                DemoIcons.ChevronDown,
                contentDescription = null,
                tint = colors.muted,
                modifier = Modifier.size(DemoSize.iconSmall),
            )
        }
    }
}

/** One of the chat list layout's bottom tabs. */
@Immutable
data class BottomTab(val label: String, val icon: ImageVector)

/**
 * The chat list layout's bottom tabs (Chats, Models, Settings), floating over the page's bottom (D148): a pill of glass
 * on the gutter, above the navigation bar, over the page that is [backdrop] (the screens under it, blurred under the
 * tabs' layer and bent through the pill); the selected one's icon sits in a soft accent pill.
 */
@Composable
fun BottomTabs(
    tabs: List<BottomTab>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    backdrop: Backdrop? = null,
) {
    val colors = LocalDemoColors.current
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
        FloatingLayer(Edge.Bottom, colors.surface, backdrop, overKeyboard = false) {
            Row(
                Modifier
                    .widthIn(max = DemoSize.contentMaxWidth)
                    .fillMaxWidth()
                    .padding(horizontal = DemoSpacing.gutter, vertical = DemoSpacing.block)
                    .glass(backdrop, LocalGlass.current.panel)
                    .clip(CircleShape)
                    .height(DemoSize.navBar)
                    .selectableGroup(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                tabs.forEachIndexed { index, tab -> Tab(tab, index == selected) { onSelect(index) } }
            }
        }
    }
}

@Composable
private fun RowScope.Tab(tab: BottomTab, on: Boolean, onSelect: () -> Unit) {
    val colors = LocalDemoColors.current
    Column(
        Modifier
            .weight(1f)
            .fillMaxHeight()
            .selectable(selected = on, role = Role.Tab, onClick = onSelect),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            Modifier
                .size(width = DemoSize.navIndicatorWidth, height = DemoSize.navIndicatorHeight)
                .then(if (on) Modifier.background(colors.accentSoft, CircleShape) else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                tab.icon,
                contentDescription = null,
                tint = if (on) colors.accent else colors.muted,
                modifier = Modifier.size(DemoSize.icon),
            )
        }
        Text(
            tab.label,
            Modifier.padding(top = DemoSpacing.inline),
            color = if (on) colors.ink else colors.muted,
            style = if (on) DemoType.labelSmall else DemoType.bodySmall,
        )
    }
}

/** The two app layouts of demo design §4.2. */
enum class LayoutKind { Sidebar, ChatList }

/** A layout choice in Settings › Appearance: a small drawing of the layout over its [title] and [subtitle]. */
@Composable
fun LayoutPreviewCard(
    kind: LayoutKind,
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalDemoColors.current
    val shape = DemoShapes.card
    Column(
        modifier
            .softShadow(shape, colors)
            .clip(shape)
            .background(colors.surface)
            .border(DemoSize.selectedStroke, if (selected) colors.accent else colors.surface, shape)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(DemoSpacing.item),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(DemoSize.LayoutDrawing.height)
                .clip(DemoShapes.chip)
                .background(colors.canvas),
        ) {
            when (kind) {
                LayoutKind.Sidebar -> {
                    Box(
                        Modifier
                            .fillMaxHeight()
                            .weight(0.34f)
                            .alpha(0.7f)
                            .background(colors.hairline),
                    )
                    Box(Modifier.fillMaxHeight().weight(0.66f), contentAlignment = Alignment.Center) {
                        Box(Modifier.size(DemoSize.LayoutDrawing.dot).background(colors.accentSoft, CircleShape))
                    }
                }

                LayoutKind.ChatList -> {
                    Column(
                        Modifier.fillMaxSize().padding(DemoSpacing.block),
                        verticalArrangement =
                            Arrangement.spacedBy(
                                DemoSize.LayoutDrawing.lineGap,
                                Alignment.CenterVertically,
                            ),
                    ) {
                        repeat(4) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    Modifier
                                        .size(
                                            DemoSize.LayoutDrawing.avatar,
                                        ).background(colors.accentSoft, CircleShape),
                                )
                                Box(
                                    Modifier
                                        .padding(start = DemoSpacing.block)
                                        .weight(1f)
                                        .height(DemoSize.LayoutDrawing.line)
                                        .background(colors.hairline, CircleShape),
                                )
                            }
                        }
                    }
                }
            }
        }
        Text(
            title,
            Modifier.padding(top = DemoSpacing.block),
            color = colors.ink,
            style = DemoType.title,
        )
        Text(subtitle, color = colors.muted, style = DemoType.bodyMedium)
    }
}

/**
 * The Sidebar layout's drawer (D145, D148): the page slides right to show [drawerContent] beneath it, which holds still
 * on the [DemoColors.panel] ground, a step lighter than the page, coming into focus from a blur and a touch smaller as
 * the page slides off it; the page casts a soft shadow on it and is dimmed with
 * the theme's scrim, and a tap on it closes the drawer. A drag on the page opens and closes it. [open] says whether it
 * is shown; the user's drag or that tap reports through [onOpenChange]. Closed, the drawer is not composed, so neither
 * a screen reader nor a test finds it under the page.
 */
@Composable
fun DemoDrawer(
    open: Boolean,
    onOpenChange: (Boolean) -> Unit,
    drawerContent: @Composable ColumnScope.() -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val colors = LocalDemoColors.current
    val still = animationsDisabled()
    BoxWithConstraints(modifier.fillMaxSize().background(colors.panel)) {
        val width = with(LocalDensity.current) { minOf(maxWidth, DemoSize.drawerWidth).toPx() }
        val state = remember { AnchoredDraggableState(if (open) DrawerValue.Open else DrawerValue.Closed) }
        SideEffect {
            state.updateAnchors(
                DraggableAnchors {
                    DrawerValue.Closed at 0f
                    DrawerValue.Open at width
                },
            )
        }
        val reportOpen by rememberUpdatedState(onOpenChange)
        LaunchedEffect(open) {
            val target = if (open) DrawerValue.Open else DrawerValue.Closed
            if (still) state.snapTo(target) else state.animateTo(target, tween(DemoMotion.STANDARD_MILLIS))
        }
        LaunchedEffect(state) { snapshotFlow { state.settledValue }.collect { reportOpen(it == DrawerValue.Open) } }
        val shown by remember { derivedStateOf { state.offset.let { !it.isNaN() && it > 0f } } }
        if (shown || state.targetValue == DrawerValue.Open) {
            DrawerPanel(state, width, still, drawerContent)
        }
        Box(
            Modifier
                .fillMaxSize()
                .offset { IntOffset(state.offset.takeUnless { it.isNaN() }?.roundToInt() ?: 0, 0) }
                .then(if (shown) Modifier.softShadow(RectangleShape, colors, DemoSize.elevationRaised) else Modifier)
                .background(colors.canvas)
                .anchoredDraggable(state, Orientation.Horizontal, flingBehavior = drawerFling(state)),
        ) {
            content()
            if (shown) {
                // the theme's scrim, as the sheets use: the ink would lay a light haze over the page in dark (F131)
                Box(
                    Modifier
                        .matchParentSize()
                        .drawBehind { drawRect(colors.scrim, alpha = (state.offset / width).coerceIn(0f, 1f)) }
                        .clickable(interactionSource = null, indication = null) { reportOpen(false) }
                        .semantics { contentDescription = "Close the menu" },
                )
            }
        }
    }
}

/**
 * The drawer's column, [width] wide: it comes into focus as the page slides off it ([state]), from a blur and a touch
 * smaller, growing from its edge; [still] (animations off) leaves out the blur.
 */
@Composable
private fun DrawerPanel(
    state: AnchoredDraggableState<DrawerValue>,
    width: Float,
    still: Boolean,
    content: @Composable ColumnScope.() -> Unit,
) {
    val density = LocalDensity.current
    val focus = with(density) { DemoSize.drawerFocusBlur.toPx() }
    Column(
        Modifier
            .fillMaxHeight()
            .width(with(density) { width.toDp() })
            .graphicsLayer {
                val open = (state.offset / width).takeUnless { it.isNaN() }?.coerceIn(0f, 1f) ?: 1f
                val scale = DRAWER_START_SCALE + (1f - DRAWER_START_SCALE) * open
                scaleX = scale
                scaleY = scale
                transformOrigin = TransformOrigin(0f, 0.5f)
                val blur = focus * (1f - open)
                renderEffect = if (blur >= 0.5f && !still) BlurEffect(blur, blur, TileMode.Decal) else null
            },
        content = content,
    )
}

/** How small the drawer starts as it opens, before it grows to its size with the slide (D148). */
private const val DRAWER_START_SCALE = 0.96f

@Composable
private fun drawerFling(state: AnchoredDraggableState<DrawerValue>) =
    AnchoredDraggableDefaults.flingBehavior(state, animationSpec = tween(DemoMotion.STANDARD_MILLIS))

/**
 * The head of the Sidebar drawer (D126): as tall as the chat's [TopBar] ([DemoSize.topBar], D145), so the two titles
 * share a line at any font scale; the app's [title] in the quiet serif on the 24 dp line the history's text
 * starts on, and at the end its one action, a 48 dp icon in the ink ([actionIcon], spoken as [actionLabel]: New chat),
 * whose glyph ends on the same 24 dp line on the right. A heading for a screen reader.
 */
@Composable
fun DrawerHead(
    title: String,
    actionIcon: ImageVector,
    actionLabel: String,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = DemoSize.topBar)
            .padding(start = DemoSpacing.section, end = DemoSpacing.compact),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            Modifier.weight(1f).semantics { heading() },
            color = LocalDemoColors.current.ink,
            style = DemoType.sidebarTitle,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        CompositionLocalProvider(LocalGlassChips provides true) { IconAction(actionIcon, actionLabel, onAction) }
    }
}

/**
 * The foot of the Sidebar drawer (D126): a hairline, then its [content], the labelled places ([NavRow]s: Models,
 * Settings) that replaced a row of bare icons, so they read as places to go and not as mystery glyphs. Inset 12 dp
 * like the history's rows, so their text starts on the same line.
 */
@Composable
fun DrawerFoot(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = DemoSpacing.compact)
            .padding(top = DemoSpacing.block, bottom = DemoSpacing.item),
    ) {
        Box(Modifier.fillMaxWidth().height(DemoSize.hairline).background(LocalDemoColors.current.hairline))
        Spacer(Modifier.height(DemoSpacing.block))
        content()
    }
}
