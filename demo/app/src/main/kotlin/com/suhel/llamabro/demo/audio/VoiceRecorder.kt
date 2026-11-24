package com.suhel.llamabro.demo.audio

import com.suhel.llamabro.demo.data.AudioStore
import com.suhel.llamabro.demo.data.NewVoice
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * Where a recording's samples come from: 16-bit mono at [rate]. [read] fills the buffer and returns how many samples it
 * wrote, or a negative number when the source can't go on (the microphone was taken by a call or another app).
 */
interface SampleSource : AutoCloseable {
    val rate: Int

    fun read(buffer: ShortArray): Int
}

/** Why a recording couldn't start or go on. */
enum class RecordingFailure {
    /** The microphone couldn't be opened. */
    Microphone,

    /** The recording couldn't be written: the phone's storage is full or failing. Nothing of it is kept. */
    Storage,
}

/** A voice message being recorded or waiting to be sent (D76): its file, its length so far, its levels, [live]. */
data class Take(val name: String, val millis: Long, val levels: List<Float>, val live: Boolean)

/**
 * Records voice messages into the [store] as WAV, 16-bit mono at the source's rate (16 kHz from the microphone),
 * one at a time (D76). A recording stops by itself at [limitMillis] and when the source fails, and is then kept, never
 * sent by itself; [stop] does the same when the screen is left. [finish] closes the file for sending; [discard] deletes
 * it.
 */
class VoiceRecorder(
    private val store: AudioStore,
    private val open: () -> SampleSource,
    private val scope: CoroutineScope,
    private val limitMillis: Long = LIMIT_MILLIS,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    // external event: the microphone's recording, as the audio arrives
    private val _take = MutableStateFlow<Take?>(null)

    /** The recording in progress or waiting, or null. */
    val take: StateFlow<Take?> = _take.asStateFlow()

    private var job: Job? = null

    /**
     * Starts recording; false when one is already under way or waiting. The microphone and the file are opened off the
     * caller's thread; when the microphone can't be opened or the file can't be written, the take goes and [onFailure]
     * says which.
     */
    fun start(onFailure: (RecordingFailure) -> Unit = {}): Boolean {
        if (_take.value != null) return false
        val name = store.newName()
        _take.value = Take(name, 0, emptyList(), live = true)
        job =
            scope.launch(io) {
                val source =
                    try {
                        open()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (ignored: Exception) {
                        _take.value = null
                        onFailure(RecordingFailure.Microphone)
                        return@launch
                    }
                try {
                    record(source, store.file(name))
                } catch (ignored: IOException) {
                    store.file(name).delete()
                    _take.value = null
                    onFailure(RecordingFailure.Storage)
                }
            }
        return true
    }

    private suspend fun record(source: SampleSource, file: File) {
        val wav =
            try {
                WavWriter(file, source.rate)
            } catch (e: IOException) {
                source.close()
                throw e
            }
        try {
            // a source that fails ends the recording, which is kept; so do a stop and the limit, after the block read
            source
                .blocks(io) { _take.value?.live == true }
                .transformWhile { chunk ->
                    emit(chunk)
                    wav.millis < limitMillis
                }.collect { chunk -> take(wav, chunk) }
        } finally {
            withContext(NonCancellable) {
                wav.close()
                _take.update { it?.copy(live = false) }
            }
        }
    }

    /** Writes [chunk] just read and shows it. */
    private fun take(wav: WavWriter, chunk: ShortArray) {
        wav.write(chunk)
        val millis = wav.millis
        val level = level(chunk, chunk.size)
        // atomic, so a stop from another thread (live = false) is never written over
        _take.update { it?.copy(millis = millis, levels = it.levels + level) }
    }

    /** Stops recording and keeps what was recorded, to be sent or discarded. */
    suspend fun stop() {
        _take.update { it?.copy(live = false) }
        job?.join()
    }

    /**
     * Stops recording and hands the finished file over for sending, its levels cut to [BUBBLE_LEVELS] for the bubble;
     * the take stays until [clear], so a send that is refused keeps it. Null when nothing was recorded.
     */
    suspend fun finish(): NewVoice? {
        stop()
        val take = _take.value ?: return null
        if (take.millis == 0L) return null
        return NewVoice(take.name, take.millis, peaks(take.levels, BUBBLE_LEVELS))
    }

    /** Forgets the take once it was sent: its file now belongs to its message. */
    fun clear() {
        _take.value = null
    }

    /** Stops recording and deletes the recording. */
    suspend fun discard() {
        stop()
        val take = _take.value ?: return
        _take.value = null
        store.delete(listOf(take.name))
    }

    companion object {
        /** The longest voice message (D76): about 1,500 tokens at 12.6 a second (F152). */
        const val LIMIT_MILLIS = 120_000L

        /** The last seconds, when the time turns ink. */
        const val NEAR_END_MILLIS = 10_000L

        /** How many levels a second of recording draws, live. */
        const val LEVELS_PER_SECOND = 20

        /** How many levels a sent voice message keeps for its bubble. */
        const val BUBBLE_LEVELS = 64

        private const val QUIET_DB = 50.0

        /** A block's loudness, 0 (50 dB below full scale or quieter) to 1 (full scale). */
        internal fun level(buffer: ShortArray, n: Int): Float =
            ((Levels.rmsToDb(Levels.rms(buffer, n)) + QUIET_DB) / QUIET_DB).coerceIn(0.0, 1.0).toFloat()

        /** A [level] back to dBFS; 0 reads as 50 dB below full scale. */
        internal fun levelDb(level: Float): Double = level * QUIET_DB - QUIET_DB

        /** [levels] as at most [count] peaks, each the loudest of its share. */
        internal fun peaks(levels: List<Float>, count: Int): List<Float> =
            if (levels.size <= count) {
                levels
            } else {
                List(count) { i -> levels.subList(i * levels.size / count, (i + 1) * levels.size / count).max() }
            }
    }
}
