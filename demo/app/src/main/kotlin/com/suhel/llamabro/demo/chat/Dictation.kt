package com.suhel.llamabro.demo.chat

import com.suhel.llamabro.LlamaBroException
import com.suhel.llamabro.MediaSource
import com.suhel.llamabro.Model
import com.suhel.llamabro.transcribe
import com.suhel.llamabro.transcriptionSession
import com.suhel.llamabro.voice.TranscriptionEvent
import com.suhel.llamabro.voice.TranscriptionSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile

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
    val levels: List<Float> = emptyList(),
    val millis: Long = 0,
    /** Still listening; false once stopped, while the last text comes in. */
    val listening: Boolean = true,
    /** Known once the realtime session answered or was refused. */
    val route: DictationRoute? = null,
)

/**
 * Speech to text into the message bar (D140), as llama.cpp's web client dictates (b12653 ui/lib/voice/src/session.ts):
 * the microphone's audio goes to a realtime transcription session when the model has one, and the text streams in (or
 * comes a piece at each pause); a model without one gets each piece transcribed over `transcribe`. When the person
 * stops, a recording cut into pieces is transcribed once more as a whole, punctuated by meaning rather than by the
 * pauses, and that text replaces the pieces'. The recording is kept in [clip] while it runs, and deleted at the end.
 *
 * The transcription's `prompt` is left unset: upstream puts it in place of the model's own instruction (D138).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class Dictation(
    private val model: Model,
    private val open: () -> SampleSource,
    private val clip: File,
    private val scope: CoroutineScope,
    private val language: String? = null,
    private val limitMillis: Long = VoiceRecorder.LIMIT_MILLIS,
    /** How long the last piece may take to come back before the text so far stands. */
    private val finishMillis: Long = FINISH_MILLIS,
) {
    private val _state = MutableStateFlow(DictationState())
    val state: StateFlow<DictationState> = _state.asStateFlow()

    // every step below runs on this one thread: no locks
    private val serial = Dispatchers.IO.limitedParallelism(1)
    private val chunks = Channel<ShortArray>(Channel.UNLIMITED)
    private var reader: Job? = null
    private var processor: Job? = null
    private var connector: Job? = null

    @Volatile private var stopped = false
    private val failure = CompletableDeferred<RecordingFailure?>()

    // the recording
    private var wav: RandomAccessFile? = null
    private var rate = 0
    private var samples = 0L

    // the realtime session, once it answered
    private var session: TranscriptionSession? = null
    private var sessionFailed = false
    private val pending = mutableListOf<ShortArray>()

    // the text: a realtime session's items in order, or the pieces' text
    private val items = LinkedHashMap<String, String>()
    private var piecesText = ""
    private var inFlight = 0
    private var piecesCommitted = 0
    private var audioSinceCommit = false
    private var speechSinceCommit = true
    private val idle = MutableStateFlow(true)

    // pieces for a model without a realtime session
    private val piece = ByteArrayOutputStream()
    private var pieceJob: Job? = null
    private var pauses: PauseDetector? = null

    /**
     * Starts listening: the microphone, the recording and the realtime session. When the microphone can't be opened or
     * the recording can't be written, [onFailure] says which and nothing goes on.
     */
    fun start(onFailure: (RecordingFailure) -> Unit = {}) {
        reader =
            scope.launch(Dispatchers.IO) {
                val source =
                    try {
                        open()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (ignored: Exception) {
                        fail(RecordingFailure.Microphone, onFailure)
                        return@launch
                    }
                source.use { read(it, onFailure) }
            }
        connector = scope.launch(serial) { connect() }
    }

    private suspend fun read(source: SampleSource, onFailure: (RecordingFailure) -> Unit) {
        try {
            withContext(serial) { openClip(source.rate) }
        } catch (ignored: java.io.IOException) {
            fail(RecordingFailure.Storage, onFailure)
            return
        }
        processor = scope.launch(serial) { for (chunk in chunks) take(chunk) }
        val block = ShortArray(source.rate / VoiceRecorder.LEVELS_PER_SECOND)
        while (!stopped) {
            val n = source.read(block)
            if (n < 0) break
            if (n > 0) chunks.send(block.copyOf(n))
            if (_state.value.millis >= limitMillis) break
        }
        chunks.close()
    }

    private fun fail(why: RecordingFailure, onFailure: (RecordingFailure) -> Unit) {
        stopped = true
        failure.complete(why)
        chunks.close()
        onFailure(why)
    }

    private fun openClip(sourceRate: Int) {
        rate = sourceRate
        clip.parentFile?.mkdirs()
        wav =
            RandomAccessFile(clip, "rw").apply {
                setLength(0)
                write(ByteArray(Wav.HEADER))
            }
        pauses = PauseDetector.forPieces(MILLIS_PER_SECOND / VoiceRecorder.LEVELS_PER_SECOND)
    }

    /** One block from the microphone: into the recording, the levels, and the transcription. */
    private fun take(chunk: ShortArray) {
        wav?.write(Wav.bytes(chunk))
        samples += chunk.size
        _state.update {
            it.copy(levels = it.levels + VoiceRecorder.level(chunk, chunk.size), millis = samples * 1000 / rate)
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
            live != null && !live.live -> commitPiece(live)
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
            scope.launch(serial) {
                val text = transcribe(MediaSource.bytes(wavBytes)) ?: return@launch
                piecesText = Transcripts.join(piecesText, text)
                showText()
            }
        return true
    }

    /** Opens the realtime session; a model without one goes on in pieces (upstream's HTTP fallback). */
    private suspend fun connect() {
        val opened =
            try {
                model.transcriptionSession {
                    language = this@Dictation.language
                    inputSampleRate = RATE
                }
            } catch (e: CancellationException) {
                throw e
            } catch (expected: LlamaBroException) {
                null
            }
        if (stopped && opened != null) {
            opened.close()
            return
        }
        if (opened == null) {
            sessionFailed = true
            pending.clear()
            _state.update { it.copy(route = DictationRoute.Pieces) }
            return
        }
        session = opened
        // the socket has it all from here
        piece.reset()
        for (chunk in pending) opened.append(chunk)
        audioSinceCommit = pending.isNotEmpty()
        pending.clear()
        _state.update { it.copy(route = if (opened.live) DictationRoute.Live else DictationRoute.PerPause) }
        scope.launch(serial) {
            opened.events.collect(::onEvent)
        }
    }

    private fun onEvent(event: TranscriptionEvent) {
        when (event) {
            is TranscriptionEvent.Text -> {
                items[event.itemId] = items[event.itemId].orEmpty() + event.delta
                showText()
            }

            is TranscriptionEvent.Done -> {
                items[event.itemId] = event.transcript
                inFlight = (inFlight - 1).coerceAtLeast(0)
                if (inFlight == 0) idle.value = true
                showText()
            }

            is TranscriptionEvent.Error -> {
                // a refused commit: nothing is on its way for it
                inFlight = (inFlight - 1).coerceAtLeast(0)
                if (inFlight == 0) idle.value = true
            }

            else -> {
                Unit
            }
        }
    }

    private fun sessionText(): String =
        items.values.fold("") { all, item -> Transcripts.join(all, Transcripts.clean(item).trim()) }

    private fun showText() {
        val text = if (session != null) sessionText() else piecesText
        _state.update { it.copy(text = text) }
    }

    /**
     * Stops listening and returns the whole text, the last pass over the recording included; empty when nothing was
     * heard. Null when the microphone or the recording failed.
     */
    suspend fun finish(): String? {
        stopped = true
        reader?.join()
        processor?.join()
        if (failure.isCompleted) return null
        _state.update { it.copy(listening = false) }
        val text =
            withContext(serial) {
                closeClip()
                connector?.join()
                finalText()
            }
        _state.update { it.copy(text = text) }
        clip.delete()
        return text
    }

    private suspend fun finalText(): String {
        val live = session
        val millis = _state.value.millis
        val whole: suspend () -> String? = { transcribe(MediaSource.file(clip.path)) }
        return when {
            live != null -> {
                // more than one piece: the text was cut at pauses, maybe inside a sentence
                val split = !live.live && piecesCommitted + (if (speechSinceCommit) 1 else 0) > 1
                val pieces = finishSession(live)
                if (split &&
                    millis <= RETRANSCRIBE_MAX_MILLIS
                ) {
                    whole()?.takeIf { it.isNotBlank() } ?: pieces
                } else {
                    pieces
                }
            }

            sessionFailed -> {
                // the whole clip now, punctuated by meaning; a piece still on its way would hold the model up
                pieceJob?.cancel()
                whole() ?: piecesText
            }

            else -> {
                whole().orEmpty()
            }
        }
    }

    /** The last piece and the text of all of them. A silent rest is left out: a model may hear a word in silence. */
    private suspend fun finishSession(live: TranscriptionSession): String {
        withTimeoutOrNull(finishMillis) {
            idle.first { it }
            val commitRest = live.live || speechSinceCommit || sessionText().isEmpty()
            if (audioSinceCommit && commitRest) {
                inFlight++
                idle.value = false
                live.finish()
                idle.first { it }
            }
        }
        live.close()
        return sessionText()
    }

    private suspend fun transcribe(source: MediaSource): String? =
        try {
            Transcripts.clean(model.transcribe(source, language = language).text).trim()
        } catch (e: CancellationException) {
            throw e
        } catch (expected: LlamaBroException) {
            null
        }

    /** Stops listening and throws everything away: the session, the pieces and the recording. */
    fun cancel() {
        stopped = true
        scope.launch(NonCancellable + serial) {
            reader?.join()
            processor?.join()
            connector?.cancel()
            pieceJob?.cancel()
            session?.close()
            closeClip()
            clip.delete()
        }
    }

    private fun closeClip() {
        val out = wav ?: return
        wav = null
        out.use { VoiceRecorder.writeHeader(it, rate, samples) }
    }

    companion object {
        /** The microphone's rate, which the realtime session is told (16 kHz, D76). */
        const val RATE = 16_000

        /** Longest recording transcribed once more as a whole when it ends (upstream's VOICE_RETRANSCRIBE_MAX_MS). */
        const val RETRANSCRIBE_MAX_MILLIS = 120_000L

        /** Final commit until the last completed event (upstream's VOICE_REALTIME_TIMEOUTS.FINISH_MS). */
        const val FINISH_MILLIS = 20_000L

        private const val MILLIS_PER_SECOND = 1000
    }
}
