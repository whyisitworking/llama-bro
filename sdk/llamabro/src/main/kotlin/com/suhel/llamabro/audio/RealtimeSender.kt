package com.suhel.llamabro.audio

import com.suhel.llamabro.internal.RealtimeLink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The client side a realtime session sends through (D97), shared by `VoiceSession` and `TranscriptionSession`: client
 * events go to [link] until [close], which cancels the session's [scope].
 */
internal class RealtimeSender(private val link: RealtimeLink, private val scope: CoroutineScope) {
    private val closed = AtomicBoolean(false)
    private val audio = AtomicBoolean(false)

    /** Whether audio went in since the last call, and none from now: a commit without it is refused by the server. */
    fun takeAudio(): Boolean = audio.getAndSet(false)

    /** [length] samples of 16-bit mono PCM from [offset] (`input_audio_buffer.append`). */
    fun append(pcm: ShortArray, offset: Int, length: Int): Boolean {
        require(offset >= 0 && length >= 0 && offset + length <= pcm.size) { "offset and length outside the array" }
        if (length == 0) return !closed.get()
        return send(RealtimeJson.append(pcm, offset, length)).also {
            if (it) audio.set(true)
        }
    }

    fun send(message: String): Boolean = !closed.get() && link.send(message)

    fun close() {
        if (closed.compareAndSet(false, true)) scope.cancel()
    }
}
