package com.suhel.llamabro.demo.chat

import com.suhel.llamabro.demo.data.AudioStore
import com.suhel.llamabro.demo.data.NewVoice
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import kotlin.math.log10
import kotlin.math.sqrt

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
) {
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
            scope.launch(Dispatchers.IO) {
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
        file.parentFile?.mkdirs()
        source.use {
            RandomAccessFile(file, "rw").use { out ->
                val pcm = Pcm(out, source.rate)
                try {
                    var going = true
                    while (going && scope.isActive && _take.value?.live == true) {
                        val n = source.read(pcm.buffer)
                        // a source that fails ends the recording, which is kept; a read of nothing just goes on
                        going = n >= 0 && (n == 0 || take(pcm, n))
                    }
                } finally {
                    withContext(NonCancellable) {
                        pcm.finish()
                        _take.update { it?.copy(live = false) }
                    }
                }
            }
        }
    }

    /** Writes [n] samples just read and shows them; false once the recording reached its limit. */
    private fun take(pcm: Pcm, n: Int): Boolean {
        pcm.write(n)
        val millis = pcm.millis
        val level = level(pcm.buffer, n)
        // atomic, so a stop from another thread (live = false) is never written over
        _take.update { it?.copy(millis = millis, levels = it.levels + level) }
        return millis < limitMillis
    }

    /** 16-bit mono PCM written to [out] after a WAV header, which [finish] fills in once the length is known. */
    private class Pcm(private val out: RandomAccessFile, private val rate: Int) {
        /** One level's worth of samples, read into by the source. */
        val buffer = ShortArray(rate / LEVELS_PER_SECOND)
        private val bytes = ByteArray(buffer.size * 2)
        private var samples = 0L

        val millis: Long get() = samples * MILLIS_PER_SECOND / rate

        init {
            out.setLength(0)
            out.write(ByteArray(WAV_HEADER))
        }

        fun write(n: Int) {
            for (i in 0 until n) {
                val sample = buffer[i].toInt()
                bytes[2 * i] = sample.toByte()
                bytes[2 * i + 1] = (sample shr Byte.SIZE_BITS).toByte()
            }
            out.write(bytes, 0, 2 * n)
            samples += n
        }

        fun finish() = writeHeader(out, rate, samples)
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

        private const val WAV_HEADER = 44
        private const val MILLIS_PER_SECOND = 1_000L
        private const val QUIET_DB = 50.0
        private const val DB_PER_DECADE = 20.0

        /** A block's loudness, 0 (50 dB below full scale or quieter) to 1 (full scale). */
        internal fun level(buffer: ShortArray, n: Int): Float {
            var sum = 0.0
            for (i in 0 until n) sum += buffer[i].toDouble() * buffer[i]
            val rms = sqrt(sum / n) / Short.MAX_VALUE
            if (rms <= 0.0) return 0f
            return ((DB_PER_DECADE * log10(rms) + QUIET_DB) / QUIET_DB).coerceIn(0.0, 1.0).toFloat()
        }

        /** [levels] as at most [count] peaks, each the loudest of its share. */
        internal fun peaks(levels: List<Float>, count: Int): List<Float> =
            if (levels.size <= count) {
                levels
            } else {
                List(count) { i -> levels.subList(i * levels.size / count, (i + 1) * levels.size / count).max() }
            }

        /** The 44-byte header of a PCM WAV file: 16-bit mono at [rate], [samples] long. */
        internal fun writeHeader(out: RandomAccessFile, rate: Int, samples: Long) {
            val data = samples * 2
            val header =
                java.nio.ByteBuffer
                    .allocate(WAV_HEADER)
                    .order(java.nio.ByteOrder.LITTLE_ENDIAN)
                    .put("RIFF".toByteArray())
                    .putInt((36 + data).toInt())
                    .put("WAVE".toByteArray())
                    .put("fmt ".toByteArray())
                    .putInt(16)
                    .putShort(1)
                    .putShort(1)
                    .putInt(rate)
                    .putInt(rate * 2)
                    .putShort(2)
                    .putShort(16)
                    .put("data".toByteArray())
                    .putInt(data.toInt())
                    .array()
            out.seek(0)
            out.write(header)
        }
    }
}
