package com.suhel.llamabro.demo.ui.chat

import com.suhel.llamabro.Model
import com.suhel.llamabro.demo.chat.Dictation
import com.suhel.llamabro.demo.chat.DictationState
import com.suhel.llamabro.demo.chat.RecordingFailure
import com.suhel.llamabro.demo.chat.VoiceHold
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
 * the draft when the person is done. A view model owns one. The model stays held while dictating, so it isn't released
 * under the transcription and a send after it finds it loaded.
 */
class DictationComposer(
    private val scope: CoroutineScope,
    /** A hold on the chat's model (`ChatHandle.holdModel`); null when it can't be had. */
    private val hold: suspend () -> VoiceHold?,
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

    private var job: Job? = null
    private var watcher: Job? = null
    private var dictation: Dictation? = null
    private var held: VoiceHold? = null

    init {
        // the owner went (its scope cancelled): a dictation under way must not keep the model held
        scope.coroutineContext[Job]?.invokeOnCompletion {
            dictation?.cancel()
            held?.release()
        }
    }

    /** Starts dictating, once the microphone is allowed; nothing happens while one is under way. */
    fun start() {
        if (_shown.value != null || !canDictate()) return
        _shown.value = DictationShown(preparing = true, DictationState())
        onUsed()
        job =
            scope.launch {
                val h = hold()
                if (h == null) {
                    _shown.value = null
                    return@launch
                }
                held = h
                val d = newDictation(h.model, language())
                dictation = d
                // in the owner's scope, not this job's: start() is over once listening began, and finish() waits for it
                watcher = scope.launch { d.state.collect { _shown.value = DictationShown(preparing = false, it) } }
                d.start { failure ->
                    problem(
                        when (failure) {
                            RecordingFailure.Microphone -> "The microphone couldn't be opened."
                            RecordingFailure.Storage -> "There's no room to listen. Free some space and try again."
                        },
                    )
                    scope.launch { end() }
                }
            }
    }

    /** Stops listening and writes the words into the draft; the screen being left does the same. */
    fun finish() {
        val running = job ?: return
        if (_shown.value?.dictation?.listening == false) return
        scope.launch {
            running.join()
            val d = dictation
            val text =
                try {
                    d?.finish()
                } catch (e: CancellationException) {
                    throw e
                } catch (ignored: Exception) {
                    problem("That couldn't be written down. Try again.")
                    null
                }
            end()
            if (!text.isNullOrBlank()) onText(text)
        }
    }

    /** Stops listening and throws the words away. */
    fun discard() {
        job?.cancel()
        dictation?.cancel()
        end()
    }

    private fun end() {
        watcher?.cancel()
        watcher = null
        held?.release()
        held = null
        dictation = null
        job = null
        _shown.value = null
    }
}
