package com.suhel.llamabro.demo.designsystem.samples

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.suhel.llamabro.demo.designsystem.components.Composer
import com.suhel.llamabro.demo.designsystem.components.ComposerState
import com.suhel.llamabro.demo.designsystem.components.DemoIcons
import com.suhel.llamabro.demo.designsystem.components.Gap
import com.suhel.llamabro.demo.designsystem.components.IconAction
import com.suhel.llamabro.demo.designsystem.components.Inline
import com.suhel.llamabro.demo.designsystem.components.NumberField
import com.suhel.llamabro.demo.designsystem.components.RecordingState
import com.suhel.llamabro.demo.designsystem.components.SearchField
import com.suhel.llamabro.demo.designsystem.components.TextArea
import com.suhel.llamabro.demo.designsystem.components.ThinkLevel
import com.suhel.llamabro.demo.designsystem.theme.DemoTheme

/**
 * Search (empty, typed, and with its filter action), two number fields, the instructions box, and the message bar in
 * its states: empty, typed, replying, a model that can't think, for a model that hears (D76) the mic, recording, and a
 * recording that stopped and waits, and for a model that talks the voice-mode button (D100).
 */
@Composable
internal fun InputSamples() {
    SampleFrame {
        SearchField("", onValueChange = {}, placeholder = "Search any model", modifier = Modifier.fillMaxWidth())
        SearchField("qwen", onValueChange = {}, placeholder = "Search Hugging Face", modifier = Modifier.fillMaxWidth())
        // with its own action at the end: the search's filters, changed from their defaults
        SearchField(
            "qwen",
            onValueChange = {},
            placeholder = "Search models",
            modifier = Modifier.fillMaxWidth(),
            trailing = {
                IconAction(DemoIcons.Filter, "Search filters", onClick = {}, muted = true, mark = "Changed")
            },
        )
        Inline(Gap.Block) {
            NumberField("1", onValueChange = {}, label = "From", unit = "B", modifier = Modifier.weight(1f))
            NumberField("", onValueChange = {}, label = "To", unit = "B", modifier = Modifier.weight(1f))
        }
        TextArea("", onValueChange = {
        }, placeholder = "What should every chat know about you?", modifier = Modifier.fillMaxWidth())
        SampleComposer(
            ComposerState("", canAttach = true, think = ThinkLevel.Off, busy = false),
            "Message Qwen3.5 0.8B",
        )
        SampleComposer(
            ComposerState(
                "Help me write a short bio for my portfolio",
                canAttach = true,
                think = ThinkLevel.Balanced,
                busy = false,
            ),
            "Message Qwen3.5 0.8B",
        )
        SampleComposer(ComposerState("", canAttach = true, think = ThinkLevel.Quick, busy = true), "Message Deep")
        SampleComposer(ComposerState("", canAttach = false, think = null, busy = false), "Message Gemma 3 4B")
        SampleComposer(
            ComposerState("", canAttach = true, think = null, busy = false, canRecord = true),
            "Message Omni",
        )
        // a model that talks (D100): the mic before the voice-mode button in send's place
        SampleComposer(
            ComposerState("", canAttach = true, think = null, busy = false, canRecord = true, canTalk = true),
            "Message Omni",
        )
        SampleComposer(
            ComposerState(
                "",
                canAttach = true,
                think = null,
                busy = false,
                canRecord = true,
                recording = RecordingState(SampleLevels, "0:42", live = true),
            ),
            "Message Omni",
        )
        SampleComposer(
            ComposerState(
                "",
                canAttach = true,
                think = null,
                busy = false,
                canRecord = true,
                recording = RecordingState(SampleLevels, "1:52", live = true, nearEnd = true),
            ),
            "Message Omni",
        )
        SampleComposer(
            ComposerState(
                "",
                canAttach = true,
                think = null,
                busy = false,
                canRecord = true,
                recording = RecordingState(SampleLevels, "0:42", live = false),
            ),
            "Message Omni",
        )
    }
}

@Composable
private fun SampleComposer(state: ComposerState, placeholder: String) {
    Composer(
        state = state,
        placeholder = placeholder,
        onTextChange = {},
        onSend = {},
        onStop = {},
        onAttach = {},
        onThink = {},
        modifier = Modifier.fillMaxWidth(),
    )
}

/** A voice's levels for the samples: speech, with pauses. */
internal val SampleLevels: List<Float> =
    List(96) { i -> if (i % 17 < 3) 0.05f else 0.3f + 0.6f * (((i * 37) % 11) / 10f) }

@LightDarkPreviews
@Composable
private fun InputPreview() = DemoTheme { InputSamples() }
