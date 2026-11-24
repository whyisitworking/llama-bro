package com.suhel.llamabro.demo.voice

import com.suhel.llamabro.LlamaBroException
import com.suhel.llamabro.MediaSource
import com.suhel.llamabro.Model
import com.suhel.llamabro.audio.TranscriptionEvent
import com.suhel.llamabro.audio.TranscriptionSession
import com.suhel.llamabro.demo.audio.Levels
import com.suhel.llamabro.demo.audio.MILLIS_PER_SECOND
import com.suhel.llamabro.demo.audio.Microphone
import com.suhel.llamabro.demo.audio.PauseDetector
import com.suhel.llamabro.demo.audio.RecordingFailure
import com.suhel.llamabro.demo.audio.SampleSource
import com.suhel.llamabro.demo.audio.VoiceRecorder
import com.suhel.llamabro.demo.audio.Wav
import com.suhel.llamabro.demo.audio.WavWriter
import com.suhel.llamabro.demo.audio.blocks
import com.suhel.llamabro.transcribe
import com.suhel.llamabro.transcriptionSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException

/** How dictation turns speech into text, once it knows (D140). */
enum class DictationRoute {
    /** Upstream's live transcription: text streams while the person speaks (LFM3). */
    Live,

    /** A realtime session transcribing per commit: a piece at each pause. */
    PerPause,

    /** No realtime session for this model (Qwen3-ASR): each piece goes to `transcribe`. */
    Pieces,
}

/** What the message bar shows while dictating: the text so far, the live levels, the time, and the stage. */
data class DictationState(
    val text: String = "",
    /** The newest levels, at most as many as the strip draws. */
    val levels: List<Float> = emptyList(),
    val millis: Long = 0,
    /** Still listening; false once stopped, while the last text comes in. */
    val listening: Boolean = true,
    /** Known once the realtime session answered or was refused. */
    val route: DictationRoute? = null,
)

/** How a dictation ended. */
sealed interface DictationResult {
    /** The words heard; empty when nothing was said. */
    data class Heard(val text: String) : DictationResult

    /** Speech was recorded, but the model couldn't write any of it down: its transcription failed. */
    data object TranscriptionFailed : DictationResult

    /** The microphone or the recording failed; `start`'s `onFailure` said which. */
    data object RecordingFailed : DictationResult

    /** `cancel` threw the dictation away before it finished: there is nothing to say. */
    data object Discarded : DictationResult
}

/**
 * Speech to text into the message bar (D140), as llama.cpp's web client dictates (b12653 ui/lib/voice/src/session.ts):
 * the microphone's audio goes to a realtime transcription session when the model has one, and the text streams in (or
 * comes a piece at each pause); a model without one, or whose session fails, gets each piece transcribed over
 * `transcribe`. When the person stops, a recording cut into pieces is transcribed once more as a whole, punctuated by
 * meaning rather than by the pauses, and that text replaces the pieces'. The recording is kept in [clip] while it runs
 * and deleted at the end. A recording stops at [limitMillis], which is also upstream's longest whole pass (2 minutes).
 *
 * An actor ([Steps]): [start], [finish] and [cancel] are steps on its one thread, and everything below is touched only
 * there. The microphone is read and the recording written on [io], by a reader that owns both and hands each block over
 * through a channel; it closes the channel at its end, with the reason when the recording failed. The actor tells it to
 * stop through a state flow, the one thing both sides touch.
 *
 * The transcription's `prompt` is left unset: upstream puts it in place of the model's own instruction (D138).
 */
