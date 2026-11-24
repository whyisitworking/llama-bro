package com.suhel.llamabro.demo.ui.chat

import com.suhel.llamabro.Model
import com.suhel.llamabro.demo.chat.Dictation
import com.suhel.llamabro.demo.chat.DictationResult
import com.suhel.llamabro.demo.chat.DictationState
import com.suhel.llamabro.demo.chat.ModelHold
import com.suhel.llamabro.demo.chat.RecordingFailure
import com.suhel.llamabro.demo.chat.holdUncancelled
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Dictation as the bar shows it: [preparing] while the model gets ready, then the [dictation]'s own state. */
data class DictationShown(val preparing: Boolean, val dictation: DictationState)

/**
 * The message bar's dictation (D140): a tap on the mic holds the chat's model, listens, and writes what is heard into
 * the draft when the person is done. A view model owns one and calls [discard] when it is cleared. The model stays
 * held while dictating, so it isn't released under the transcription and a send after it finds it loaded.
 *
 * Confined to the main thread: it is called there, [scope] runs there, and its state is written only there. The
 * dictation's own callbacks, on its thread, only show a line and launch onto [scope].
 */
class DictationComposer(
    private val scope: CoroutineScope,
    /** A hold on the chat's model (`ChatHandle.holdModel`); null when it can't be had. */
    private val hold: suspend () -> ModelHold?,
    /** A dictation over the held model, in the language the person chose (null: the model tells). */
    private val newDictation: (Model, String?) -> Dictation,
    private val language: suspend () -> String?,
    /** Whether dictation may start now: the model hears, no reply is coming, no voice message is being recorded. */
    private val canDictate: () -> Boolean,
    /** The words heard, once done, for the draft. */
    private val onText: (String) -> Unit,
    /** Dictation was used: the mic's hint is no longer needed. */
    private val onUsed: () -> Unit,
    /** A plain line when the microphone can't be opened or nothing could be written down. */
    private val problem: (String) -> Unit,
) {
    // external event: the microphone's dictation as it goes
    private val _shown = MutableStateFlow<DictationShown?>(null)

    /** The dictation under way, or null. */
    val shown: StateFlow<DictationShown?> = _shown.asStateFlow()

    private var starting: Job? = null
    private var finishing: Job? = null
    private var watcher: Job? = null
    private var dictation: Dictation? = null
    private var held: ModelHold? = null

    /** Starts dictating, once the microphone is allowed; nothing happens while one is under way. */
    fun start() {
        if (_shown.value != null || !canDictate()) return
        _shown.value = DictationShown(preparing = true, DictationState())
        onUsed()
        starting =
            scope.launch {
                val h = holdUncancelled(hold) ?: return@launch end()
                held = h
                val d = newDictation(h.model, language())
                dictation = d
                // in the owner's scope, not this job's: start is over once listening began, and finish waits for it
                watcher = scope.launch { d.state.collect { _shown.value = DictationShown(preparing = false, it) } }
                d.start { failure ->
                    problem(
                        when (failure) {
                            RecordingFailure.Microphone -> "The microphone couldn't be opened."
                            RecordingFailure.Storage -> "There's no room to listen. Free some space and try again."
                        },
                    )
                    scope.launch { discard() }
                }
            }
    }

    /** Stops listening and writes the words into the draft, once however often it is asked. */
    fun finish() {
        val start = starting ?: return
        if (finishing != null) return
        finishing =
            scope.launch {
                start.join()
                val result =
                    try {
                        dictation?.finish()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (ignored: Exception) {
                        DictationResult.TranscriptionFailed
                    }
                end()
                when (result) {
                    is DictationResult.Heard -> if (result.text.isNotBlank()) onText(result.text)

                    DictationResult.TranscriptionFailed -> problem("That couldn't be written down. Try again.")

                    // the microphone or the recording failed: its own line said so already
                    // or it was thrown away, which says nothing
                    DictationResult.RecordingFailed, DictationResult.Discarded, null -> Unit
                }
            }
    }

    /** The screen was left: what was heard goes into the draft; a dictation still getting ready is dropped. */
    fun leave() {
        if (_shown.value?.preparing == true) discard() else finish()
    }

    /** Stops listening and throws the words away; the model is let go once the transcription has stopped. */
    fun discard() {
        starting?.cancel()
        finishing?.cancel()
        val d = dictation
        val h = held
        held = null
        end()
        // not in [scope]: the owner may be cleared, its scope already cancelled
        val stopping = d?.cancel()
        if (stopping == null) h?.release() else stopping.invokeOnCompletion { h?.release() }
    }

    private fun end() {
        watcher?.cancel()
        held?.release()
        held = null
        watcher = null
        dictation = null
        starting = null
        finishing = null
        _shown.value = null
    }
}
