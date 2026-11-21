package com.suhel.llamabro.demo.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import com.suhel.llamabro.demo.designsystem.theme.DemoShapes
import com.suhel.llamabro.demo.designsystem.theme.DemoSize
import com.suhel.llamabro.demo.designsystem.theme.DemoSpacing
import com.suhel.llamabro.demo.designsystem.theme.DemoType
import com.suhel.llamabro.demo.designsystem.theme.LocalDemoColors
import com.suhel.llamabro.demo.designsystem.theme.scrim

/**
 * A screen's top bar, 64 dp (Material's small app bar): an optional [start] (menu, back, 48 dp at 4 dp from the
 * edge), the [title] taking the rest, an optional [end] (new chat, the download ring). The title starts 16 dp from the
 * edge, or 64 dp when there is a [start], as Material's does. [divider] draws the hairline under it.
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
            ).padding(horizontal = DemoSpacing.inline),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (start != null) start()
        Box(Modifier.weight(1f).padding(horizontal = DemoSpacing.compact)) { title() }
        if (end != null) end()
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
) {
    val colors = LocalDemoColors.current
    Column(
        modifier
            .clip(DemoShapes.card)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .heightIn(min = DemoSize.target)
            .padding(vertical = DemoSpacing.inline),
        verticalArrangement = Arrangement.Center,
    ) {
        val chevronOnTitle = chevron && subtitle == null
        Row(verticalAlignment = Alignment.CenterVertically) {
            // at large font a model's name wraps rather than lose its end: the name is never what gets cut first
            Text(
                title,
                Modifier.weight(1f, fill = false),
                color = colors.ink,
                style = if (light) DemoType.screenTitleLight else DemoType.screenTitle,
                maxLines = if (isLargeText()) 2 else 1,
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
        if (subtitle != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    subtitle,
                    color = colors.muted,
                    style = DemoType.bodyMedium,
                    maxLines = 1,
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
    }
}

/** One of the chat list layout's bottom tabs. */
@Immutable
data class BottomTab(val label: String, val icon: ImageVector)

/** The chat list layout's bottom tabs (Chats, Models, Settings): the selected one's icon sits in a soft accent pill. */
@Composable
fun BottomTabs(tabs: List<BottomTab>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val colors = LocalDemoColors.current
    Row(
        modifier
            .fillMaxWidth()
            .background(colors.surface)
            .drawBehind {
                drawLine(
                    colors.hairline,
                    Offset(0f, 0f),
                    Offset(size.width, 0f),
                    strokeWidth = DemoSize.hairline.toPx(),
                )
            }.navigationBarsPadding()
            .height(DemoSize.navBar)
            .selectableGroup(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        tabs.forEachIndexed { index, tab ->
            val on = index == selected
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .selectable(selected = on, role = Role.Tab, onClick = { onSelect(index) }),
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
 * The Sidebar layout's drawer: [drawerContent] slides over [content] from the left edge, on the surface colour, with
 * the scrim of the theme. [open] says whether it is shown; the user's swipe or a tap on the scrim reports through
 * [onOpenChange].
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
    val state =
        androidx.compose.material3.rememberDrawerState(
            if (open) DrawerValue.Open else DrawerValue.Closed,
        )
    LaunchedEffect(open) { if (open) state.open() else state.close() }
    LaunchedEffect(state) {
        androidx.compose.runtime
            .snapshotFlow { state.isOpen }
            .collect { onOpenChange(it) }
    }
    ModalNavigationDrawer(
        drawerContent = {
            // the sheet is as wide as its content (the sidebar's 320 dp), not Material's 360 dp with a blank band
            ModalDrawerSheet(
                modifier = Modifier.drawerWidth(),
                drawerContainerColor = colors.canvas,
                drawerContentColor = colors.ink,
                drawerShape = RectangleShape,
                windowInsets =
                    androidx.compose.foundation.layout
                        .WindowInsets(0, 0, 0, 0),
            ) { Column(Modifier.fillMaxHeight(), content = drawerContent) }
        },
        modifier = modifier,
        drawerState = state,
        // the theme's scrim, as the sheets use: the ink would lay a light haze over the page in dark (F131)
        scrimColor = colors.scrim,
        content = content,
    )
}

/**
 * The head of the Sidebar drawer (D126): the app's [title] in the quiet serif on the 24 dp line the history's text
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
            .padding(start = DemoSpacing.section, end = DemoSpacing.compact)
            .padding(vertical = DemoSpacing.inline),
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
        IconAction(actionIcon, actionLabel, onAction)
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
