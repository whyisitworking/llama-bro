package com.suhel.llamabro.demo.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.suhel.llamabro.DeviceKind
import com.suhel.llamabro.demo.chat.InMemory
import com.suhel.llamabro.demo.chat.kTokens
import com.suhel.llamabro.demo.chat.label
import com.suhel.llamabro.demo.data.ChipChoice
import com.suhel.llamabro.demo.data.ContextChoice
import com.suhel.llamabro.demo.data.KeepLoaded
import com.suhel.llamabro.demo.designsystem.components.GroupedList
import com.suhel.llamabro.demo.designsystem.components.ListRow
import com.suhel.llamabro.demo.designsystem.components.QuietButton
import com.suhel.llamabro.demo.designsystem.components.Section
import com.suhel.llamabro.demo.designsystem.components.SectionNote

@Composable
fun MemoryScreen(viewModel: SettingsViewModel, onBack: () -> Unit) {
    val settings by viewModel.state.collectAsStateWithLifecycle()
    val inMemory by viewModel.inMemory.collectAsStateWithLifecycle()
    val replying by viewModel.replying.collectAsStateWithLifecycle()
    val chips by viewModel.chips.collectAsStateWithLifecycle()
    val runsOn by viewModel.runsOn.collectAsStateWithLifecycle()
    val contextLine by viewModel.contextLine.collectAsStateWithLifecycle()
    MemoryContent(
        MemoryState(
            inMemory,
            replying,
            settings.keepLoaded,
            settings.chip,
            chips,
            runsOn,
            settings.context,
            contextLine,
        ),
        viewModel::unload,
        viewModel::setKeepLoaded,
        viewModel::setChip,
        viewModel::setContext,
        onBack,
    )
}

/**
 * What Settings › Memory shows: the model in memory and whether a reply holds it, how long it stays, the chip chosen
 * and the kinds this phone has ([chips]), the line under it ([runsOn], D72): where the model runs when that isn't
 * the chip chosen, or when a model loaded for an earlier choice moves, and the context length chosen.
 */
data class MemoryState(
    val inMemory: InMemory?,
    val replying: Boolean,
    val keep: KeepLoaded,
    val chip: ChipChoice,
    val chips: Set<DeviceKind>,
    val runsOn: String?,
    val context: ContextChoice = ContextChoice.Automatic,
    /** When a model loaded at another length reloads at the one chosen ([contextLine]); null when nothing changes. */
    val contextLine: String? = null,
)

/**
 * Settings › Memory (D59): the model in memory now, with Unload, the chip it runs on (D72), its context length, and how
 * long it stays once the app is in the background. Android's own request for memory frees it under every choice.
 */
@Composable
fun MemoryContent(
    state: MemoryState,
    onUnload: () -> Unit,
    onKeep: (KeepLoaded) -> Unit,
    onChip: (ChipChoice) -> Unit,
    onContext: (ContextChoice) -> Unit,
    onBack: () -> Unit,
) {
    SettingsPage("Memory", onBack, Modifier.testTag("memory_settings")) {
        Section("In memory now") {
            InMemoryList(state.inMemory, state.replying, onUnload)
            // said once, with the model it reloads, for both choices below
            SectionNote("Changing the chip or the context length reloads the model.")
        }
        Section("Runs on") { RunsOn(state, onChip) }
        Section("Context length") { ContextLength(state, onContext) }
        Section("After you leave the app") {
            GroupedList {
                for (choice in KeepLoaded.entries) {
                    row {
                        ListRow(
                            choice.title,
                            subtitle = choice.subtitle,
                            selected = state.keep == choice,
                            onClick = { onKeep(choice) },
                        )
                    }
                }
            }
        }
    }
}

/**
 * The chip choice (D72): Automatic or a chip, a chip this phone lacks marked so (it still falls back, never fails),
 * then where the model runs when that isn't the chip chosen (or when it moves). That a change reloads the model is
 * said once, under the model in memory.
 */
@Composable
private fun RunsOn(state: MemoryState, onChip: (ChipChoice) -> Unit) {
    GroupedList(Modifier.testTag("runs_on")) {
        for (choice in ChipChoice.entries) {
            row {
                ListRow(
                    choice.title,
                    subtitle = choice.kind?.takeIf { it !in state.chips }?.let { MISSING } ?: choice.subtitle,
                    selected = state.chip == choice,
                    onClick = { onChip(choice) },
                )
            }
        }
    }
    state.runsOn?.let { SectionNote(it, Modifier.testTag("runs_on_line")) }
}

/**
 * How much of the chat the model sees at once: Automatic, or a ceiling the SDK still fits to memory (down to 4k, so a
 * load never fails for it), then when a model loaded at another length reloads. A change reloads the model, as a chip
 * change does (D72).
 */
@Composable
private fun ContextLength(state: MemoryState, onContext: (ContextChoice) -> Unit) {
    GroupedList(Modifier.testTag("context_length")) {
        for (choice in ContextChoice.entries) {
            row {
                ListRow(
                    choice.title,
                    subtitle = choice.subtitle,
                    selected = state.context == choice,
                    onClick = { onContext(choice) },
                )
            }
        }
    }
    state.contextLine?.let { SectionNote(it, Modifier.testTag("context_line")) }
    SectionNote(CONTEXT_NOTE)
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

private const val MISSING = "Not on this phone, so the fastest chip it has runs the model"

private val ChipChoice.title: String get() = kind?.label ?: "Automatic"

private val ChipChoice.subtitle: String
    get() =
        when (this) {
            ChipChoice.Automatic -> "The fastest chip that can run the model"
            ChipChoice.Npu -> "The neural processor, built for this kind of work"
            ChipChoice.Gpu -> "The graphics processor"
            ChipChoice.Cpu -> "The main processor: slower, but every phone has one"
        }

private val ContextChoice.title: String get() = tokens?.let(::kTokens) ?: "Automatic"

private const val CONTEXT_NOTE =
    "How much of the chat the model sees at once. Each size is the most it gets: with less memory free, it gets " +
        "less, down to 4k."

private val ContextChoice.subtitle: String
    get() =
        when (this) {
            ContextChoice.Automatic -> "The most that fits in memory, up to 16k"
            ContextChoice.UpTo4k -> "About 3,000 words; the least memory"
            ContextChoice.UpTo8k -> "About 6,000 words"
            ContextChoice.UpTo16k -> "About 12,000 words"
            ContextChoice.UpTo32k -> "About 24,000 words, for long documents; the most memory"
        }
