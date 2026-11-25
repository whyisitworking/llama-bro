package com.suhel.llamabro.demo.ui.shell

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
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
import com.suhel.llamabro.demo.data.AppLayout
import com.suhel.llamabro.demo.designsystem.components.BottomTabs
import com.suhel.llamabro.demo.designsystem.components.DemoDrawer
import com.suhel.llamabro.demo.designsystem.components.DemoIcons
import com.suhel.llamabro.demo.designsystem.components.EmptyLine
import com.suhel.llamabro.demo.designsystem.components.FloatingFrame
import com.suhel.llamabro.demo.designsystem.components.IconAction
import com.suhel.llamabro.demo.designsystem.components.LocalBottomOverlay
import com.suhel.llamabro.demo.designsystem.components.TopBar
import com.suhel.llamabro.demo.designsystem.components.backdropSource
import com.suhel.llamabro.demo.designsystem.components.rememberBackdrop
import com.suhel.llamabro.demo.designsystem.components.screenEnter
import com.suhel.llamabro.demo.designsystem.components.screenExit
import com.suhel.llamabro.demo.designsystem.theme.DarkDemoColors
import com.suhel.llamabro.demo.designsystem.theme.DemoTheme
import com.suhel.llamabro.demo.designsystem.theme.LightDemoColors
import com.suhel.llamabro.demo.designsystem.theme.LocalDemoColors
import com.suhel.llamabro.demo.designsystem.theme.LocalGlass
import com.suhel.llamabro.demo.designsystem.theme.ThemeMode
import com.suhel.llamabro.demo.designsystem.theme.animationsDisabled
import com.suhel.llamabro.demo.ui.Screens
import com.suhel.llamabro.demo.ui.models.ModelsRoute
import com.suhel.llamabro.demo.ui.settings.AboutScreen
import com.suhel.llamabro.demo.ui.settings.AppearanceScreen
import com.suhel.llamabro.demo.ui.settings.DefaultModelScreen
import com.suhel.llamabro.demo.ui.settings.GlassScreen
import com.suhel.llamabro.demo.ui.settings.InstructionsScreen
import com.suhel.llamabro.demo.ui.settings.MemoryScreen
import com.suhel.llamabro.demo.ui.settings.SamplingModelScreen
import com.suhel.llamabro.demo.ui.settings.SamplingModelsScreen
import com.suhel.llamabro.demo.ui.settings.SettingsHome
import com.suhel.llamabro.demo.ui.settings.SettingsRoutes
import com.suhel.llamabro.demo.ui.settings.SettingsViewModel
import com.suhel.llamabro.demo.ui.settings.TokenScreen
import com.suhel.llamabro.demo.ui.settings.VoiceScreen
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
    /** The model a new chat starts on, chosen on a model's card ("Chat with"); null for the usual one. */
    val startModel: HfModel? = null,
)

private val settingsRoutes =
    SettingsRoutes(
        appearance = Routes.APPEARANCE,
        instructions = Routes.INSTRUCTIONS,
        defaultModel = Routes.DEFAULT_MODEL,
        token = Routes.TOKEN,
        storage = Routes.models(),
        memory = Routes.MEMORY,
        sampling = Routes.SAMPLING,
        about = Routes.ABOUT,
        voice = Routes.VOICE,
    )

/**
 * The whole app: the theme, then one of the two layouts (demo design §4.2) around one navigation graph. Switching
 * layout crossfades and keeps the open chat open. [chatScreen] is the chat screen.
 */
@Composable
fun AppShell(
    screens: Screens,
    chatScreen: @Composable (chatId: Long, chrome: ChatChrome) -> Unit = { _, chrome -> EmptyChatScreen(chrome) },
    /** Called once the layout and the start screen are known (the settings and the cache scan): the splash ends. */
    onReady: () -> Unit = {},
) {
    val shell = viewModel { screens.shellViewModel(createSavedStateHandle()) }
    val settings = viewModel { screens.settingsViewModel() }
    val theme by shell.theme.collectAsStateWithLifecycle()
    val layout by shell.layout.collectAsStateWithLifecycle()
    val firstRun by shell.startsWithFirstRun.collectAsStateWithLifecycle()
    val seen by shell.seenFirstRun.collectAsStateWithLifecycle()
    SystemBars(theme)
    // the glass as the person tuned it on Settings › Appearance › Glass (D152); null until it is read
    val glass by shell.glass.collectAsStateWithLifecycle()
    DemoTheme(theme) {
        CompositionLocalProvider(LocalGlass provides (glass ?: LocalGlass.current)) {
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
                val shown = layout
                // first run's seen flag too: the Models page's intro is drawn from it on the first frame; and the
                // glass, so a tuned glass never flickers in after a frame of the default
                val read = seen != null && glass != null
                if (shown != null && start != null && read) {
                    Shell(screens, shown, start, shell, settings, chatScreen)
                    SideEffect(onReady)
                }
            }
        }
    }
}

