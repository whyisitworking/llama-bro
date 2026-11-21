package com.suhel.llamabro.demo.designsystem.components

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Voice mode's tap-to-talk (D100): a tapped turn is remembered while it listens and forgotten as it leaves. */
@RunWith(AndroidJUnit4::class)
class TurnTappedTest {
    @get:Rule
    val rule = createComposeRule()

    private var phase by mutableStateOf(VoicePhase.Ready)
    private lateinit var tapped: MutableState<Boolean>

    private fun show(start: VoicePhase) {
        phase = start
        rule.setContent { tapped = rememberTurnTapped(phase) }
        rule.waitForIdle()
    }

    private fun moveTo(next: VoicePhase) {
        rule.runOnIdle { phase = next }
        rule.waitForIdle()
    }

    @Test
    fun aTapStaysWhileTheTurnStartsAndListens() {
        show(VoicePhase.Ready)
        rule.runOnIdle { tapped.value = true }
        moveTo(VoicePhase.Listening)
        assertTrue(rule.runOnIdle { tapped.value })
    }

    @Test
    fun leavingListeningForgetsTheTap() {
        show(VoicePhase.Ready)
        rule.runOnIdle { tapped.value = true }
        moveTo(VoicePhase.Listening)
        moveTo(VoicePhase.Thinking)
        assertFalse(rule.runOnIdle { tapped.value })
    }

    @Test
    fun aTapFromOtherPhasesIsKeptUntilATurnListensAndLeaves() {
        show(VoicePhase.Ready)
        rule.runOnIdle { tapped.value = true }
        moveTo(VoicePhase.Speaking)
        assertTrue(rule.runOnIdle { tapped.value })
        moveTo(VoicePhase.Listening)
        assertTrue(rule.runOnIdle { tapped.value })
        moveTo(VoicePhase.Thinking)
        assertFalse(rule.runOnIdle { tapped.value })
    }

    @Test
    fun theNextTurnAfterAResetStartsAsAHold() {
        show(VoicePhase.Ready)
        rule.runOnIdle { tapped.value = true }
        moveTo(VoicePhase.Listening)
        moveTo(VoicePhase.Thinking)
        moveTo(VoicePhase.Listening)
        assertFalse(rule.runOnIdle { tapped.value })
    }

    @Test
    fun openingWhileListeningForgetsNothingUntilItLeaves() {
        show(VoicePhase.Listening)
        rule.runOnIdle { tapped.value = true }
        assertTrue(rule.runOnIdle { tapped.value })
        moveTo(VoicePhase.Ready)
        assertFalse(rule.runOnIdle { tapped.value })
    }
}
