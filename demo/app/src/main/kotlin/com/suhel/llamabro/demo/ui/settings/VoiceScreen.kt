package com.suhel.llamabro.demo.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.suhel.llamabro.demo.designsystem.components.GroupedList
import com.suhel.llamabro.demo.designsystem.components.ListRow
import com.suhel.llamabro.demo.designsystem.components.Section
import com.suhel.llamabro.demo.designsystem.components.SectionNote

/**
 * What Settings › Voice shows (D100): the voices to choose from, the one chosen, and whether the person is captioned.
 */
data class VoiceSettingsState(val voices: List<String>, val voice: String?, val captionYou: Boolean)

@Composable
fun VoiceScreen(viewModel: SettingsViewModel, onBack: () -> Unit) {
    val settings by viewModel.state.collectAsStateWithLifecycle()
    val voices by viewModel.voices.collectAsStateWithLifecycle()
    VoiceContent(
        VoiceSettingsState(voices, settings.voice, settings.captionYou),
        onVoice = viewModel::setVoice,
        onCaption = viewModel::setCaptionYou,
        onBack = onBack,
    )
}

/**
 * Settings › Voice (demo design §4.8, D100): the voice a model that talks speaks with (its first unless another is
 * chosen; a model without the chosen one speaks with its first), and whether voice mode captions what the person
 * says, which loads the model with a second slot.
 */
@Composable
fun VoiceContent(
    state: VoiceSettingsState,
    onVoice: (String) -> Unit,
    onCaption: (Boolean) -> Unit,
    onBack: () -> Unit,
) {
    SettingsPage("Voice", onBack, Modifier.testTag("voice_settings")) {
        Section("Voice") {
            if (state.voices.isEmpty()) {
                SectionNote("Talk with a model that speaks once, and its voices show here.")
            } else {
                val chosen = state.voice?.takeIf { it in state.voices } ?: state.voices.first()
                GroupedList {
                    for (voice in state.voices) {
                        row {
                            ListRow(voice.replaceFirstChar { it.uppercase() }, selected = voice == chosen, onClick = {
                                onVoice(voice)
                            })
                        }
                    }
                }
                SectionNote("A model without this voice speaks with its own first one.")
            }
        }
        Section("Captions") {
            GroupedList {
                row {
                    ListRow(
                        "Caption what you say",
                        subtitle = "Your words show as you talk and stay in the chat",
                        selected = state.captionYou,
                        onClick = { onCaption(true) },
                    )
                }
                row {
                    ListRow(
                        "Don't caption",
                        subtitle = "Uses less memory; your turns stay as voice messages",
                        selected = !state.captionYou,
                        onClick = { onCaption(false) },
                    )
                }
            }
        }
    }
}
