package com.suhel.llamabro.demo.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.suhel.llamabro.demo.chat.LimitAction
import com.suhel.llamabro.demo.designsystem.components.LimitLine
import com.suhel.llamabro.demo.designsystem.components.VoiceDialog
import com.suhel.llamabro.demo.designsystem.components.VoicePhase
import com.suhel.llamabro.demo.designsystem.components.VoiceViewActions
import com.suhel.llamabro.demo.designsystem.components.VoiceViewState
import com.suhel.llamabro.demo.voice.VoiceConversation
import com.suhel.llamabro.demo.voice.VoiceStage

/**
 * Voice mode over the chat (demo design §4.8, D100): [conversation]'s state in the design system's voice view, named
 * [model]. Leaving the screen (Home, a call, the lock) pauses it: recording and playback stop as Stop does and the
 * model is let go, and the next press opens it again, or, hands-free, coming back does (D147). A limit line's Retry
 * opens it again; its other actions go to [onLimitAction] (the app's settings for a refused microphone).
 */
@Composable
fun VoiceHost(
    conversation: VoiceConversation,
    model: String,
    startsFresh: Boolean,
    onClose: () -> Unit,
    onLimitAction: (LimitAction) -> Unit,
) {
    val ui by conversation.ui.collectAsStateWithLifecycle()
    val limit by conversation.limit.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { conversation.pause() }
    // hands-free listens again by itself on coming back; Tap to talk waits for the press (D147)
    LifecycleEventEffect(Lifecycle.Event.ON_START) { conversation.resume() }
    val actions =
        remember(conversation, onClose) {
            VoiceViewActions(onClose, conversation::talkStart, conversation::talkEnd, conversation::stop)
        }
    VoiceDialog(
        VoiceViewState(
            model,
            ui.voice,
            ui.phase.view(),
            ui.level,
            ui.userWords,
            ui.caption,
            FRESH_NOTE.takeIf { startsFresh },
            ui.sendsOnPause,
        ),
        actions,
        Modifier.testTag("voice_mode"),
        notice =
            limit?.let { line ->
                {
                    LimitLine(
                        line.line,
                        line.actionLabel,
                        onAction = {
                            if (line.action == LimitAction.Retry) conversation.retry() else onLimitAction(line.action)
                        },
                        modifier = Modifier.testTag("voice_limit"),
                    )
                }
            },
    )
}

/** Voice mode's open conversation over the chat, while there is one (D100). */
@Composable
fun VoiceOverlay(vm: ChatViewModel, model: String, onLimitAction: (LimitAction) -> Unit) {
    val open by vm.voiceMode.open.collectAsStateWithLifecycle()
    open?.let { VoiceHost(it.conversation, model, it.startsFresh, vm.voiceMode::close, onLimitAction) }
}

/** Upstream's conversation starts without the chat's earlier messages (D100): said once, quietly. */
private const val FRESH_NOTE = "Voice starts fresh: it doesn't see the messages above."

private fun VoiceStage.view(): VoicePhase =
    when (this) {
        VoiceStage.Ready -> VoicePhase.Ready
        VoiceStage.Listening -> VoicePhase.Listening
        VoiceStage.Thinking -> VoicePhase.Thinking
        VoiceStage.Speaking -> VoicePhase.Speaking
    }
