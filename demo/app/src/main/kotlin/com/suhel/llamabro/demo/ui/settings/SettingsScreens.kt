package com.suhel.llamabro.demo.ui.settings

import android.content.Context
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.suhel.llamabro.demo.data.AppLayout
import com.suhel.llamabro.demo.designsystem.components.DemoIcons
import com.suhel.llamabro.demo.designsystem.components.Gap
import com.suhel.llamabro.demo.designsystem.components.GroupedList
import com.suhel.llamabro.demo.designsystem.components.IconAction
import com.suhel.llamabro.demo.designsystem.components.Inline
import com.suhel.llamabro.demo.designsystem.components.LayoutKind
import com.suhel.llamabro.demo.designsystem.components.LayoutPreviewCard
import com.suhel.llamabro.demo.designsystem.components.ListRow
import com.suhel.llamabro.demo.designsystem.components.PrimaryButton
import com.suhel.llamabro.demo.designsystem.components.QuietButton
import com.suhel.llamabro.demo.designsystem.components.Screen
import com.suhel.llamabro.demo.designsystem.components.SearchField
import com.suhel.llamabro.demo.designsystem.components.Section
import com.suhel.llamabro.demo.designsystem.components.SegmentedSwitch
import com.suhel.llamabro.demo.designsystem.components.Stack
import com.suhel.llamabro.demo.designsystem.components.TextArea
import com.suhel.llamabro.demo.designsystem.components.TopBar
import com.suhel.llamabro.demo.designsystem.components.TopBarTitle
import com.suhel.llamabro.demo.designsystem.theme.DemoType
import com.suhel.llamabro.demo.designsystem.theme.LocalDemoColors
import com.suhel.llamabro.demo.designsystem.theme.ThemeMode
import com.suhel.llamabro.demo.models.Format
import com.suhel.llamabro.demo.models.LibraryModel
import com.suhel.llamabro.demo.ui.mode

/**
 * A settings screen: a floating top bar with Back over its body, whose parts are a section apart on the gutter, and an
 * optional [bottomBar] floating over its bottom.
 */
@Composable
fun SettingsPage(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    bottomBar: @Composable () -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    Screen(
        topBar = {
            TopBar(
                title = { TopBarTitle(title) },
                start = { IconAction(DemoIcons.Back, "Back", onClick = onBack) },
            )
        },
        modifier = modifier,
        bottomBar = bottomBar,
        content = content,
    )
}

/** Settings home: the areas in groups, each row opening its page (demo design §4.5). */
@Composable
fun SettingsHome(onBack: () -> Unit, onOpen: (String) -> Unit, routes: SettingsRoutes) {
    SettingsPage("Settings", onBack, Modifier.testTag("settings_home")) {
        Section("General") {
            GroupedList {
                row {
                    ListRow(
                        "Appearance",
                        subtitle = "Layout, theme and glass",
                        chevron = true,
                        onClick = { onOpen(routes.appearance) },
                    )
                }
                row {
                    ListRow(
                        "Custom instructions",
                        subtitle = "Tell every chat about you",
                        chevron = true,
                        onClick = { onOpen(routes.instructions) },
                    )
                }
            }
        }
        Section("Models") {
            GroupedList {
                row {
                    ListRow(
                        "Default model",
                        subtitle = "Which model new chats start with",
                        chevron = true,
                        onClick = { onOpen(routes.defaultModel) },
                    )
                }
                row {
                    ListRow(
                        "Hugging Face token",
                        subtitle = "For models that need one",
                        chevron = true,
                        onClick = { onOpen(routes.token) },
                    )
                }
                row {
                    ListRow(
                        "Inference",
                        subtitle = "Context, reply length and each model's values",
                        chevron = true,
                        onClick = { onOpen(routes.inference) },
                    )
                }
                row {
                    ListRow(
                        "Processor",
                        subtitle = "Where models run: NPU, GPU or CPU",
                        chevron = true,
                        onClick = { onOpen(routes.processor) },
                    )
                }
                row {
                    ListRow(
                        "Memory",
                        subtitle = "The model in memory, and when it's freed",
                        chevron = true,
                        onClick = { onOpen(routes.memory) },
                    )
                }
                row {
                    ListRow(
                        "Voice",
                        subtitle = "Voice mode and dictation",
                        chevron = true,
                        onClick = { onOpen(routes.voice) },
                    )
                }
            }
        }
        Section("App") {
            GroupedList {
                row {
                    ListRow(
                        "About",
                        subtitle = "Versions and licences",
                        chevron = true,
                        onClick = { onOpen(routes.about) },
                    )
                }
            }
        }
    }
}

