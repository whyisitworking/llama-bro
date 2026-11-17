package com.suhel.llamabro.demo.ui.shell

import androidx.activity.compose.LocalActivity
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.core.graphics.drawable.toDrawable
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.suhel.llamabro.demo.AppContainer
import com.suhel.llamabro.demo.data.AppLayout
import com.suhel.llamabro.demo.designsystem.components.BottomTabs
import com.suhel.llamabro.demo.designsystem.components.DemoDrawer
import com.suhel.llamabro.demo.designsystem.components.DemoIcons
import com.suhel.llamabro.demo.designsystem.components.EmptyLine
import com.suhel.llamabro.demo.designsystem.components.IconAction
import com.suhel.llamabro.demo.designsystem.components.TopBar
import com.suhel.llamabro.demo.designsystem.theme.Dark
import com.suhel.llamabro.demo.designsystem.theme.DemoTheme
import com.suhel.llamabro.demo.designsystem.theme.Light
import com.suhel.llamabro.demo.designsystem.theme.LocalDemoColors
import com.suhel.llamabro.demo.designsystem.theme.ThemeMode
import com.suhel.llamabro.demo.ui.firstrun.FirstRunRoute
import com.suhel.llamabro.demo.ui.models.GetModelsRoute
import com.suhel.llamabro.demo.ui.models.LibraryRoute
import com.suhel.llamabro.demo.ui.settings.AboutScreen
import com.suhel.llamabro.demo.ui.settings.AppearanceScreen
import com.suhel.llamabro.demo.ui.settings.DefaultModelScreen
import com.suhel.llamabro.demo.ui.settings.InstructionsScreen
import com.suhel.llamabro.demo.ui.settings.MemoryScreen
import com.suhel.llamabro.demo.ui.settings.SamplingModelScreen
import com.suhel.llamabro.demo.ui.settings.SamplingModelsScreen
import com.suhel.llamabro.demo.ui.settings.SettingsHome
import com.suhel.llamabro.demo.ui.settings.SettingsRoutes
import com.suhel.llamabro.demo.ui.settings.SettingsViewModel
import com.suhel.llamabro.demo.ui.settings.TokenScreen
import com.suhel.llamabro.hub.HfModel

/**
 * What a chat screen needs from the shell around it: which layout it is in and how to open the drawer, go back, start a
 * chat.
 */
class ChatChrome(
    val layout: AppLayout,
    val onMenu: () -> Unit,
    val onBack: () -> Unit,
    val onNewChat: () -> Unit,
    val onGetModels: () -> Unit = {},
    val onToken: () -> Unit = {},
    /** Settings › Memory, where the chip is chosen (D72). */
    val onMemory: () -> Unit = {},
    /** The id of the chat on screen once it has one: a new chat is made with its first message. */
    val onChatResolved: (Long) -> Unit = {},
)

private val settingsRoutes =
    SettingsRoutes(
        appearance = Routes.APPEARANCE,
        instructions = Routes.INSTRUCTIONS,
        defaultModel = Routes.DEFAULT_MODEL,
        token = Routes.TOKEN,
        storage = Routes.MODELS,
        memory = Routes.MEMORY,
        sampling = Routes.SAMPLING,
        about = Routes.ABOUT,
    )

/**
 * The whole app: the theme, then one of the two layouts (demo design §4.2) around one navigation graph. Switching
 * layout crossfades and keeps the open chat open. [chatScreen] is the chat screen; first run, Get models and the
 * library are supplied the same way.
 */
@Composable
fun AppShell(
    container: AppContainer,
    chatScreen: @Composable (chatId: Long, chrome: ChatChrome) -> Unit = { _, chrome -> EmptyChatScreen(chrome) },
    /** Called once the layout and the start screen are known (the settings and the cache scan): the splash ends. */
    onReady: () -> Unit = {},
) {
    val shell =
        viewModel {
            ShellViewModel(
                container.chats,
                container.settings,
                container.models,
                savedState = createSavedStateHandle(),
                deleteChat = { container.engine.delete(it) },
            )
        }
    val settings =
        viewModel {
            SettingsViewModel(
                container.settings,
                container.tokenStore,
                container.models,
                container.memory,
                container.sampling,
                container::deviceFacts,
            )
        }
    val theme by shell.theme.collectAsStateWithLifecycle()
    val layout by shell.layout.collectAsStateWithLifecycle()
    val firstRun by shell.startsWithFirstRun.collectAsStateWithLifecycle()
    SystemBars(theme)
    DemoTheme(theme) {
        Box(
            Modifier
                .fillMaxSize()
                .background(LocalDemoColors.current.canvas)
                // the benchmarks (UI Automator) find the screens by their tags
                .semantics { testTagsAsResourceId = true }
                .testTag("shell_root"),
        ) {
            // the start destination is decided once, when both the layout and first run are known
            val start = firstRun
            if (layout != null && start != null) {
                Shell(container, layout!!, start, shell, settings, chatScreen)
                SideEffect(onReady)
            }
        }
    }
}

