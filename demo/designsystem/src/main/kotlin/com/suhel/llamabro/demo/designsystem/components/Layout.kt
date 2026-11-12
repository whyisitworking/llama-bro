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
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.suhel.llamabro.demo.designsystem.theme.DemoShapes
import com.suhel.llamabro.demo.designsystem.theme.DemoSpacing
import com.suhel.llamabro.demo.designsystem.theme.DemoType
import com.suhel.llamabro.demo.designsystem.theme.LocalDemoColors

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
            .heightIn(min = 64.dp)
            .then(
                if (divider) {
                    Modifier.drawBehind {
                        val y = size.height - 0.5.dp.toPx()
                        drawLine(colors.hairline, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.dp.toPx())
                    }
                } else {
                    Modifier
                },
            ).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (start != null) start()
        Box(Modifier.weight(1f).padding(horizontal = 12.dp)) { title() }
        if (end != null) end()
    }
}

/**
 * The title of a [TopBar]: [title] ("Swift", "Portfolio bio") with an optional [subtitle] ("On this phone",
 * "Writer"). [chevron] adds the ⌄ that says it opens something (the model switcher); [onClick] makes it tappable.
 */
@Composable
fun TopBarTitle(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    chevron: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val colors = LocalDemoColors.current
    Column(
        modifier
            .clip(RoundedCornerShape(10.dp))
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .heightIn(min = 48.dp)
            .padding(vertical = 4.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        val chevronOnTitle = chevron && subtitle == null
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                title,
                Modifier.weight(1f, fill = false),
                color = colors.ink,
                style = DemoType.screenTitle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (chevronOnTitle) {
                Icon(
                    DemoIcons.ChevronDown,
                    contentDescription = null,
                    tint = colors.ink,
                    modifier = Modifier.size(20.dp),
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
                        modifier = Modifier.size(16.dp),
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
            .drawBehind { drawLine(colors.hairline, Offset(0f, 0f), Offset(size.width, 0f), strokeWidth = 1.dp.toPx()) }
            .navigationBarsPadding()
            .height(80.dp)
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
                        .then(if (on) Modifier.background(colors.accentSoft, RoundedCornerShape(12.dp)) else Modifier)
                        .padding(horizontal = 20.dp, vertical = 4.dp),
                ) {
                    Icon(
                        tab.icon,
                        contentDescription = null,
                        tint = if (on) colors.accent else colors.muted,
                        modifier = Modifier.size(24.dp),
                    )
                }
                Text(
                    tab.label,
                    Modifier.padding(top = 4.dp),
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
            .border(2.dp, if (selected) colors.accent else colors.surface, shape)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(DemoSpacing.item),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(120.dp)
                .clip(RoundedCornerShape(10.dp))
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
                        Box(Modifier.size(18.dp).background(colors.accentSoft, CircleShape))
                    }
                }

                LayoutKind.ChatList -> {
                    Column(
                        Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(9.dp),
                    ) {
                        repeat(4) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(12.dp).background(colors.accentSoft, CircleShape))
                                Box(
                                    Modifier
                                        .padding(
                                            start = 6.dp,
                                        ).weight(1f)
                                        .height(6.dp)
                                        .background(colors.hairline, RoundedCornerShape(3.dp)),
                                )
                            }
                        }
                    }
                }
            }
        }
        Text(
            title,
            Modifier.padding(top = 8.dp),
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
            ModalDrawerSheet(
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
        scrimColor = colors.ink.copy(alpha = 0.32f),
        content = content,
    )
}
