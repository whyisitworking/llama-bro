package com.suhel.llamabro.demo.ui.chat

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.suhel.llamabro.demo.chat.KnobSource
import com.suhel.llamabro.demo.chat.SamplingLayers
import com.suhel.llamabro.demo.data.settings.Knob
import com.suhel.llamabro.demo.designsystem.components.DemoSheet
import com.suhel.llamabro.demo.designsystem.components.GroupedList
import com.suhel.llamabro.demo.designsystem.components.QuietButton
import com.suhel.llamabro.demo.designsystem.components.Section
import com.suhel.llamabro.demo.ui.settings.samplingSliders

/** The chat's own sampling values (D60), over its model's: a sheet from the model switcher. */
@Composable
fun ChatSamplingSheet(
    layers: SamplingLayers,
    thinks: Boolean,
    onChange: (Knob, Float) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
) {
    DemoSheet(onDismissRequest = onDismiss) { ChatSamplingContent(layers, thinks, onChange, onReset) }
}

/**
 * The sheet's contents, without the window: a slider for each value, saying whether this chat changed it or it is the
 * model's; the values shown are those of the chat's Think level. Reset gives the chat back the model's values.
 */
@Composable
fun ColumnScope.ChatSamplingContent(
    layers: SamplingLayers,
    thinks: Boolean,
    onChange: (Knob, Float) -> Unit,
    onReset: () -> Unit,
) {
    Section("Sampling for this chat", Modifier.testTag("chat_sampling")) {
        GroupedList { samplingSliders(layers.resolve(thinks), ::chatNote, onChange) }
    }
    if (!layers.chat.isEmpty) QuietButton("Use the model's values", onReset)
}

/** In a chat: a value is changed for this chat, set for the model in Settings, or a default. */
fun chatNote(source: KnobSource): String =
    when (source) {
        KnobSource.Chat -> "Changed for this chat"
        KnobSource.Model -> "The model's setting"
        KnobSource.Card -> "Model default"
        KnobSource.Server -> "llama.cpp default"
    }