/** The routes settings rows open (the shell owns the real ones). */
data class SettingsRoutes(
    val appearance: String,
    val instructions: String,
    val defaultModel: String,
    val token: String,
    val inference: String,
    val processor: String,
    val memory: String,
    val voice: String,
    val about: String,
)

@Composable
fun AppearanceScreen(viewModel: SettingsViewModel, onBack: () -> Unit, onGlass: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    AppearanceContent(state.layout, state.theme.mode(), viewModel::setLayout, viewModel::setTheme, onBack, onGlass)
}

/** The layout choice (two small pictures of the two layouts), the theme switch, and the way to the glass (D152). */
@Composable
fun AppearanceContent(
    layout: AppLayout,
    theme: ThemeMode,
    onLayout: (AppLayout) -> Unit,
    onTheme: (ThemeMode) -> Unit,
    onBack: () -> Unit,
    onGlass: () -> Unit,
) {
    SettingsPage("Appearance", onBack) {
        Section("Layout") {
            Inline(Gap.Item, Modifier.fillMaxWidth()) {
                LayoutPreviewCard(
                    LayoutKind.Sidebar,
                    "Sidebar",
                    "Like ChatGPT",
                    layout == AppLayout.Sidebar,
                    onClick = { onLayout(AppLayout.Sidebar) },
                    modifier = Modifier.weight(1f).testTag("layout_sidebar"),
                )
                LayoutPreviewCard(
                    LayoutKind.ChatList,
                    "Chat list",
                    "Like messages",
                    layout == AppLayout.ChatList,
                    onClick = { onLayout(AppLayout.ChatList) },
                    modifier = Modifier.weight(1f).testTag("layout_chat_list"),
                )
            }
        }
        Section("Theme") {
            val modes = ThemeMode.entries
            SegmentedSwitch(
                modes.map { it.name },
                modes.indexOf(theme),
                { onTheme(modes[it]) },
                Modifier.fillMaxWidth().testTag("theme_switch"),
            )
        }
        Section("Glass") {
            GroupedList {
                row {
                    ListRow(
                        "Glass",
                        subtitle = "Blur, refraction and colour split",
                        chevron = true,
                        onClick = onGlass,
                        modifier = Modifier.testTag("appearance_glass"),
                    )
                }
            }
        }
    }
}

@Composable
fun InstructionsScreen(viewModel: SettingsViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var about by rememberSaveable(state.instructionsAbout) { mutableStateOf(state.instructionsAbout) }
    var style by rememberSaveable(state.instructionsStyle) { mutableStateOf(state.instructionsStyle) }
    InstructionsContent(
        about,
        style,
        { about = it },
        { style = it },
        onSave = {
            viewModel.saveInstructions(about, style)
            onBack()
        },
        onBack = onBack,
    )
}

/** What every chat should know about the user, and how replies should sound. */
@Composable
fun InstructionsContent(
    about: String,
    style: String,
    onAbout: (String) -> Unit,
    onStyle: (String) -> Unit,
    onSave: () -> Unit,
    onBack: () -> Unit,
) {
    SettingsPage("Custom instructions", onBack) {
        Section("About me") {
            TextArea(
                about,
                onAbout,
                "What should every chat know about you?",
                Modifier.fillMaxWidth().testTag("instructions_about"),
            )
        }
        Section("How to answer") {
            TextArea(style, onStyle, "How should replies sound?", Modifier.fillMaxWidth().testTag("instructions_style"))
        }
        PrimaryButton("Save", onSave, Modifier.fillMaxWidth().testTag("instructions_save"))
    }
}

