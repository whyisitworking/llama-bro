package com.suhel.llamabro.demo.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.suhel.llamabro.demo.data.VoiceTurns
import com.suhel.llamabro.demo.designsystem.components.GroupedList
import com.suhel.llamabro.demo.designsystem.components.ListRow
import com.suhel.llamabro.demo.designsystem.components.RuledLabel
import com.suhel.llamabro.demo.designsystem.components.Section
import com.suhel.llamabro.demo.designsystem.components.SectionNote
import com.suhel.llamabro.demo.designsystem.components.SwitchRow
import java.util.Locale

/**
 * What Settings › Voice shows (D100, D140, D147): the voices to choose from, the one chosen, whether the person is
 * captioned, how a turn ends and whether talking interrupts a reply, the language dictation listens for (null: the
 * model tells), and the phone's own language, offered as the one choice.
 */
data class VoiceSettingsState(
    val voices: List<String>,
    val voice: String?,
    val captionYou: Boolean,
    val dictationLanguage: String? = null,
    val phoneLanguage: PhoneLanguage,
    val turns: VoiceTurns = VoiceTurns.TapToTalk,
    val interruptByTalking: Boolean = false,
)

/** What Settings › Voice's talking choices do. */
data class TalkingActions(val onTurns: (VoiceTurns) -> Unit = {}, val onInterrupt: (Boolean) -> Unit = {})

/** A language as dictation is told it ([code], ISO 639-1) and as its row names it. */
data class PhoneLanguage(val code: String, val name: String) {
    companion object {
        /** [code] named in its own language, e.g. "Français" for "fr". */
        fun of(code: String): PhoneLanguage {
            val locale = Locale.forLanguageTag(code)
            return PhoneLanguage(code, locale.getDisplayLanguage(locale).replaceFirstChar { it.uppercase(locale) })
        }
    }
}

@Composable
fun VoiceScreen(viewModel: SettingsViewModel, onBack: () -> Unit) {
    val settings by viewModel.state.collectAsStateWithLifecycle()
    val voices by viewModel.voices.collectAsStateWithLifecycle()
    VoiceContent(
        VoiceSettingsState(
            voices,
            settings.voice.name,
            settings.voice.captionYou,
            settings.voice.dictationLanguage,
            viewModel.phoneLanguage,
            settings.voice.turns,
            settings.voice.interruptByTalking,
        ),
        onVoice = viewModel::setVoice,
        onCaption = viewModel::setCaptionYou,
        onDictationLanguage = viewModel::setDictationLanguage,
        onBack = onBack,
        talking = TalkingActions(viewModel::setVoiceTurns, viewModel::setInterruptByTalking),
    )
}

/**
 * Settings › Voice (demo design §4.8, D100): the voice a model that talks speaks with (its first unless another is
 * chosen; a model without the chosen one speaks with its first), and whether voice mode captions what the person
 * says, which loads the model with a second slot; how a turn ends, and whether talking over a reply stops it (D147).
 * Two ruled parts (D157): voice mode's options, then dictation's, which belongs to the message bar.
 */
@Composable
fun VoiceContent(
    state: VoiceSettingsState,
    onVoice: (String) -> Unit,
    onCaption: (Boolean) -> Unit,
    onBack: () -> Unit,
    onDictationLanguage: (String?) -> Unit = {},
    talking: TalkingActions = TalkingActions(),
) {
    SettingsPage("Voice", onBack, Modifier.testTag("voice_settings")) {
        RuledLabel("Voice mode")
        Section("Its voice") {
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
        TalkingSection(state.turns, state.interruptByTalking, talking)
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
                        subtitle = "Uses less memory; your turns stay as voice messages; changing it reloads the model",
                        selected = !state.captionYou,
                        onClick = { onCaption(false) },
                    )
                }
            }
        }
        RuledLabel("In the message bar")
        Section("Dictation") {
            GroupedList {
                row {
                    ListRow(
                        "Automatic",
                        subtitle = "The model tells which language you speak",
                        selected = state.dictationLanguage == null,
                        onClick = { onDictationLanguage(null) },
                    )
                }
                row {
                    ListRow(
                        state.phoneLanguage.name,
                        subtitle = "Your phone's language",
                        selected = state.dictationLanguage == state.phoneLanguage.code,
                        onClick = { onDictationLanguage(state.phoneLanguage.code) },
                    )
                }
                // chosen before the phone's language changed: it stays, shown as chosen, until another is picked
                val other = state.dictationLanguage?.takeIf { it != state.phoneLanguage.code }
                if (other != null) {
                    row { ListRow(PhoneLanguage.of(other).name, selected = true, onClick = {}) }
                }
            }
            SectionNote("Tap the mic in a chat to dictate; hold it to send a voice message.")
        }
    }
}

/** How a voice-mode turn ends, and whether talking interrupts a reply (D147), each with its limits said plainly. */
@Composable
private fun TalkingSection(turns: VoiceTurns, interrupt: Boolean, actions: TalkingActions) {
    Section("Talking") {
        GroupedList {
            row {
                ListRow(
                    "Tap to talk",
                    subtitle = "Tap to start and tap to send, or hold while you talk",
                    selected = turns == VoiceTurns.TapToTalk,
                    onClick = { actions.onTurns(VoiceTurns.TapToTalk) },
                )
            }
            row {
                ListRow(
                    "Hands-free",
                    subtitle = "A pause of about a second sends; it listens again after each reply",
                    selected = turns == VoiceTurns.HandsFree,
                    onClick = { actions.onTurns(VoiceTurns.HandsFree) },
                )
            }
        }
        SectionNote(
            "Hands-free: a long pause ends your turn, and a noisy room can start one. Tap to send at once. " +
                "Changes apply the next time voice mode opens.",
        )
        GroupedList {
            row {
                SwitchRow(
                    "Interrupt by talking",
                    interrupt,
                    actions.onInterrupt,
                    subtitle =
                        "Talking over a reply stops it. On the speaker, a loud reply can stop itself; " +
                            "headphones avoid that.",
                )
            }
        }
    }
}