@Composable
private fun Shell(
    container: AppContainer,
    layout: AppLayout,
    startsWithFirstRun: Boolean,
    shell: ShellViewModel,
    settings: SettingsViewModel,
    chatScreen: @Composable (Long, ChatChrome) -> Unit,
) {
    val nav = rememberNavController()
    val start =
        remember {
            when {
                startsWithFirstRun -> Routes.FIRST_RUN
                layout == AppLayout.Sidebar -> Routes.chat(Routes.NEW_CHAT_ID)
                else -> Routes.CHATS
            }
        }
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    var drawerOpen by rememberSaveable { mutableStateOf(false) }
    // a new chat is route 0 until its first message makes it: the chat tells the shell its id, and the drawer marks it
    var resolved by remember { mutableStateOf<Pair<String, Long>?>(null) }
    val currentChat =
        if (route == Routes.CHAT_PATTERN) {
            resolved?.takeIf { it.first == entry?.id }?.second ?: entry?.arguments?.getLong(Routes.CHAT_ARG)
        } else {
            null
        }

    // the chat list home only exists in the Chat list layout: switching to Sidebar from it opens a new chat
    LaunchedEffect(layout, route) {
        if (layout == AppLayout.Sidebar && route == Routes.CHATS) {
            nav.navigate(Routes.chat(Routes.NEW_CHAT_ID)) { popUpTo(Routes.CHATS) { inclusive = true } }
        }
    }

    Crossfade(layout, label = "layout", modifier = Modifier.testTag("layout_switch")) { current ->
        val go = { to: String -> nav.navigate(to) { launchSingleTop = true } }
        val openChat = { id: Long ->
            nav.navigate(Routes.chat(id)) {
                if (current == AppLayout.Sidebar) popUpTo(Routes.CHAT_PATTERN) { inclusive = true }
            }
        }
        // the chat on screen, deleted, gives way to a new one: its screen would otherwise go on writing to a chat
        // that is gone
        val deleteChat = { id: Long ->
            shell.delete(id)
            if (id == currentChat) openChat(Routes.NEW_CHAT_ID)
        }
        when (current) {
            AppLayout.Sidebar -> {
                DemoDrawer(
                    open = drawerOpen,
                    onOpenChange = { drawerOpen = it },
                    drawerContent = {
                        SidebarDrawer(
                            shell,
                            currentChat,
                            onNewChat = {
                                drawerOpen = false
                                openChat(Routes.NEW_CHAT_ID)
                            },
                            onOpenChat = {
                                drawerOpen = false
                                openChat(it)
                            },
                            onModels = {
                                drawerOpen = false
                                go(Routes.MODELS)
                            },
                            onSettings = {
                                drawerOpen = false
                                go(Routes.SETTINGS)
                            },
                            onDelete = deleteChat,
                        )
                    },
                ) {
                    Graph(
                        container,
                        nav,
                        start,
                        current,
                        shell,
                        settings,
                        chatScreen,
                        openChat,
                        deleteChat,
                        { entryId, id -> resolved = entryId to id },
                        { drawerOpen = true },
                    )
                }
            }

            AppLayout.ChatList -> {
                Column(Modifier.fillMaxSize()) {
                    Box(
                        Modifier.weight(1f),
                    ) {
                        Graph(
                            container,
                            nav,
                            start,
                            current,
                            shell,
                            settings,
                            chatScreen,
                            openChat,
                            deleteChat,
                            { _, _ -> },
                        ) {}
                    }
                    val tab =
                        when (route) {
                            Routes.CHATS -> 0
                            Routes.MODELS -> 1
                            Routes.SETTINGS -> 2
                            else -> null
                        }
                    if (tab != null) {
                        BottomTabs(ChatListTabs, tab, {
                            go(listOf(Routes.CHATS, Routes.MODELS, Routes.SETTINGS)[it])
                        }, Modifier.navigationBarsPadding())
                    }
                }
            }
        }
    }
}