class Dictation(
    private val model: Model,
    private val open: () -> SampleSource,
    private val clip: File,
    private val scope: CoroutineScope,
    private val language: String? = null,
    private val limitMillis: Long = VoiceRecorder.LIMIT_MILLIS,
    /** How long the last piece may take to come back before the text so far stands. */
    private val finishMillis: Long = FINISH_MILLIS,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
    /** Opens [model]'s realtime transcription session for audio at a rate; tests pass their own to hold it open. */
    private val openSession: suspend (rate: Int) -> TranscriptionSession = { rate ->
        val spoken = language
        model.transcriptionSession {
            this.language = spoken
            inputSampleRate = rate
        }
    },
    /** Transcribes a clip with [model]; tests pass their own to hold it. */
    private val transcribeClip: suspend (MediaSource) -> String = { source ->
        val spoken = language
        model.transcribe(source) { this.language = spoken }.text
    },
) {
    private val _state = MutableStateFlow(DictationState())
    val state: StateFlow<DictationState> = _state.asStateFlow()

    private val steps = Steps(scope, dispatcher)
    private val confined = steps.dispatcher
    private val chunks = Channel<ShortArray>(Channel.UNLIMITED)
    private var started = false
    private var reader: Job? = null
    private var processor: Job? = null
    private var connector: Job? = null
    private var collector: Job? = null

    private var stopped = false
    private var discarded = false

    // the one word the actor sends the reader: stop after the block being read, which is kept
    private val recording = MutableStateFlow(true)
    private var failure: RecordingFailure? = null
    private var onFailure: (RecordingFailure) -> Unit = {}
    private var finishing: Deferred<DictationResult>? = null

    // the recording, as the processor has taken it
    private var rate = 0
    private var samples = 0L

    // the realtime session, once it answered; refused or failed, the pieces go to transcribe
    private var session: TranscriptionSession? = null
    private var sessionFailed = false
    private val pending = mutableListOf<ShortArray>()

    // the text: a realtime session's items in order, or the pieces' text
    private val items = LinkedHashMap<String, String>()
    private var piecesText = ""
    private var inFlight = 0
    private var piecesCommitted = 0
    private var failedCommits = 0
    private var audioSinceCommit = false

    // upstream starts it true: until a piece is cut, what was said counts as speech
    private var speechSinceCommit = true
    private val idle = MutableStateFlow(true)

    // pieces for a model without a realtime session
    private val piece = ByteArrayOutputStream()
    private var pieceJob: Job? = null
    private var pauses: PauseDetector? = null

    /** The reader's end when the recording failed: the reason travels with the channel's close. */
    private class CaptureFailed(val why: RecordingFailure) : Exception()

    /**
     * Starts listening: the microphone, the recording and the realtime session. When the microphone can't be opened or
     * the recording can't be written, [onFailure] (on the dictation's own thread) says which and nothing goes on.
     */
    fun start(onFailure: (RecordingFailure) -> Unit = {}) {
        steps.run {
            if (started || stopped) return@run
            started = true
            this@Dictation.onFailure = onFailure
            val (source, wav) = openCapture() ?: return@run
            rate = source.rate
            pauses = PauseDetector.forPieces((MILLIS_PER_SECOND / VoiceRecorder.LEVELS_PER_SECOND).toInt())
            // the session is told the source's own rate, so its audio and the recording's agree
            connector = scope.launch(confined) { connect(source.rate) }
            processor = scope.launch(confined) { process() }
            reader = startReader(source, wav)
        }
    }

    /** The microphone and the recording, opened on [io]; null when either couldn't be, after [fail] said which. */
    private suspend fun openCapture(): Pair<SampleSource, WavWriter>? {
        var opened: Pair<SampleSource, WavWriter>? = null
        var why: RecordingFailure? = null
        try {
            // no timeout: opening the microphone and a file returns or throws, and a bound would only leak them
            withContext(io + NonCancellable) {
                val source =
                    try {
                        open()
                    } catch (ignored: Exception) {
                        why = RecordingFailure.Microphone
                        return@withContext
                    }
                try {
                    opened = source to WavWriter(clip, source.rate)
                } catch (ignored: Exception) {
                    // whatever the file threw, the microphone mustn't stay open
                    source.close()
                    why = RecordingFailure.Storage
                }
            }
        } catch (e: CancellationException) {
            // cancelled while they opened: what was opened is closed here, as nothing else will
            opened?.let { (source, wav) ->
                source.close()
                runCatching { wav.close() }
            }
            throw e
        }
        why?.let(::fail)
        return opened
    }

    /**
     * The reader, on [io]: each block into the recording, then to [process]. Started ATOMIC, so even one cancelled
     * before it runs takes the microphone and the file over and closes them.
     */
    @OptIn(DelicateCoroutinesApi::class)
    private fun startReader(source: SampleSource, wav: WavWriter): Job =
        scope.launch(io, start = CoroutineStart.ATOMIC) {
            try {
                wav.use {
                    source
                        .blocks(io) { recording.value }
                        .transformWhile { chunk ->
                            emit(chunk)
                            wav.millis < limitMillis
                        }.collect { chunk ->
                            wav.write(chunk)
                            chunks.send(chunk)
                        }
                }
            } catch (ignored: IOException) {
                chunks.close(CaptureFailed(RecordingFailure.Storage))
            } finally {
                chunks.close()
            }
        }

    /** Takes the reader's blocks, in order, until its end. */
    private suspend fun process() {
        try {
            for (chunk in chunks) take(chunk)
        } catch (failed: CaptureFailed) {
            fail(failed.why)
        }
    }

    private fun fail(why: RecordingFailure) {
        if (failure != null) return
        failure = why
        stopped = true
        onFailure(why)
    }

    /** One block from the microphone: into the levels and the transcription. */
    private fun take(chunk: ShortArray) {
        samples += chunk.size
        val level = VoiceRecorder.level(chunk, chunk.size)
        _state.update {
            it.copy(levels = (it.levels + level).takeLast(MAX_LEVELS), millis = samples * MILLIS_PER_SECOND / rate)
        }
        val live = session
        when {
            live != null -> {
                live.append(chunk)
                audioSinceCommit = true
            }

            sessionFailed -> {
                piece.write(Wav.bytes(chunk))
            }

            else -> {
                // still connecting: kept, so nothing said meanwhile is lost whichever way it goes
                pending += chunk
                piece.write(Wav.bytes(chunk))
            }
        }
        val detector = pauses ?: return
        val atPause = detector.push(Levels.detectorDb(chunk, chunk.size))
        if (detector.heardSpeech) speechSinceCommit = true
        if (atPause && cut()) speechSinceCommit = false
    }

    /** A place to cut: commits a piece to a session that transcribes per commit, or sends one to `transcribe`. */
    private fun cut(): Boolean {
        val live = session
        return when {
            live != null && !live.isLive -> commitPiece(live)
            sessionFailed -> sendPiece()
            else -> false
        }
    }

    private fun commitPiece(live: TranscriptionSession): Boolean {
        if (!audioSinceCommit || inFlight > 0) return false
        audioSinceCommit = false
        inFlight++
        piecesCommitted++
        idle.value = false
        live.commit()
        return true
    }

    private fun sendPiece(): Boolean {
        if (pieceJob?.isActive == true || piece.size() == 0) return false
        val wavBytes = Wav.file(piece.toByteArray(), rate)
        piece.reset()
        pieceJob =
            scope.launch(confined) {
                val text = transcribe(MediaSource.bytes(wavBytes)) ?: return@launch
                piecesText = Transcripts.join(piecesText, text)
                showText()
            }
        return true
    }

    /** Opens the realtime session; a model without one goes on in pieces (upstream's HTTP fallback). */
    private suspend fun connect(sourceRate: Int) {
        val opened =
            try {
                openSession(sourceRate)
            } catch (e: CancellationException) {
                throw e
            } catch (expected: LlamaBroException) {
                null
            }
        if (opened == null) {
            fallBack()
            return
        }
        if (stopped) {
            opened.close()
            return
        }
        session = opened
        // the session has it all from here
        piece.reset()
        for (chunk in pending) opened.append(chunk)
        audioSinceCommit = pending.isNotEmpty()
        pending.clear()
        _state.update { it.copy(route = if (opened.isLive) DictationRoute.Live else DictationRoute.PerPause) }
        collector =
            scope.launch(confined) {
                try {
                    opened.events.collect(::onEvent)
                } catch (e: CancellationException) {
                    throw e
                } catch (expected: LlamaBroException) {
                    // the session failed (the model closed under it): what it wrote stands, the rest goes in pieces
                    piecesText = sessionText()
                    opened.close()
                    session = null
                    fallBack()
                }
            }
    }

    /** No realtime session (refused, or failed): the pieces go to `transcribe`, as upstream's client does. */
    private fun fallBack() {
        sessionFailed = true
        pending.clear()
        inFlight = 0
        idle.value = true
        _state.update { it.copy(route = DictationRoute.Pieces) }
    }

    private fun onEvent(event: TranscriptionEvent) {
        when (event) {
            is TranscriptionEvent.Text -> {
                items[event.itemId] = items[event.itemId].orEmpty() + event.delta
                showText()
            }

            is TranscriptionEvent.Done -> {
                items[event.itemId] = event.transcript
                settle()
                showText()
            }

            is TranscriptionEvent.Error -> {
                // while a commit is out, the answer to it: nothing is on its way for it, and unless the commit was
                // empty, its transcription failed. An error with no commit out (a bad event) answers none
                if (inFlight > 0) {
                    if (event.type != COMMIT_EMPTY) failedCommits++
                    settle()
                }
            }

            else -> {
                Unit
            }
        }
    }

    private fun settle() {
        inFlight = (inFlight - 1).coerceAtLeast(0)
        if (inFlight == 0) idle.value = true
    }

    private fun sessionText(): String =
        items.values.fold("") { all, item -> Transcripts.join(all, Transcripts.clean(item).trim()) }

    private fun showText() {
        val text = if (session != null) sessionText() else piecesText
        _state.update { it.copy(text = text) }
    }

    /**
     * Stops listening and returns how it ended: the whole text, the last pass over the recording included (empty when
     * nothing was heard, or under a fifth of a second was); a transcription that failed with nothing written down; a
     * recording that failed; or [DictationResult.Discarded] once [cancel] has thrown it away (also when the dictation's
     * scope has ended). Calling it again returns the same answer until a [cancel], and Discarded after one.
     */
    suspend fun finish(): DictationResult {
        var answer: Deferred<DictationResult>? = null
        steps
            .run {
                answer =
                    if (discarded) {
                        CompletableDeferred(DictationResult.Discarded)
                    } else {
                        // the last steps run apart from the queue, so a cancel asked meanwhile still stops them
                        finishing ?: scope.async(confined) { stopAndWrite() }.also { finishing = it }
                    }
            }.join()
        val asked = answer ?: return DictationResult.Discarded
        return try {
            asked.await()
        } catch (e: CancellationException) {
            // the caller's own cancellation goes on; a cancel() of the dictation is an answer
            currentCoroutineContext().ensureActive()
            DictationResult.Discarded
        }
    }

    private suspend fun stopAndWrite(): DictationResult {
        stopCapture()
        if (failure != null) {
            discardAll()
            return DictationResult.RecordingFailed
        }
        _state.update { it.copy(listening = false) }
        if (samples == 0L || samples * MILLIS_PER_SECOND < TAP_MILLIS * rate) {
            // a quick double tap: nothing was said, and nothing goes to the model
            discardAll()
            _state.update { it.copy(text = "") }
            return DictationResult.Heard("")
        }
        // a session still opening isn't waited for: the whole recording is transcribed now anyway
        if (session == null && !sessionFailed) connector?.cancel() else connector?.join()
        val result = finalText()
        _state.update { it.copy(text = (result as? DictationResult.Heard)?.text.orEmpty()) }
        withContext(io + NonCancellable) { clip.delete() }
        return result
    }

    /** The reader stops after the block it is reading and closes the recording; every block it read has been taken. */
    private suspend fun stopCapture() {
        stopped = true
        recording.value = false
        reader?.join()
        // a dictation that never got to read has no reader to close it
        chunks.close()
        processor?.join()
    }

    private suspend fun finalText(): DictationResult {
        val live = session
        val whole: suspend () -> String? = { transcribe(MediaSource.file(clip.path)) }
        return when {
            live != null -> {
                // more than one piece: the text was cut at pauses, maybe inside a sentence
                val split = !live.isLive && piecesCommitted + (if (speechSinceCommit) 1 else 0) > 1
                val pieces = finishSession(live)
                val all = if (split) whole() else null
                when {
                    !all.isNullOrBlank() -> DictationResult.Heard(all)

                    pieces.isNotBlank() -> DictationResult.Heard(pieces)

                    // nothing written down, and a pass failed: the whole one, or (with none) a commit the session
                    // answered with an error. A whole pass that succeeded is trusted, empty or not
                    split && all == null -> DictationResult.TranscriptionFailed

                    !split && failedCommits > 0 -> DictationResult.TranscriptionFailed

                    else -> DictationResult.Heard(pieces)
                }
            }

            sessionFailed -> {
                // the whole clip now, punctuated by meaning; a piece still on its way would hold the model up
                pieceJob?.cancel()
                val all = whole()
                when {
                    all != null -> DictationResult.Heard(all)
                    piecesText.isNotEmpty() -> DictationResult.Heard(piecesText)
                    else -> DictationResult.TranscriptionFailed
                }
            }

            else -> {
                whole()?.let { DictationResult.Heard(it) } ?: DictationResult.TranscriptionFailed
            }
        }
    }

    /** The last piece and the text of all of them. A silent rest is left out: a model may hear a word in silence. */
    private suspend fun finishSession(live: TranscriptionSession): String {
        withTimeoutOrNull(finishMillis) {
            idle.first { it }
            val commitRest = live.isLive || speechSinceCommit || sessionText().isEmpty()
            if (audioSinceCommit && commitRest) {
                audioSinceCommit = false
                inFlight++
                idle.value = false
                live.finish()
                idle.first { it }
            }
        }
        collector?.cancel()
        live.close()
        return sessionText()
    }

    private suspend fun transcribe(source: MediaSource): String? =
        try {
            Transcripts.clean(transcribeClip(source)).trim()
        } catch (e: CancellationException) {
            throw e
        } catch (expected: LlamaBroException) {
            null
        }

    /**
     * Stops listening and throws everything away: the session, the pieces, the recording, and the last pass when
     * [finish] is under way. Join it to wait until nothing of it holds the model any more.
     */
    fun cancel(): Job =
        steps.run {
            stopped = true
            discarded = true
            finishing?.cancelAndJoin()
            discardAll()
        }

    private suspend fun discardAll() {
        stopCapture()
        // each waited for: once this returns, no session is opening and no transcription runs, so the hold may go
        connector?.cancelAndJoin()
        collector?.cancelAndJoin()
        pieceJob?.cancelAndJoin()
        session?.close()
        withContext(io + NonCancellable) { clip.delete() }
    }

    companion object {
        /** Final commit until the last completed event (upstream's VOICE_REALTIME_TIMEOUTS.FINISH_MS). */
        const val FINISH_MILLIS = 20_000L

        /** The levels the bar's strip can draw; older ones scroll off. */
        const val MAX_LEVELS = 200

        /** Under a fifth of a second is a tap, not something said (as in voice mode). */
        private const val TAP_MILLIS = 200L

        /** Upstream's refusal of a commit with no audio in it: nothing was said, nothing failed. */
        private const val COMMIT_EMPTY = "input_audio_buffer_commit_empty"
    }
}
