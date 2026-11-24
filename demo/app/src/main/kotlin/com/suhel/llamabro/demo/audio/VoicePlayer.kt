package com.suhel.llamabro.demo.audio

import android.media.AudioAttributes
import android.media.MediaPlayer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** The voice message playing or paused (by message id), and how far, 0 to 1. */
data class PlayingClip(val messageId: Long, val playing: Boolean, val progress: Float)

/** Plays a chat's voice messages, one at a time (D76). */
interface VoicePlayer {
    val clip: StateFlow<PlayingClip?>

    /** Plays [file] for message [messageId] from where it was paused, or pauses it when it is playing. */
    fun toggle(messageId: Long, file: File)

    /** Stops playing and lets the player go. */
    fun release()

    /** Plays nothing: for screens and tests that have no voice messages. */
    object None : VoicePlayer {
        override val clip: StateFlow<PlayingClip?> = MutableStateFlow<PlayingClip?>(null).asStateFlow()

        override fun toggle(messageId: Long, file: File) = Unit

        override fun release() = Unit
    }
}

/**
 * [VoicePlayer] on Android's `MediaPlayer`, called on the main thread; the file is read off it, and the progress moves
 * every [tickMillis] while playing.
 */
class MediaVoicePlayer(
    private val scope: CoroutineScope,
    private val tickMillis: Long = 50,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : VoicePlayer {
    // external event: MediaPlayer's playback and its progress
    private val _clip = MutableStateFlow<PlayingClip?>(null)
    override val clip: StateFlow<PlayingClip?> = _clip.asStateFlow()

    private var player: MediaPlayer? = null
    private var ticker: Job? = null
    private var opening: Job? = null

    override fun toggle(messageId: Long, file: File) {
        val current = _clip.value
        val p = player
        if (current != null && current.messageId == messageId && p != null) {
            if (p.isPlaying) {
                p.pause()
                ticker?.cancel()
                _clip.value = current.copy(playing = false)
            } else {
                p.start()
                _clip.value = current.copy(playing = true)
                tick(p, messageId)
            }
            return
        }
        release()
        // shown at once; the file is opened and read off the main thread
        _clip.value = PlayingClip(messageId, playing = true, progress = 0f)
        opening =
            scope.launch(Dispatchers.Main) {
                var opened: MediaPlayer? = null
                try {
                    // not cancellable, so the file is opened to the end; but once cancelled, withContext throws on its
                    // way back instead of returning, so the player it opened is let go here
                    withContext(io + NonCancellable) { opened = prepared(file) }
                } catch (cancelled: CancellationException) {
                    opened?.release()
                    throw cancelled
                }
                val next = opened
                if (next == null) {
                    _clip.value = null
                    return@launch
                }
                next.setOnCompletionListener {
                    ticker?.cancel()
                    _clip.value = PlayingClip(messageId, playing = false, progress = 0f)
                    it.seekTo(0)
                }
                player = next
                next.start()
                tick(next, messageId)
            }
    }

    private fun prepared(file: File): MediaPlayer? =
        runCatching {
            MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes
                        .Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                setDataSource(file.path)
                prepare()
            }
        }.getOrNull()

    private fun tick(p: MediaPlayer, messageId: Long) {
        ticker?.cancel()
        ticker =
            scope.launch(Dispatchers.Main) {
                while (isActive && p.isPlaying) {
                    val total = p.duration.takeIf { it > 0 } ?: break
                    _clip.value = PlayingClip(messageId, playing = true, progress = p.currentPosition.toFloat() / total)
                    delay(tickMillis)
                }
            }
    }

    override fun release() {
        opening?.cancel()
        ticker?.cancel()
        player?.release()
        player = null
        _clip.value = null
    }
}
