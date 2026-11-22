package com.suhel.llamabro.demo.chat

import android.content.Context
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.AudioTrack
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowAudioTrack
import org.robolectric.util.ReflectionHelpers

/**
 * [TrackSpeech] on Robolectric's AudioTrack. The shadow's play head is what it has been fed as bytes; 16-bit writes
 * never reach it, so the head stays where a test puts it and stands for how far the speaker has played.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
@OptIn(ExperimentalCoroutinesApi::class)
class TrackSpeechTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val audio = shadowOf(context.getSystemService(AudioManager::class.java))

    private val loud = ShortArray(1_600) { if (it % 2 == 0) 20_000 else -20_000 }
    private val quiet = ShortArray(1_600) { if (it % 2 == 0) 200 else -200 }

    // the track is the speech's own; a test reaches it to read its state and move its play head
    private fun TrackSpeech.track(): AudioTrack = ReflectionHelpers.getField(this, "track")

    private fun TrackSpeech.playedTo(frames: Int) {
        val shadow = Shadow.extract<ShadowAudioTrack>(track())
        ReflectionHelpers.setField(shadow, "numBytesReceived", frames * Short.SIZE_BYTES)
    }

    private fun focusAsked(): AudioFocusRequest? = audio.lastAudioFocusRequest?.audioFocusRequest

    @Test fun nothingWrittenIsSilentAndAsksNoFocus() {
        val speech = TrackSpeech(context, 16_000)
        assertEquals(0f, speech.level)
        assertNull(focusAsked())
        assertEquals(AudioTrack.PLAYSTATE_STOPPED, speech.track().playState)
    }

    @Test fun theTrackIsMonoSixteenBitAtTheGivenRate() {
        val speech = TrackSpeech(context, 24_000)
        val track = speech.track()
        assertEquals(24_000, track.sampleRate)
        assertEquals(1, track.channelCount)
        assertEquals(android.media.AudioFormat.ENCODING_PCM_16BIT, track.audioFormat)
    }

    @Test fun theFirstWriteAsksForTransientFocusAndStartsPlaying() {
        val speech = TrackSpeech(context, 16_000)
        speech.write(loud)
        assertEquals(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT, focusAsked()?.focusGain)
        assertEquals(AudioTrack.PLAYSTATE_PLAYING, speech.track().playState)
    }

    @Test fun focusIsAskedOnceWhileItPlays() {
        val speech = TrackSpeech(context, 16_000)
        speech.write(loud)
        val first = audio.lastAudioFocusRequest
        speech.write(quiet)
        assertSame(first, audio.lastAudioFocusRequest)
    }

    @Test fun theLevelIsTheLoudnessOfTheChunkAtThePlayHead() {
        val speech = TrackSpeech(context, 16_000)
        speech.write(loud)
        speech.write(quiet)
        assertEquals(VoiceRecorder.level(loud, loud.size), speech.level, 0f)
        speech.playedTo(loud.size)
        assertEquals(VoiceRecorder.level(quiet, quiet.size), speech.level, 0f)
        speech.playedTo(loud.size + quiet.size)
        assertEquals(0f, speech.level)
    }

    @Test fun aLoudChunkReadsLouderThanAQuietOne() {
        val speech = TrackSpeech(context, 16_000)
        speech.write(loud)
        val loudLevel = speech.level
        speech.playedTo(loud.size)
        speech.write(quiet)
        assertTrue(loudLevel > speech.level)
        assertTrue(speech.level > 0f)
    }

    @Test fun stopDropsWhatWasQueuedStopsTheTrackAndGivesUpFocus() {
        val speech = TrackSpeech(context, 16_000)
        speech.write(loud)
        speech.write(quiet)
        val asked = focusAsked()
        speech.stop()
        assertEquals(0f, speech.level)
        assertEquals(AudioTrack.PLAYSTATE_STOPPED, speech.track().playState)
        assertSame(asked, audio.lastAbandonedAudioFocusRequest)
    }

    @Test fun stopWhenNothingPlaysDoesNothing() {
        val speech = TrackSpeech(context, 16_000)
        speech.stop()
        assertNull(audio.lastAbandonedAudioFocusRequest)
    }

    @Test fun aWriteAfterAStopPlaysAgainFromAnEmptyQueue() {
        val speech = TrackSpeech(context, 16_000)
        speech.write(loud)
        speech.stop()
        speech.write(quiet)
        assertEquals(AudioTrack.PLAYSTATE_PLAYING, speech.track().playState)
        // the stop flushed the track, so its head is back at 0 and the queue holds only the new chunk
        assertEquals(VoiceRecorder.level(quiet, quiet.size), speech.level, 0f)
    }

    @Test fun drainWaitsUntilEverythingWrittenHasPlayedThenStops() =
        runTest {
            val speech = TrackSpeech(context, 16_000)
            speech.write(loud)
            speech.write(quiet)
            val draining = launch { speech.drain() }
            advanceTimeBy(1_000)
            runCurrent()
            assertFalse("nothing played yet", draining.isCompleted)
            speech.playedTo(loud.size)
            advanceTimeBy(1_000)
            runCurrent()
            assertFalse("half played", draining.isCompleted)
            speech.playedTo(loud.size + quiet.size)
            advanceTimeBy(100)
            runCurrent()
            assertTrue(draining.isCompleted)
            assertEquals(AudioTrack.PLAYSTATE_STOPPED, speech.track().playState)
            assertTrue(audio.lastAbandonedAudioFocusRequest != null)
        }

    @Test fun drainReturnsWhenAStopFromElsewhereDropsTheQueue() =
        runTest {
            val speech = TrackSpeech(context, 16_000)
            speech.write(loud)
            val draining = launch { speech.drain() }
            advanceTimeBy(500)
            runCurrent()
            assertFalse(draining.isCompleted)
            speech.stop()
            advanceTimeBy(100)
            runCurrent()
            assertTrue(draining.isCompleted)
        }

    @Test fun drainWithNothingWrittenReturnsAtOnce() =
        runTest {
            val speech = TrackSpeech(context, 16_000)
            val draining = launch { speech.drain() }
            runCurrent()
            assertTrue(draining.isCompleted)
            assertNull(audio.lastAbandonedAudioFocusRequest)
        }

    @Test fun closeStopsAndReleasesTheTrack() {
        val speech = TrackSpeech(context, 16_000)
        speech.write(loud)
        speech.close()
        assertEquals(AudioTrack.STATE_UNINITIALIZED, speech.track().state)
        assertTrue(audio.lastAbandonedAudioFocusRequest != null)
    }
}
