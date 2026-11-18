package com.suhel.llamabro.demo.designsystem.samples

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.tooling.preview.Preview
import com.suhel.llamabro.demo.designsystem.components.VoicePhase
import com.suhel.llamabro.demo.designsystem.components.VoiceView
import com.suhel.llamabro.demo.designsystem.components.VoiceViewActions
import com.suhel.llamabro.demo.designsystem.components.VoiceViewState
import com.suhel.llamabro.demo.designsystem.theme.DemoTheme
import com.suhel.llamabro.demo.designsystem.theme.LocalAnimatorDurationScale

/** Voice mode's view (D100) in each phase, as on a phone; the orb holds still so the frames are stable. */
@Composable
internal fun VoiceModeSample(phase: VoicePhase) {
    val actions = VoiceViewActions({}, {}, {}, {})
    val asked = "Can you tell me a short story about a lighthouse keeper who finds a message in a bottle?"
    val state =
        when (phase) {
            VoicePhase.Ready -> {
                VoiceViewState(
                    "LFM3 Omni 2B",
                    "female",
                    phase,
                    note = "Voice starts fresh: it doesn't see the messages above.",
                )
            }

            VoicePhase.Listening -> {
                VoiceViewState("LFM3 Omni 2B", "female", phase, level = 0.6f)
            }

            VoicePhase.Thinking -> {
                VoiceViewState("LFM3 Omni 2B", "female", phase, userWords = asked)
            }

            VoicePhase.Speaking -> {
                VoiceViewState(
                    "LFM3 Omni 2B",
                    "female",
                    phase,
                    level = 0.4f,
                    userWords = asked,
                    caption =
                        "The lighthouse keeper discovers a bottle with a message inside. He reads it, and the keeper " +
                            "realizes the message is a warning about a storm coming tonight.",
                )
            }
        }
    CompositionLocalProvider(LocalAnimatorDurationScale provides 0f) { VoiceView(state, actions) }
}

@Preview(name = "Ready", widthDp = 360, heightDp = 780)
@Composable
private fun VoiceReadyPreview() = DemoTheme { VoiceModeSample(VoicePhase.Ready) }

@Preview(name = "Listening", widthDp = 360, heightDp = 780)
@Composable
private fun VoiceListeningPreview() = DemoTheme { VoiceModeSample(VoicePhase.Listening) }

@Preview(name = "Thinking", widthDp = 360, heightDp = 780)
@Composable
private fun VoiceThinkingPreview() = DemoTheme { VoiceModeSample(VoicePhase.Thinking) }

@Preview(name = "Speaking, light", widthDp = 360, heightDp = 780)
@Preview(
    name = "Speaking, dark",
    widthDp = 360,
    heightDp = 780,
    uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun VoiceSpeakingPreview() = DemoTheme { VoiceModeSample(VoicePhase.Speaking) }
