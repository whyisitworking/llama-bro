package com.suhel.llamabro.demo.designsystem.samples

import android.content.res.Configuration
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
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

/** Voice mode's four phases one under another, for the gallery: each view is a phone's height. */
@Composable
internal fun VoiceModeSamples() {
    Column {
        for (phase in VoicePhase.entries) {
            Box(Modifier.fillMaxWidth().height(VOICE_SAMPLE_HEIGHT.dp)) { VoiceModeSample(phase) }
        }
    }
}

private const val VOICE_SAMPLE_HEIGHT = 780

/** Light and dark at a phone's size. */
@Preview(name = "Light", widthDp = 360, heightDp = VOICE_SAMPLE_HEIGHT)
@Preview(
    name = "Dark",
    widthDp = 360,
    heightDp = VOICE_SAMPLE_HEIGHT,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
private annotation class VoiceModePreviews

@VoiceModePreviews
@Composable
private fun VoiceReadyPreview() = DemoTheme { VoiceModeSample(VoicePhase.Ready) }

@VoiceModePreviews
@Composable
private fun VoiceListeningPreview() = DemoTheme { VoiceModeSample(VoicePhase.Listening) }

@VoiceModePreviews
@Composable
private fun VoiceThinkingPreview() = DemoTheme { VoiceModeSample(VoicePhase.Thinking) }

@VoiceModePreviews
@Composable
private fun VoiceSpeakingPreview() = DemoTheme { VoiceModeSample(VoicePhase.Speaking) }
