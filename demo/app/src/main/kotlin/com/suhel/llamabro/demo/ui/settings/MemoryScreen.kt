package com.suhel.llamabro.demo.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.suhel.llamabro.demo.chat.InMemory
import com.suhel.llamabro.demo.data.KeepLoaded
import com.suhel.llamabro.demo.designsystem.components.GroupedList
import com.suhel.llamabro.demo.designsystem.components.ListRow
import com.suhel.llamabro.demo.designsystem.components.QuietButton
import com.suhel.llamabro.demo.designsystem.components.Section

@Composable
fun MemoryScreen(viewModel: SettingsViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val inMemory by viewModel.inMemory.collectAsStateWithLifecycle()
    val replying by viewModel.replying.collectAsStateWithLifecycle()
    MemoryContent(inMemory, replying, state.keepLoaded, viewModel::unload, viewModel::setKeepLoaded, onBack)
}

/**
 * Settings › Memory (D59): the model in memory now, with Unload, and how long it stays once the app is in the
 * background. Android's own request for memory frees it under every choice.
 */
@Composable
fun MemoryContent(
    inMemory: InMemory?,
    replying: Boolean,
    keep: KeepLoaded,
    onUnload: () -> Unit,
    onKeep: (KeepLoaded) -> Unit,
    onBack: () -> Unit,
) {
    SettingsPage("Memory", onBack, Modifier.testTag("memory_settings")) {
        Section("In memory now") { InMemoryList(inMemory, replying, onUnload) }
        Section("After you leave the app") {
            GroupedList {
                for (choice in KeepLoaded.entries) {
                    row {
                        ListRow(
                            choice.title,
                            subtitle = choice.subtitle,
                            selected = keep == choice,
                            onClick = { onKeep(choice) },
                        )
                    }
                }
            }
        }
    }
}

/**
 * The model in memory as a row with Unload at its end, or that nothing is loaded. While a reply is being written the
 * row says so instead of offering to unload: the reply is never cut off.
 */
@Composable
fun InMemoryList(inMemory: InMemory?, replying: Boolean, onUnload: () -> Unit) {
    GroupedList(Modifier.testTag("in_memory")) {
        row {
            when {
                inMemory == null -> {
                    ListRow("Nothing loaded", subtitle = "A model loads when you send a message")
                }

                replying -> {
                    ListRow(inMemory.name, subtitle = "${inMemory.detail} · writing a reply")
                }

                else -> {
                    ListRow(inMemory.name, subtitle = inMemory.detail, end = { QuietButton("Unload", onUnload) })
                }
            }
        }
    }
}

private val KeepLoaded.title: String
    get() =
        when (this) {
            KeepLoaded.OneMinute -> "Free it after a minute"
            KeepLoaded.FiveMinutes -> "Free it after 5 minutes"
            KeepLoaded.UntilNeeded -> "Keep it until Android needs the memory"
        }

private val KeepLoaded.subtitle: String
    get() =
        when (this) {
            KeepLoaded.OneMinute -> "A quick look at another app keeps it ready"
            KeepLoaded.FiveMinutes -> "Ready for longer; other apps have less room"
            KeepLoaded.UntilNeeded -> "Ready when you're back, unless Android closed the app"
        }