@Composable
fun DefaultModelScreen(viewModel: SettingsViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val library by viewModel.library.collectAsStateWithLifecycle()
    DefaultModelContent(state.defaultModel, library, viewModel::pinDefault, onBack)
}

/** "Last used", then every model on the phone; the pinned one is ticked. [defaultModel] null means last used. */
@Composable
fun DefaultModelContent(
    defaultModel: String?,
    library: List<LibraryModel>,
    onPin: (String?) -> Unit,
    onBack: () -> Unit,
) {
    SettingsPage("Default model", onBack) {
        GroupedList {
            row {
                ListRow(
                    "Last used",
                    subtitle = "New chats start with the model you used last",
                    selected = defaultModel == null,
                    onClick = { onPin(null) },
                )
            }
        }
        if (library.isNotEmpty()) {
            Section("On this phone") {
                GroupedList {
                    for (model in library) {
                        row {
                            ListRow(
                                model.name,
                                // the one size, the file and its image reader (models-page design §7)
                                subtitle = Format.sizeInGb(model.totalBytes),
                                selected = defaultModel == model.key.toString(),
                                onClick = { onPin(model.key.toString()) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun TokenScreen(viewModel: SettingsViewModel, onBack: () -> Unit) {
    val hasToken by viewModel.hasToken.collectAsStateWithLifecycle()
    var text by rememberSaveable { mutableStateOf("") }
    TokenContent(
        hasToken,
        text,
        { text = it },
        onSave = {
            viewModel.saveToken(text)
            text = ""
        },
        onRemove = viewModel::removeToken,
        onBack = onBack,
    )
}

/** Where the user pastes a Hugging Face token; a saved one is never shown again, only that it exists. */
@Composable
fun TokenContent(
    hasToken: Boolean,
    text: String,
    onText: (String) -> Unit,
    onSave: () -> Unit,
    onRemove: () -> Unit,
    onBack: () -> Unit,
) {
    SettingsPage("Hugging Face token", onBack) {
        Stack(Gap.Item) {
            Text(
                "Some models on Hugging Face need you to sign in. Paste a read token here. It's saved privately on " +
                    "this phone and sent only to Hugging Face.",
                color = LocalDemoColors.current.muted,
                style = DemoType.body,
            )
            SearchField(
                text,
                onText,
                if (hasToken) "A token is saved" else "Paste your token",
                Modifier.fillMaxWidth().testTag("token_field"),
                icon = DemoIcons.Key,
                secret = true,
            )
            PrimaryButton("Save", onSave, Modifier.fillMaxWidth().testTag("token_save"), enabled = text.isNotBlank())
        }
        if (hasToken) {
            Stack(Gap.Block) {
                Text(
                    "Saved privately on this phone",
                    color = LocalDemoColors.current.muted,
                    style = DemoType.bodyMedium,
                    modifier = Modifier.testTag("token_saved"),
                )
                QuietButton("Remove", onRemove, Modifier.testTag("token_remove"), bordered = true, destructive = true)
            }
        }
    }
}

/** The pinned llama.cpp build (design: the submodule at b12653). */
private const val LLAMA_CPP_BUILD = "b12653"

@Composable
fun AboutScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val version = remember { appVersion(context) }
    AboutContent(version, onBack)
}

/** The app's version, the llama.cpp build it carries, and the licences of what it is made of. */
@Composable
fun AboutContent(version: String, onBack: () -> Unit) {
    SettingsPage("About", onBack, Modifier.testTag("about")) {
        Section("Versions") {
            GroupedList {
                row { ListRow("Version", trailing = version) }
                row { ListRow("llama.cpp", trailing = LLAMA_CPP_BUILD) }
            }
        }
        Section("Licences") {
            GroupedList {
                row { ListRow("llama.cpp", subtitle = "MIT licence") }
                row { ListRow("Figtree", subtitle = "SIL Open Font Licence 1.1") }
                row { ListRow("Source Serif 4", subtitle = "SIL Open Font Licence 1.1") }
                row { ListRow("Jetpack Compose, Room, WorkManager", subtitle = "Apache licence 2.0") }
            }
        }
    }
}

private fun appVersion(context: Context): String =
    runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "dev"
    }.getOrDefault("dev")
