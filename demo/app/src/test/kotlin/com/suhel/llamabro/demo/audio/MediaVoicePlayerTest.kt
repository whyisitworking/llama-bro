package com.suhel.llamabro.demo.audio

import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowMediaPlayer
import org.robolectric.shadows.ShadowMediaPlayer.MediaInfo
import org.robolectric.shadows.util.DataSource
import java.io.File
import java.io.IOException
import java.time.Duration
import kotlin.coroutines.CoroutineContext

/**
 * [MediaVoicePlayer] on Robolectric's MediaPlayer: its main-thread work and its ticks run on the paused main looper,
 * whose clock is also the player's, so `idleFor` moves playback and progress together. The file is opened on [io], a
 * queue the test runs by hand on the same thread: no second thread, so nothing races.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class MediaVoicePlayerTest {
    private val scope = CoroutineScope(Job())
    private val looper get() = shadowOf(Looper.getMainLooper())
    private val players = mutableListOf<MediaPlayer>()
    private val io = ManualDispatcher()

    private val short = File("/voice/7.wav")
    private val long = File("/voice/8.wav")

    @Before fun media() {
        ShadowMediaPlayer.addMediaInfo(DataSource.toDataSource(short.path), MediaInfo(1_000, 0))
        ShadowMediaPlayer.addMediaInfo(DataSource.toDataSource(long.path), MediaInfo(2_000, 0))
        ShadowMediaPlayer.setCreateListener { player, _ -> players += player }
    }

    @After fun stop() = scope.cancel()

    private fun state(player: MediaPlayer) = shadowOf(player).state

    /** Runs the open up to its file read, the read, and what the read hands back to the main thread. */
    private fun awaitOpened() {
        looper.idle()
        io.runAll()
        looper.idle()
    }

    private fun idleFor(millis: Long) = looper.idleFor(Duration.ofMillis(millis))

    @Test fun toggleShowsTheClipPlayingAtOnceBeforeTheFileIsOpen() {
        val player = MediaVoicePlayer(scope, io = io)
        player.toggle(7, short)
        assertEquals(PlayingClip(7, playing = true, progress = 0f), player.clip.value)
        awaitOpened()
    }

    @Test fun aPlayingClipReportsHowFarItHasPlayed() {
        val player = MediaVoicePlayer(scope, io = io)
        player.toggle(7, short)
        awaitOpened()
        idleFor(500)
        val clip = player.clip.value!!
        assertEquals(7L, clip.messageId)
        assertTrue(clip.playing)
        assertEquals(0.5f, clip.progress, 0.06f)
    }

    @Test fun theFileIsPlayedAsSpeechMedia() {
        val player = MediaVoicePlayer(scope, io = io)
        player.toggle(7, short)
        awaitOpened()
        val attributes = shadowOf(players.single()).audioAttributes
        assertEquals(AudioAttributes.USAGE_MEDIA, attributes.usage)
        assertEquals(AudioAttributes.CONTENT_TYPE_SPEECH, attributes.contentType)
    }

    @Test fun toggleAgainPausesWhereItIsAndTheProgressHolds() {
        val player = MediaVoicePlayer(scope, io = io)
        player.toggle(7, short)
        awaitOpened()
        idleFor(300)
        player.toggle(7, short)
        val paused = player.clip.value!!
        assertEquals(false, paused.playing)
        assertEquals(0.3f, paused.progress, 0.06f)
        idleFor(2_000) // well past the clip's end: a paused clip neither moves nor completes
        assertEquals(paused, player.clip.value)
    }

    @Test fun togglingAPausedClipResumesFromWhereItPaused() {
        val player = MediaVoicePlayer(scope, io = io)
        player.toggle(7, short)
        awaitOpened()
        idleFor(300)
        player.toggle(7, short)
        idleFor(1_000)
        player.toggle(7, short)
        assertEquals(true, player.clip.value!!.playing)
        idleFor(200)
        assertEquals(0.5f, player.clip.value!!.progress, 0.06f)
    }

    @Test fun aClipThatPlaysToItsEndIsNotPlayingAndBackAtTheStart() {
        val player = MediaVoicePlayer(scope, io = io)
        player.toggle(7, short)
        awaitOpened()
        idleFor(1_100)
        assertEquals(PlayingClip(7, playing = false, progress = 0f), player.clip.value)
    }

    @Test fun aFinishedClipPlaysAgainFromTheStart() {
        val player = MediaVoicePlayer(scope, io = io)
        player.toggle(7, short)
        awaitOpened()
        idleFor(1_100)
        player.toggle(7, short)
        idleFor(200)
        val clip = player.clip.value!!
        assertTrue(clip.playing)
        assertEquals(0.2f, clip.progress, 0.06f)
    }

    @Test fun togglingAnotherMessageStopsTheFirstAndPlaysTheSecond() {
        val player = MediaVoicePlayer(scope, io = io)
        player.toggle(7, short)
        awaitOpened()
        idleFor(300)
        player.toggle(8, long)
        assertEquals(PlayingClip(8, playing = true, progress = 0f), player.clip.value)
        assertEquals(ShadowMediaPlayer.State.END, state(players[0]))
        awaitOpened()
        // past where the first clip would have ended: its completion must not claim the second
        idleFor(1_000)
        val clip = player.clip.value!!
        assertEquals(8L, clip.messageId)
        assertTrue(clip.playing)
        assertEquals(0.5f, clip.progress, 0.06f)
    }

    @Test fun aFileThatCannotBeOpenedClearsTheClip() {
        val broken = File("/voice/9.wav")
        ShadowMediaPlayer.addException(DataSource.toDataSource(broken.path), IOException("not audio"))
        val player = MediaVoicePlayer(scope, io = io)
        player.toggle(9, broken)
        assertEquals(PlayingClip(9, playing = true, progress = 0f), player.clip.value)
        awaitOpened()
        assertNull(player.clip.value)
    }

    @Test fun releaseStopsPlayingClearsTheClipAndLetsThePlayerGo() {
        val player = MediaVoicePlayer(scope, io = io)
        player.toggle(7, short)
        awaitOpened()
        idleFor(200)
        player.release()
        assertNull(player.clip.value)
        assertEquals(ShadowMediaPlayer.State.END, state(players.single()))
        idleFor(2_000) // no tick nor completion brings it back
        assertNull(player.clip.value)
    }

    @Test fun aPlayerOpenedAfterAReleaseIsLetGoAndNeverPlays() {
        val player = MediaVoicePlayer(scope, io = io)
        player.toggle(7, short)
        looper.idle() // the open is under way, waiting on io
        player.release()
        assertNull(player.clip.value)
        io.runAll() // the file opens after the release
        looper.idle()
        assertEquals(ShadowMediaPlayer.State.END, state(players.single()))
        idleFor(2_000)
        assertNull(player.clip.value)
    }

    @Test fun theNonePlayerHasNoClipAndIgnoresToggles() {
        VoicePlayer.None.toggle(7, short)
        assertNull(VoicePlayer.None.clip.value)
        VoicePlayer.None.release()
        assertNull(VoicePlayer.None.clip.value)
    }
}

/** Holds what is dispatched to it until [runAll] runs it, on the caller's thread. */
private class ManualDispatcher : CoroutineDispatcher() {
    private val queue = ArrayDeque<Runnable>()

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        queue += block
    }

    fun runAll() {
        while (queue.isNotEmpty()) queue.removeFirst().run()
    }
}
