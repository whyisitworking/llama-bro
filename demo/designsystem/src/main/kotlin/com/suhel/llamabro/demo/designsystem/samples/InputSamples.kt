package com.suhel.llamabro.demo.designsystem.samples

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.suhel.llamabro.demo.designsystem.components.Composer
import com.suhel.llamabro.demo.designsystem.components.ComposerState
import com.suhel.llamabro.demo.designsystem.components.SearchField
import com.suhel.llamabro.demo.designsystem.components.TextArea
import com.suhel.llamabro.demo.designsystem.components.ThinkLevel
import com.suhel.llamabro.demo.designsystem.theme.DemoTheme

/** Search (empty and typed), the instructions box, and the message bar in its four states. */
@Composable
internal fun InputSamples() {
    SampleFrame {
        SearchField("", onValueChange = {}, placeholder = "Search any model", modifier = Modifier.fillMaxWidth())
        SearchField("qwen", onValueChange = {}, placeholder = "Search Hugging Face", modifier = Modifier.fillMaxWidth())
        TextArea("", onValueChange = {
        }, placeholder = "What should every chat know about you?", modifier = Modifier.fillMaxWidth())
        SampleComposer(ComposerState("", canAttach = true, think = ThinkLevel.Off, busy = false), "Message Swift")
        SampleComposer(
            ComposerState(
                "Help me write a short bio for my portfolio",
                canAttach = true,
                think = ThinkLevel.Balanced,
                busy = false,
            ),
            "Message Swift",
        )
        SampleComposer(ComposerState("", canAttach = true, think = ThinkLevel.Quick, busy = true), "Message Deep")
        SampleComposer(ComposerState("", canAttach = false, think = null, busy = false), "Message Writer")
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

@LightDarkPreviews
@Composable
private fun InputPreview() = DemoTheme { InputSamples() }
