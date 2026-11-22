package com.suhel.llamabro.demo.chat

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAudioRecord
import org.robolectric.util.ReflectionHelpers

/** [Microphone] on Robolectric's AudioRecord, fed by a source the test writes, and its audio focus. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
@SuppressLint("MissingPermission") // a host test records nothing; the shadow source stands for the microphone
class MicrophoneTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val audio = shadowOf(context.getSystemService(AudioManager::class.java))

    @After fun clearSource() = ShadowAudioRecord.clearSource()

    private fun Microphone.record(): AudioRecord = ReflectionHelpers.getField(this, "record")

    /** A source that hands out [samples] in order, then silence. */
    private fun feed(samples: ShortArray) {
        var next = 0
        ShadowAudioRecord.setSource(
            object : ShadowAudioRecord.AudioRecordSource {
                override fun readInShortArray(
                    audioData: ShortArray,
                    offsetInShorts: Int,
                    sizeInShorts: Int,
                    isBlocking: Boolean,
                ): Int {
                    val n = minOf(sizeInShorts, samples.size - next)
                    samples.copyInto(audioData, offsetInShorts, next, next + n)
                    next += n
                    return n
                }
            },
        )
    }

    @Test fun itOpensRecordingAtSixteenKilohertz() {
        val mic = Microphone(context)
        assertEquals(16_000, mic.rate)
        assertEquals(16_000, mic.record().sampleRate)
        assertEquals(1, mic.record().channelCount)
        assertEquals(AudioRecord.RECORDSTATE_RECORDING, mic.record().recordingState)
        mic.close()
    }

    @Test fun theSourceIsSpeechRecognitionUnlessAskedOtherwise() {
        val plain = Microphone(context)
        assertEquals(MediaRecorder.AudioSource.VOICE_RECOGNITION, plain.record().audioSource)
        plain.close()
        val call = Microphone(context, MediaRecorder.AudioSource.VOICE_COMMUNICATION)
        assertEquals(MediaRecorder.AudioSource.VOICE_COMMUNICATION, call.record().audioSource)
        call.close()
    }

    @Test fun itHoldsTransientExclusiveFocusWhileOpen() {
        val mic = Microphone(context)
        val asked = audio.lastAudioFocusRequest?.audioFocusRequest
        assertEquals(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE, asked?.focusGain)
        assertNull(audio.lastAbandonedAudioFocusRequest)
        mic.close()
        assertSame(asked, audio.lastAbandonedAudioFocusRequest)
    }

    @Test fun readHandsOverWhatTheMicrophoneHeard() {
        val heard = ShortArray(8) { (it * 1_000).toShort() }
        feed(heard)
        val mic = Microphone(context)
        val buffer = ShortArray(8)
        assertEquals(8, mic.read(buffer))
        assertArrayEquals(heard, buffer)
        mic.close()
    }

    @Test fun losingFocusEndsTheSource() {
        feed(ShortArray(64) { 1 })
        val mic = Microphone(context)
        val buffer = ShortArray(16)
        assertEquals(16, mic.read(buffer))
        audio.lastAudioFocusRequest!!.listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        assertEquals(-1, mic.read(buffer))
        mic.close()
    }

    @Test fun aFocusGainKeepsTheSourceGoing() {
        feed(ShortArray(64) { 1 })
        val mic = Microphone(context)
        audio.lastAudioFocusRequest!!.listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN)
        assertEquals(16, mic.read(ShortArray(16)))
        mic.close()
    }

    @Test(expected = IllegalStateException::class)
    fun withoutFocusItDoesNotOpen() {
        audio.setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_FAILED)
        Microphone(context)
    }

    @Test fun closeStopsAndReleasesTheRecorder() {
        val mic = Microphone(context)
        val record = mic.record()
        mic.close()
        assertEquals(AudioRecord.STATE_UNINITIALIZED, record.state)
    }
}
