package com.suhel.llamabro.demo.chat

import com.suhel.llamabro.LlamaBroException
import com.suhel.llamabro.speak
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Reads a reply aloud on a model that speaks (D141): its text through `Model.speak`, in the voice chosen in Settings ›
 * Voice when the model has it, played as it is made. One reply at a time; reading another, or the same one again,
 * stops the one playing, and a new reading begins only once the one before it has ended, so two holds on the model and
 * two speakers never coexist. The chat's model is held while it reads.
 *
 * An actor ([Steps]): [toggle] and [stop] are steps on its one thread, and the reading runs there too; the speaker is
 * opened and written on [io].
 */
class ReadAloud(
    private val scope: CoroutineScope,
    /** A hold on the chat's model; null when it can't be had. */
    private val hold: suspend () -> ModelHold?,
    /** The voice chosen in Settings › Voice, or null. */
    private val voice: suspend () -> String?,
    /** Where speech at a rate plays. */
    private val output: (rate: Int) -> SpeechOutput,
    /** A plain line when the reply couldn't be read. */
    private val problem: (String) -> Unit,
    /** Where the speech is written: `AudioTrack.write` blocks while its buffer is full, which paces it. */
    private val io: CoroutineDispatcher = Dispatchers.IO,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val steps = Steps(scope, dispatcher)

    // external event: the reply being read, as it plays
    private val _reading = MutableStateFlow<Long?>(null)

    /** The message being read aloud, or null. */
    val reading: StateFlow<Long?> = _reading.asStateFlow()

    /** The reading launched last, which may still be ending, and its speaker once it has one. */
    private var job: Job? = null
    private var speaker: SpeechOutput? = null

    /** Reads message [id]'s [text], or stops it when it is the one being read; join the step to wait for it. */
    fun toggle(id: Long, text: String): Job =
        steps.run {
            val same = _reading.value == id
            // the reading before ends, its hold let go and its speaker closed, before this one asks for its own (OQ3)
            endReading()
            if (same) return@run
            _reading.value = id
            // LAZY: the job is known as the latest before it runs, even on a dispatcher that would run it inline
            val reading = scope.launch(steps.dispatcher, start = CoroutineStart.LAZY) { read(id, text) }
            job = reading
            reading.start()
        }

    /** Stops the reading; join the step to wait until its hold and speaker are let go. */
    fun stop(): Job = steps.run { endReading() }

    private suspend fun endReading() {
        _reading.value = null
        val reading = job ?: return
        reading.cancel()
        // releases a write waiting on a full buffer, so the reading sees its cancellation now, not a buffer later
        speaker?.stop()
        reading.join()
    }

    private suspend fun read(id: Long, text: String) {
        val me = currentCoroutineContext().job
        var out: SpeechOutput? = null
        var held: ModelHold? = null
        try {
            held = holdUncancelled(hold) ?: return
            val chosen = voice()?.takeIf { it in held.model.info.voices }
            held.model.speak(spoken(text)) { this.voice = chosen }.collect { audio ->
                val track = out ?: open(audio.sampleRate).also { out = it }
                speaker = track
                withContext(io) { track.write(audio.pcm) }
            }
            out?.drain()
        } catch (e: CancellationException) {
            out?.stop()
            throw e
        } catch (expected: LlamaBroException) {
            problem("That reply couldn't be read aloud.")
        } finally {
            out?.close()
            held?.release()
            if (job === me) {
                speaker = null
                if (_reading.value == id) _reading.value = null
            }
        }
    }

    /** A speaker at [rate], opened on [io]; a reading cancelled meanwhile closes it rather than losing it. */
    private suspend fun open(rate: Int): SpeechOutput {
        var opened: SpeechOutput? = null
        try {
            withContext(io + NonCancellable) { opened = output(rate) }
        } catch (e: CancellationException) {
            opened?.close()
            throw e
        }
        return checkNotNull(opened)
    }

    companion object {
        private val codeBlock = Regex("```.*?```", RegexOption.DOT_MATCHES_ALL)
        private val link = Regex("""\[([^\]]*)]\([^)]*\)""")

        // marks only at a word's edge, so snake_case and C# keep theirs; backticks anywhere
        private val emphasis = Regex("""`+|(?<!\w)[*_~]+|[*_~]+(?!\w)""")
        private val lineMarks = Regex("""(?m)^\s*[#>]+|\|""")
        private val bullet = Regex("""(?m)^\s*(?:[-+]|\d+[.)])\s+""")
        private val space = Regex("""\s+""")

        /** A reply's Markdown as it is said: code blocks left out, links as their text, list bullets and marks gone. */
        fun spoken(markdown: String): String =
            markdown
                .replace(codeBlock, " ")
                .replace(bullet, " ")
                .replace(link, "$1")
                .replace(emphasis, "")
                .replace(lineMarks, " ")
                .replace(space, " ")
                .trim()
    }
}
