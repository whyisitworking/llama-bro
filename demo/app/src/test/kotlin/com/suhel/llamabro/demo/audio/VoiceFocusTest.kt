package com.suhel.llamabro.demo.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.AudioDeviceInfoBuilder
import org.robolectric.shadows.ShadowAudioRecord
import org.robolectric.util.ReflectionHelpers

/** Voice mode's one focus for a session (D147), and the microphone and speaker under it, on Robolectric's audio. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
@SuppressLint("MissingPermission") // a host test records nothing; the shadow source stands for the microphone
class VoiceFocusTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val manager = context.getSystemService(AudioManager::class.java)
    private val audio = shadowOf(manager)
    private val earpiece = device(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE)
    private val speaker = device(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)
    private val headset = device(AudioDeviceInfo.TYPE_WIRED_HEADSET)

    private fun device(type: Int): AudioDeviceInfo = AudioDeviceInfoBuilder.newBuilder().setType(type).build()

    @After fun clearSource() = ShadowAudioRecord.clearSource()

    private fun loud() =
        ShadowAudioRecord.setSource(
            object : ShadowAudioRecord.AudioRecordSource {
                override fun readInShortArray(
                    audioData: ShortArray,
                    offsetInShorts: Int,
                    sizeInShorts: Int,
                    isBlocking: Boolean,
                ): Int {
                    audioData.fill(1_000, offsetInShorts, offsetInShorts + sizeInShorts)
                    return sizeInShorts
                }
            },
        )

    @Test fun itTakesTransientExclusiveFocusOnceAndGivesItBackOnce() {
        val focus = VoiceFocus(context)
        focus.take(communication = false)
        val asked = audio.lastAudioFocusRequest
        focus.take(communication = false)
        assertSame(asked, audio.lastAudioFocusRequest)
        assertEquals(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE, asked?.audioFocusRequest?.focusGain)
        focus.release()
        assertSame(asked?.audioFocusRequest, audio.lastAbandonedAudioFocusRequest)
        // another session's focus given back last: a second release of the first gives nothing back again
        val other = VoiceFocus(context).apply { take(communication = false) }
        other.release()
        val last = audio.lastAbandonedAudioFocusRequest
        focus.release()
        assertSame(last, audio.lastAbandonedAudioFocusRequest)
    }

    @Test fun aReplyPlayingUnderItTakesNoFocusFromTheMicrophone() {
        loud()
        val focus = VoiceFocus(context).apply { take(communication = false) }
        val asked = audio.lastAudioFocusRequest
        val mic = Microphone(context, MediaRecorder.AudioSource.VOICE_COMMUNICATION, focus)
        val speech = TrackSpeech(context, 24_000, focus)
        speech.write(ShortArray(2_400) { 1_000 })
        // neither asked for its own, so nothing told the session's focus it was lost
        assertSame(asked, audio.lastAudioFocusRequest)
        assertEquals(AudioTrack.PLAYSTATE_PLAYING, speech.track().playState)
        assertEquals(16, mic.read(ShortArray(16)))
        speech.stop()
        assertNull(audio.lastAbandonedAudioFocusRequest)
        assertEquals(16, mic.read(ShortArray(16)))
        mic.close()
        speech.close()
        assertNull(audio.lastAbandonedAudioFocusRequest)
        focus.release()
    }

    @Test fun aLossToSomethingElseEndsTheMicrophoneUnderIt() {
        loud()
        val focus = VoiceFocus(context).apply { take(communication = false) }
        val mic = Microphone(context, MediaRecorder.AudioSource.VOICE_COMMUNICATION, focus)
        audio.lastAudioFocusRequest!!.listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        assertEquals(-1, mic.read(ShortArray(16)))
        mic.close()
        // gained back, a new turn's microphone opens and reads
        audio.lastAudioFocusRequest!!.listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN)
        assertFalse(focus.lost)
        val again = Microphone(context, MediaRecorder.AudioSource.VOICE_COMMUNICATION, focus)
        assertEquals(16, again.read(ShortArray(16)))
        again.close()
        focus.release()
    }

    @Test(expected = IllegalStateException::class)
    fun withoutTheFocusTheMicrophoneUnderItDoesNotOpen() {
        audio.setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_FAILED)
        val focus = VoiceFocus(context).apply { take(communication = false) }
        assertTrue(focus.lost)
        Microphone(context, MediaRecorder.AudioSource.VOICE_COMMUNICATION, focus)
    }

    @Test fun afterAPermanentLossTheNextMicrophoneTakesTheFocusAgain() {
        loud()
        val focus = VoiceFocus(context).apply { take(communication = false) }
        val first = audio.lastAudioFocusRequest
        // another app took the audio for good: the old request is spent
        first!!.listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS)
        assertTrue(focus.lost)
        val mic = Microphone(context, MediaRecorder.AudioSource.VOICE_COMMUNICATION, focus)
        assertFalse(focus.lost)
        assertSame(first.audioFocusRequest, audio.lastAbandonedAudioFocusRequest)
        assertNotSame(first, audio.lastAudioFocusRequest)
        assertEquals(16, mic.read(ShortArray(16)))
        mic.close()
        focus.release()
    }

    @Test fun withInterruptByTalkingRepliesPlayAsACallsVoiceOnTheLoudspeakerInCommunicationMode() {
        audio.setAvailableCommunicationDevices(listOf(earpiece, speaker))
        val focus = VoiceFocus(context).apply { take(communication = true) }
        assertTrue(focus.communication)
        assertEquals(AudioManager.MODE_IN_COMMUNICATION, manager.mode)
        assertSame(speaker, manager.communicationDevice)
        val speech = TrackSpeech(context, 24_000, focus)
        assertEquals(AudioAttributes.USAGE_VOICE_COMMUNICATION, speech.track().audioAttributes.usage)
        speech.close()
        focus.release()
        assertFalse(focus.communication)
        assertEquals(AudioManager.MODE_NORMAL, manager.mode)
        assertNull(manager.communicationDevice)
    }

    @Test fun withoutAWayToTheLoudspeakerRepliesPlayAsTheAssistantsAndTheModeIsLeftAlone() {
        val focus = VoiceFocus(context).apply { take(communication = true) }
        assertFalse(focus.communication)
        assertEquals(AudioManager.MODE_NORMAL, manager.mode)
        val speech = TrackSpeech(context, 24_000, focus)
        assertEquals(AudioAttributes.USAGE_ASSISTANT, speech.track().audioAttributes.usage)
        speech.close()
        focus.release()
    }

    @Test fun withoutTheFocusNothingIsRoutedAndTheModeIsLeftAlone() {
        audio.setAvailableCommunicationDevices(listOf(earpiece, speaker))
        audio.setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_FAILED)
        val focus = VoiceFocus(context).apply { take(communication = true) }
        assertFalse(focus.communication)
        assertEquals(AudioManager.MODE_NORMAL, manager.mode)
        assertNull(manager.communicationDevice)
        focus.release()
    }

    @Test fun interruptOffNeverChangesTheMode() {
        audio.setAvailableCommunicationDevices(listOf(earpiece, speaker))
        val focus = VoiceFocus(context).apply { take(communication = false) }
        assertEquals(AudioManager.MODE_NORMAL, manager.mode)
        assertNull(manager.communicationDevice)
        val speech = TrackSpeech(context, 24_000, focus)
        assertEquals(AudioAttributes.USAGE_ASSISTANT, speech.track().audioAttributes.usage)
        speech.close()
        focus.release()
    }

    @Test fun aHeadsetThatComesInTakesTheCallsVoiceAndOneThatGoesGivesItBackToTheLoudspeaker() {
        audio.setAvailableCommunicationDevices(listOf(earpiece, speaker))
        val focus = VoiceFocus(context).apply { take(communication = true) }
        assertSame(speaker, manager.communicationDevice)
        audio.addAvailableCommunicationDevice(headset, false)
        audio.addOutputDevice(headset, true)
        shadowOf(Looper.getMainLooper()).idle()
        assertSame(headset, manager.communicationDevice)
        audio.removeAvailableCommunicationDevice(headset, false)
        audio.removeOutputDevice(headset, true)
        shadowOf(Looper.getMainLooper()).idle()
        assertSame(speaker, manager.communicationDevice)
        focus.release()
        assertEquals(AudioManager.MODE_NORMAL, manager.mode)
    }

    private fun TrackSpeech.track(): AudioTrack = ReflectionHelpers.getField(this, "track")
}
