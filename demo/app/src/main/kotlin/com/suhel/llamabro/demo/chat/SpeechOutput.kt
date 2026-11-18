package com.suhel.llamabro.demo.chat

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import kotlinx.coroutines.delay
import java.util.ArrayDeque

/**
 * Where voice mode's replies are spoken (D100): 16-bit mono PCM written as it arrives, played in order. [level] is the
 * loudness (0 to 1) of what is playing now, for the orb. Calls come from one thread at a time.
 */
interface SpeechOutput : AutoCloseable {
    /** Queues [pcm] to play after what is already queued. */
    fun write(pcm: ShortArray)

    /** The loudness of the part playing now, 0 to 1; 0 when nothing plays. */
    val level: Float

    /** Suspends until everything written has played (or [stop] dropped it). */
    suspend fun drain()

    /** Stops at once and drops what was queued. */
    fun stop()
}

/**
 * [SpeechOutput] on an [AudioTrack] in streaming mode at [rate], speech for the assistant (`USAGE_ASSISTANT`), so it
 * plays on the loudspeaker or the connected headset, wherever the phone routes media. It asks for transient audio focus
 * while it plays.
 */
class TrackSpeech(context: Context, private val rate: Int) : SpeechOutput {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val attributes =
        AudioAttributes
            .Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
    private val focus =
        AudioFocusRequest
            .Builder(
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT,
            ).setAudioAttributes(attributes)
            .build()
    private val track =
        AudioTrack
            .Builder()
            .setAudioAttributes(attributes)
            .setAudioFormat(
                AudioFormat
                    .Builder()
                    .setSampleRate(rate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build(),
            ).setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(
                maxOf(
                    AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT),
                    rate, // half a second of 16-bit samples
                ),
            ).build()

    /** Frames written since the last stop, and each written chunk's end frame with its loudness. */
    @Volatile private var written = 0L
    private val chunks = ArrayDeque<Pair<Long, Float>>()

    @Volatile private var playing = false

    override fun write(pcm: ShortArray) {
        if (!playing) {
            audio.requestAudioFocus(focus)
            track.play()
            playing = true
        }
        written += pcm.size
        synchronized(chunks) { chunks.addLast(written to VoiceRecorder.level(pcm, pcm.size)) }
        // blocks while the track's buffer is full: the reply arrives ahead of playback (F170), so this paces it
        track.write(pcm, 0, pcm.size)
    }

    override val level: Float
        get() {
            if (!playing) return 0f
            val head = track.playbackHeadPosition.toLong() and FRAMES_MASK
            synchronized(chunks) {
                while (chunks.isNotEmpty() && chunks.first.first <= head) chunks.removeFirst()
                return chunks.firstOrNull()?.second ?: 0f
            }
        }

    override suspend fun drain() {
        while (playing && (track.playbackHeadPosition.toLong() and FRAMES_MASK) < written) delay(DRAIN_POLL_MILLIS)
        if (playing) stop()
    }

    override fun stop() {
        if (!playing) return
        playing = false
        track.pause()
        track.flush()
        track.stop()
        written = 0
        synchronized(chunks) { chunks.clear() }
        audio.abandonAudioFocusRequest(focus)
    }

    override fun close() {
        stop()
        track.release()
    }

    private companion object {
        const val DRAIN_POLL_MILLIS = 40L

        /** `playbackHeadPosition` is an unsigned 32-bit count that wraps; a reply is far shorter than its range. */
        const val FRAMES_MASK = 0xFFFF_FFFFL
    }
}
