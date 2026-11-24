package com.suhel.llamabro.demo.audio

import android.Manifest
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.annotation.RequiresPermission

/**
 * The phone's microphone as a [SampleSource]: 16 kHz mono, 16-bit, the rate the speech models take (D76). It holds
 * transient exclusive audio focus while open, or works under voice mode's [session] focus (D147); losing it (a call,
 * another app recording) ends the source, so the recording stops and is kept.
 */
class Microphone
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    constructor(
        context: Context,
        /**
         * Android's audio source: speech recognition's for a voice message (D76), the voice call's for voice mode,
         * whose echo cancellation and noise suppression keep the reply out of the microphone (D100).
         */
        private val source: Int = MediaRecorder.AudioSource.VOICE_RECOGNITION,
        /** Voice mode's focus, held for its whole session; null asks for the microphone's own. */
        private val session: SessionFocus? = null,
    ) : SampleSource {
        override val rate: Int = RATE

        private val audio = context.getSystemService(AudioManager::class.java)

        @Volatile private var lost = false

        private val focus: AudioFocusRequest? =
            if (session != null) {
                null
            } else {
                AudioFocusRequest
                    .Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                    .setAudioAttributes(
                        AudioAttributes
                            .Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANT)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build(),
                    ).setOnAudioFocusChangeListener { change ->
                        if (change < 0) lost = true
                    }.build()
            }

        private val record: AudioRecord

        init {
            val granted =
                if (focus != null) {
                    audio.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
                } else {
                    session?.lost != true
                }
            check(granted) { "no audio focus" }
            val min =
                AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            record =
                AudioRecord(
                    source,
                    RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    maxOf(min, RATE / 2),
                )
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                record.release()
                focus?.let { audio.abandonAudioFocusRequest(it) }
                error("the microphone could not be opened")
            }
            record.startRecording()
        }

        override fun read(buffer: ShortArray): Int =
            if (lost || session?.lost == true) -1 else record.read(buffer, 0, buffer.size)

        override fun close() {
            runCatching { record.stop() }
            record.release()
            focus?.let { audio.abandonAudioFocusRequest(it) }
        }

        private companion object {
            const val RATE = 16_000
        }
    }
