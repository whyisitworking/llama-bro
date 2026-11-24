package com.suhel.llamabro.demo.ui.chat

import com.suhel.llamabro.demo.audio.RecordingFailure
import com.suhel.llamabro.demo.audio.Take
import com.suhel.llamabro.demo.audio.VoiceRecorder
import com.suhel.llamabro.demo.chat.ChatEngine
import com.suhel.llamabro.demo.chat.Limit
import com.suhel.llamabro.demo.chat.Limits
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The message bar's voice messages (D76): recording one for a model that hears, keeping it when the screen is left,
 * and sending it, with the photos in the bar, as the message. A view model owns one, over its chat's [handle].
 */
class VoiceComposer(
    private val recorder: VoiceRecorder,
    private val handle: ChatEngine.ChatHandle,
    private val scope: CoroutineScope,
    /** Whether a recording may start now: the model hears and no message is being edited. */
    private val canRecord: () -> Boolean,
    /** The bar's own send, so a voice message and a typed one never go at once, and the photos it carries. */
    private val sending: Sending,
    /** The photos in the bar, which go with the voice message; [onSent] gets the ones that went. */
    private val photos: () -> List<String>,
    private val onSent: (photos: List<String>) -> Unit,
    /** A plain line when the microphone can't be opened or a voice message can't be sent. */
    private val problem: (String) -> Unit,
) {
    /** The recording under way or waiting. */
    val take: StateFlow<Take?> get() = recorder.take

    // external event: the microphone permission refused
    private val _limit = MutableStateFlow<Limit?>(null)

    /** The microphone was refused: its line, until the next recording starts. */
    val limit: StateFlow<Limit?> = _limit.asStateFlow()

    /** Starts a recording, once the microphone is allowed, with no reply coming and nothing recorded yet. */
    fun start() {
        if (!canRecord() || handle.busy.value) return
        _limit.value = null
        recorder.start(
            onFailure = { failure ->
                problem(
                    when (failure) {
                        RecordingFailure.Microphone -> "The microphone couldn't be opened."
                        RecordingFailure.Storage -> "The recording couldn't be saved. Free some space and try again."
                    },
                )
            },
        )
    }

    /** The microphone permission was refused: a line with the way to Android's settings for the app. */
    fun refused() {
        _limit.value = Limits.microphone()
    }

    /** Stops recording and keeps the recording in the bar (the screen was left). */
    fun pause() {
        if (recorder.take.value?.live == true) scope.launch { recorder.stop() }
    }

    fun discard() {
        scope.launch { recorder.discard() }
    }

    /** Sends the recording as a voice message; when the engine refuses it, the recording stays in the bar. */
    fun send() {
        if (!sending.tryStart()) return
        scope.launch {
            try {
                val voice = recorder.finish()
                if (voice == null) {
                    recorder.discard()
                    return@launch
                }
                val sent = photos().also(sending::carry)
                if (handle.send("", sent, voice)) {
                    recorder.clear()
                    onSent(sent)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (ignored: Exception) {
                // as the typed send: the database refused the message (its chat went meanwhile), so the recording
                // stays in the bar and the bar says so, rather than the app crashing
                problem("That message couldn't be sent.")
            } finally {
                sending.end()
            }
        }
    }
}
