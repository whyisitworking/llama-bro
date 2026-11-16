package com.suhel.llamabro.demo.chat

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
 * transient exclusive audio focus while open; losing it (a call, another app recording) ends the source, so the
 * recording stops and is kept.
 */
class Microphone
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    constructor(context: Context) :
    SampleSource {
        override val rate: Int = RATE

        private val audio = context.getSystemService(AudioManager::class.java)

        @Volatile private var lost = false

        private val focus =
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

        private val record: AudioRecord

        init {
            check(audio.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) { "no audio focus" }
            val min =
                AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            record =
                AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    maxOf(min, RATE / 2),
                )
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                record.release()
                audio.abandonAudioFocusRequest(focus)
                error("the microphone could not be opened")
            }
            record.startRecording()
        }

        override fun read(buffer: ShortArray): Int = if (lost) -1 else record.read(buffer, 0, buffer.size)

        override fun close() {
            runCatching { record.stop() }
            record.release()
            audio.abandonAudioFocusRequest(focus)
        }

        private companion object {
            const val RATE = 16_000
        }
    }
