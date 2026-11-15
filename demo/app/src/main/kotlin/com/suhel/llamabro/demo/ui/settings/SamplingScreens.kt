package com.suhel.llamabro.demo.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.suhel.llamabro.demo.chat.Knob
import com.suhel.llamabro.demo.chat.KnobSource
import com.suhel.llamabro.demo.chat.KnobValue
import com.suhel.llamabro.demo.chat.SamplingLayers
import com.suhel.llamabro.demo.data.ReplyLength
import com.suhel.llamabro.demo.designsystem.components.GroupScope
import com.suhel.llamabro.demo.designsystem.components.GroupedList
import com.suhel.llamabro.demo.designsystem.components.ListRow
import com.suhel.llamabro.demo.designsystem.components.QuietButton
import com.suhel.llamabro.demo.designsystem.components.Section
import com.suhel.llamabro.demo.designsystem.components.SectionNote
import com.suhel.llamabro.demo.designsystem.components.SegmentedSwitch
import com.suhel.llamabro.demo.designsystem.components.SliderRow
import com.suhel.llamabro.demo.models.LibraryModel
import com.suhel.llamabro.hub.HfModel
import kotlin.math.roundToInt

/** A model in Settings › Sampling: its name and how many of its values differ from its card's. */
data class SamplingModelRow(val key: HfModel, val name: String, val changed: Int)

/** Rows for the library's models, in the library's order. */
fun samplingRows(library: List<LibraryModel>, changed: Map<String, Int>): List<SamplingModelRow> =
    library.map { SamplingModelRow(it.key, it.name, changed[it.key.toString()] ?: 0) }

@Composable
fun SamplingModelsScreen(viewModel: SettingsViewModel, onBack: () -> Unit, onOpen: (HfModel) -> Unit) {
    val rows by viewModel.samplingRows.collectAsStateWithLifecycle()
    val settings by viewModel.state.collectAsStateWithLifecycle()
    SamplingModelsContent(settings.replyLength, rows, viewModel::setReplyLength, onOpen, onBack)
}

/**
 * Settings › Sampling (D60): the reply length, for every model and chat, then each model on the phone, opening its
 * values. A model's values start from its model card's recommendations; a chat can change them again for itself.
 */
@Composable
fun SamplingModelsContent(
    length: ReplyLength,
    rows: List<SamplingModelRow>,
    onLength: (ReplyLength) -> Unit,
    onOpen: (HfModel) -> Unit,
    onBack: () -> Unit,
) {
    SettingsPage("Sampling", onBack, Modifier.testTag("sampling_settings")) {
        Section("Reply length") { ReplyLengthList(length, onLength) }
        Section("Models on this phone") {
            GroupedList {
                if (rows.isEmpty()) row { ListRow("No models yet", subtitle = "Get a model to set its values") }
                for (r in rows) {
                    row {
                        ListRow(
                            r.name,
                            subtitle = changedLine(r.changed),
                            chevron = true,
                            onClick = { onOpen(r.key) },
                        )
                    }
                }
            }
        }
    }
}

/**
 * The reply's length limit, thinking and answer together: Automatic follows the Think level (D60); a reply that reaches
 * the limit stops, and Continue goes on.
 */
@Composable
private fun ReplyLengthList(chosen: ReplyLength, onLength: (ReplyLength) -> Unit) {
    GroupedList(Modifier.testTag("reply_length")) {
        for (choice in ReplyLength.entries) {
            row {
                ListRow(
                    choice.title,
                    subtitle = choice.subtitle,
                    selected = chosen == choice,
                    onClick = { onLength(choice) },
                )
            }
        }
    }
    SectionNote(LENGTH_NOTE)
}

private const val LENGTH_NOTE =
    "For every model and chat. A reply that reaches its length stops, and Continue goes on, unless the chat has " +
        "filled the context."

private val ReplyLength.title: String
    get() =
        when (this) {
            ReplyLength.Automatic -> "Automatic"
            ReplyLength.Short -> "Short"
            ReplyLength.Long -> "Long"
            ReplyLength.NoLimit -> "No limit"
        }

private val ReplyLength.subtitle: String
    get() =
        when (this) {
            ReplyLength.Automatic -> "Set by the Think level: longer when it thinks more"
            ReplyLength.Short -> "About 1,000 tokens, thinking included"
            ReplyLength.Long -> "About 8,000 tokens, thinking included"
            ReplyLength.NoLimit -> "Until the model stops or the context is full"
        }

@Composable
fun SamplingModelScreen(viewModel: SettingsViewModel, key: HfModel, onBack: () -> Unit) {
    val layers by viewModel.samplingOf(key).collectAsStateWithLifecycle(null)
    val name =
        viewModel.library
            .collectAsStateWithLifecycle()
            .value
            .firstOrNull { it.key == key }
            ?.name ?: ""
    layers?.let { l ->
        SamplingModelContent(
            name,
            l,
            onChange = { knob, value -> viewModel.setSampling(key, knob, value) },
            onReset = { viewModel.resetSampling(key) },
            onBack = onBack,
        )
    }
}

/**
 * One model's values: a slider for each, saying whether it is the model's default or changed. When the card
 * recommends other values with thinking on, a switch shows either set; a change applies to both.
 */
@Composable
fun SamplingModelContent(
    name: String,
    layers: SamplingLayers,
    onChange: (Knob, Float) -> Unit,
    onReset: () -> Unit,
    onBack: () -> Unit,
) {
    val card = layers.card
    var thinks by rememberSaveable { mutableStateOf(true) }
    val twoModes = card != null && card.thinking != card.answering
    SettingsPage(name, onBack, Modifier.testTag("sampling_model")) {
        if (twoModes) {
            Section("Recommended values for") {
                SegmentedSwitch(listOf("Thinking", "Not thinking"), if (thinks) 0 else 1, { thinks = it == 0 })
            }
        }
        Section("Values") {
            GroupedList { samplingSliders(layers.resolve(thinks), ::modelNote, onChange) }
        }
        if (!layers.model.isEmpty) QuietButton("Reset to the model's defaults", onReset)
    }
}

/** Sliders for every knob, each with its value's [note]: the rows of a [GroupedList]. */
fun GroupScope.samplingSliders(
    values: Map<Knob, KnobValue>,
    note: (KnobSource) -> String,
    onChange: (Knob, Float) -> Unit,
) {
    for ((knob, v) in values) {
        row {
            SliderRow(
                knob.label,
                v.value,
                knob::format,
                knob.min..knob.max,
                steps = ((knob.max - knob.min) / knob.step).roundToInt() - 1,
                onValueChange = { onChange(knob, knob.snap(it)) },
                Modifier.testTag("knob_${knob.key}"),
                note = note(v.source),
            )
        }
    }
}

/** On a model's page: a value is the card's, llama.cpp's, or changed for the model. */
fun modelNote(source: KnobSource): String =
    when (source) {
        KnobSource.Chat, KnobSource.Model -> "Changed"
        KnobSource.Card -> "Model default"
        KnobSource.Server -> "llama.cpp default"
    }

private fun changedLine(changed: Int): String =
    when (changed) {
        0 -> "Model defaults"
        1 -> "1 value changed"
        else -> "$changed values changed"
    }