@Composable
private fun Graph(
    container: AppContainer,
    nav: NavHostController,
    start: String,
    layout: AppLayout,
    shell: ShellViewModel,
    settings: SettingsViewModel,
    chatScreen: @Composable (Long, ChatChrome) -> Unit,
    openChat: (Long) -> Unit,
    deleteChat: (Long) -> Unit,
    onChatResolved: (entryId: String, chatId: Long) -> Unit,
    openDrawer: () -> Unit,
) {
    val back: () -> Unit = {
        if (layout == AppLayout.ChatList && nav.previousBackStackEntry == null) {
            // a chat opened under Sidebar has nothing beneath it; in Chat list, Back from it is the list
            nav.navigate(Routes.CHATS) { popUpTo(nav.graph.id) { inclusive = true } }
        } else {
            nav.popBackStack()
        }
    }
    val getModels = { query: String -> nav.navigate(Routes.getModels(query)) { launchSingleTop = true } }
    NavHost(nav, startDestination = start) {
        composable(Routes.FIRST_RUN) {
            FirstRunRoute(
                container,
                onOpenChat = { id -> nav.navigate(Routes.chat(id)) { popUpTo(Routes.FIRST_RUN) { inclusive = true } } },
                onSearch = getModels,
            )
        }
        composable(
            Routes.GET_MODELS_PATTERN,
            arguments =
                listOf(
                    navArgument(Routes.QUERY_ARG) {
                        type = NavType.StringType
                        defaultValue = ""
                    },
                ),
        ) { entry -> GetModelsRoute(container, entry.arguments?.getString(Routes.QUERY_ARG).orEmpty(), back) }
        composable(Routes.CHATS) {
            ChatListHome(shell, openChat, onNewChat = { openChat(Routes.NEW_CHAT_ID) }, onDelete = deleteChat)
        }
        composable(
            Routes.CHAT_PATTERN,
            arguments = listOf(navArgument(Routes.CHAT_ARG) { type = NavType.LongType }),
        ) { entry: NavBackStackEntry ->
            val id = entry.arguments?.getLong(Routes.CHAT_ARG) ?: 0L
            chatScreen(
                id,
                ChatChrome(
                    layout,
                    openDrawer,
                    back,
                    { openChat(Routes.NEW_CHAT_ID) },
                    { getModels("") },
                    { nav.navigate(Routes.TOKEN) { launchSingleTop = true } },
                    onMemory = { nav.navigate(Routes.MEMORY) { launchSingleTop = true } },
                    onChatResolved = { onChatResolved(entry.id, it) },
                ),
            )
        }
        composable(Routes.MODELS) { LibraryRoute(container, back, onGetMore = { getModels("") }) }
        composable(Routes.SETTINGS) {
            SettingsHome(back, onOpen = { nav.navigate(it) { launchSingleTop = true } }, routes = settingsRoutes)
        }
        composable(Routes.APPEARANCE) { AppearanceScreen(settings, back) }
        composable(Routes.INSTRUCTIONS) { InstructionsScreen(settings, back) }
        composable(Routes.DEFAULT_MODEL) { DefaultModelScreen(settings, back) }
        composable(Routes.TOKEN) { TokenScreen(settings, back) }
        composable(Routes.MEMORY) { MemoryScreen(settings, back) }
        composable(Routes.SAMPLING) {
            SamplingModelsScreen(settings, back) { key ->
                nav.navigate(Routes.samplingModel(key.toString())) { launchSingleTop = true }
            }
        }
        composable(
            Routes.SAMPLING_MODEL,
            arguments = listOf(navArgument(Routes.SAMPLING_KEY_ARG) { type = NavType.StringType }),
        ) { entry ->
            val key =
                entry.arguments
                    ?.getString(
                        Routes.SAMPLING_KEY_ARG,
                    )?.let { runCatching { HfModel.parse(it) }.getOrNull() }
            if (key != null) SamplingModelScreen(settings, key, back)
        }
        composable(Routes.ABOUT) { AboutScreen(back) }
    }
}

/** A chat with no messages yet: the greeting the design gives. */
@Composable
private fun EmptyChatScreen(chrome: ChatChrome) {
    Column(Modifier.fillMaxSize()) {
        TopBar(
            title = {},
            start = {
                if (chrome.layout == AppLayout.Sidebar) {
                    IconAction(DemoIcons.Menu, "Chats", onClick = chrome.onMenu)
                } else {
                    IconAction(DemoIcons.Back, "Back", onClick = chrome.onBack)
                }
            },
        )
        Box(Modifier.weight(1f).fillMaxSize(), contentAlignment = Alignment.Center) {
            EmptyLine("What's on your mind?")
        }
    }
}

/**
 * The status and navigation bar icons follow the app's own theme choice, not just the system's: a Light app on a dark
 * phone gets dark icons, so the clock stays readable. (`enableEdgeToEdge()` reads the system setting once; calling it
 * again from here leaked each activity's compose view, so only the icon appearance is set.)
 */
@Composable
private fun SystemBars(theme: ThemeMode) {
    val activity = LocalActivity.current ?: return
    val dark =
        when (theme) {
            ThemeMode.System -> androidx.compose.foundation.isSystemInDarkTheme()
            ThemeMode.Light -> false
            ThemeMode.Dark -> true
        }
    SideEffect {
        val controller =
            WindowCompat
                .getInsetsController(activity.window, activity.window.decorView)
        controller.isAppearanceLightStatusBars = !dark
        controller.isAppearanceLightNavigationBars = !dark
        // what shows behind the status bar and under a sheet's scrim is the window's own background, which follows the
        // phone's day or night setting: a dark app on a light phone would show a light strip, so it follows the app's
        // own choice
        activity.window.setBackgroundDrawable((if (dark) Dark.canvas else Light.canvas).toArgb().toDrawable())
    }
}
