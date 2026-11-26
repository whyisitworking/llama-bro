package com.suhel.llamabro.demo.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.suhel.llamabro.DeviceKind
import com.suhel.llamabro.demo.chat.label
import com.suhel.llamabro.demo.data.ChipChoice
import com.suhel.llamabro.demo.designsystem.components.GroupedList
import com.suhel.llamabro.demo.designsystem.components.ListRow
import com.suhel.llamabro.demo.designsystem.components.Section
import com.suhel.llamabro.demo.designsystem.components.SectionNote

@Composable
fun ProcessorScreen(viewModel: SettingsViewModel, onBack: () -> Unit) {
    val settings by viewModel.state.collectAsStateWithLifecycle()
    val chips by viewModel.chips.collectAsStateWithLifecycle()
    val runsOn by viewModel.runsOn.collectAsStateWithLifecycle()
    ProcessorContent(ProcessorState(settings.chip, chips, runsOn), viewModel::setChip, onBack)
}

/**
 * What Settings › Processor shows: the chip chosen and the kinds this phone has ([chips]), and the line under it
 * ([runsOn], D72): where the model runs when that isn't the chip chosen, or when a model loaded for an earlier choice
 * moves.
 */
data class ProcessorState(val chip: ChipChoice, val chips: Set<DeviceKind>, val runsOn: String?)

/** Settings › Processor (D157, amending D72): where the models run, NPU, GPU or CPU, for every chat. */
@Composable
fun ProcessorContent(state: ProcessorState, onChip: (ChipChoice) -> Unit, onBack: () -> Unit) {
    SettingsPage("Processor", onBack, Modifier.testTag("processor_settings")) {
        Section("Runs on") { RunsOn(state, onChip) }
    }
}

/**
 * The chip choice (D72): Automatic or a chip, a chip this phone lacks marked so (it still falls back, never fails),
 * then where the model runs when that isn't the chip chosen (or when it moves), and that a change reloads the model.
 */
@Composable
private fun RunsOn(state: ProcessorState, onChip: (ChipChoice) -> Unit) {
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
    SectionNote(RELOAD_NOTE)
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