@Composable
private fun Shell(
    screens: Screens,
    layout: AppLayout,
    startsWithFirstRun: Boolean,
    shell: ShellViewModel,
    settings: SettingsViewModel,
    chatScreen: @Composable (Long, ChatChrome) -> Unit,
) {
    val nav = rememberNavController()
    val start = remember { Routes.start(startsWithFirstRun, layout) }
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
                // Back closes an open drawer before it leaves the screen
                BackHandler(enabled = drawerOpen) { drawerOpen = false }
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
                                go(Routes.models())
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
                        screens,
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
                val tab =
                    when (route) {
                        Routes.CHATS -> 0
                        Routes.MODELS -> 1
                        Routes.SETTINGS -> 2
                        else -> null
                    }
                // the tabs float over the page's bottom (D148): the page scrolls on under them, its end kept clear
                var tabsHeight by remember { mutableStateOf(0.dp) }
                val density = LocalDensity.current
                // the screens are the tabs' page: blurred under their layer and bent through their glass
                val screensPage = rememberBackdrop()
                Box(Modifier.fillMaxSize()) {
                    CompositionLocalProvider(LocalBottomOverlay provides if (tab != null) tabsHeight else 0.dp) {
                        Box(Modifier.fillMaxSize().backdropSource(screensPage)) {
                            Graph(
                                screens,
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
                    }
                    if (tab != null) {
                        BottomTabs(
                            ChatListTabs,
                            tab,
                            { go(listOf(Routes.CHATS, Routes.models(), Routes.SETTINGS)[it]) },
                            Modifier
                                .align(Alignment.BottomCenter)
                                .onSizeChanged { tabsHeight = with(density) { it.height.toDp() } },
                            backdrop = screensPage,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Graph(
    screens: Screens,
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
    val models = { query: String -> nav.navigate(Routes.models(query)) { launchSingleTop = true } }
    // a model card's "Chat with": a new chat on that model, in place of the chat the Sidebar shows, as New chat does
    val chatWith = { key: HfModel ->
        nav.navigate(Routes.newChatWith(key)) {
            if (layout == AppLayout.Sidebar) popUpTo(Routes.CHAT_PATTERN) { inclusive = true }
        }
    }
    // screens fade through, never crossfade: the chat's greeting, orb and chips drew over
    // the Models rows for 300 ms. The one leaving fades out, then the one arriving fades in, on the text motion tokens
    val still = animationsDisabled()
    NavHost(
        nav,
        startDestination = start,
        enterTransition = { screenEnter(still) },
        exitTransition = { screenExit(still) },
        popEnterTransition = { screenEnter(still) },
        popExitTransition = { screenExit(still) },
    ) {
        composable(Routes.CHATS) {
            ChatListHome(shell, openChat, onNewChat = { openChat(Routes.NEW_CHAT_ID) }, onDelete = deleteChat)
        }
        composable(
            Routes.CHAT_PATTERN,
            arguments =
                listOf(
                    navArgument(Routes.CHAT_ARG) { type = NavType.LongType },
                    navArgument(Routes.CHAT_MODEL_ARG) {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    },
                ),
        ) { entry: NavBackStackEntry ->
            val id = entry.arguments?.getLong(Routes.CHAT_ARG) ?: 0L
            val startModel =
                entry.arguments
                    ?.getString(Routes.CHAT_MODEL_ARG)
                    ?.let { runCatching { HfModel.parse(it) }.getOrNull() }
            chatScreen(
                id,
                ChatChrome(
                    layout,
                    openDrawer,
                    back,
                    { openChat(Routes.NEW_CHAT_ID) },
                    { models("") },
                    { nav.navigate(Routes.TOKEN) { launchSingleTop = true } },
                    onMemory = { nav.navigate(Routes.MEMORY) { launchSingleTop = true } },
                    onChatResolved = { onChatResolved(entry.id, it) },
                    startModel = startModel,
                ),
            )
        }
        composable(
            Routes.MODELS,
            arguments =
                listOf(
                    navArgument(Routes.QUERY_ARG) {
                        type = NavType.StringType
                        defaultValue = ""
                    },
                ),
        ) { entry ->
            val seenFirstRun by shell.seenFirstRun.collectAsStateWithLifecycle()
            // Back only when something is under the page: a fresh install starts here (models-page design §2)
            val under = remember(entry) { nav.previousBackStackEntry != null }
            ModelsRoute(
                screens,
                entry.arguments?.getString(Routes.QUERY_ARG).orEmpty(),
                onBack = if (under) back else null,
                onChatWith = chatWith,
                // the first Get on a fresh install opens its chat in the page's place, as first run's did
                onOpenChat = { id -> nav.navigate(Routes.chat(id)) { popUpTo(Routes.MODELS) { inclusive = true } } },
                initialIntro = seenFirstRun?.not(),
            )
        }
        composable(Routes.SETTINGS) {
            SettingsHome(back, onOpen = { route ->
                nav.navigate(route) { launchSingleTop = true }
            }, routes = settingsRoutes)
        }
        composable(Routes.APPEARANCE) { AppearanceScreen(settings, back) { nav.navigate(Routes.GLASS) } }
        composable(Routes.INSTRUCTIONS) { InstructionsScreen(settings, back) }
        composable(Routes.DEFAULT_MODEL) { DefaultModelScreen(settings, back) }
        composable(Routes.TOKEN) { TokenScreen(settings, back) }
        composable(Routes.MEMORY) { MemoryScreen(settings, back) }
        composable(Routes.VOICE) { VoiceScreen(settings, back) }
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
        composable(Routes.GLASS) { GlassScreen(settings::setGlass, back) }
    }
}

/** A chat with no messages yet: the greeting the design gives. */
@Composable
private fun EmptyChatScreen(chrome: ChatChrome) {
    FloatingFrame(
        top = {
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
        },
        modifier = Modifier.fillMaxSize(),
    ) { bars ->
        Box(Modifier.fillMaxSize().padding(bars), contentAlignment = Alignment.Center) {
            EmptyLine("What's on your mind?")
        }
    }
}

/**
 * The status and navigation bar icons follow the app's own theme choice, not just the system's: a light-theme app on a
 * dark phone gets dark icons, so the clock stays readable. (`enableEdgeToEdge()` reads the system setting once;
 * calling it again from here leaked each activity's compose view, so only the icon appearance is set.)
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
        activity.window.setBackgroundDrawable(
            (if (dark) DarkDemoColors.canvas else LightDemoColors.canvas).toArgb().toDrawable(),
        )
    }
}
