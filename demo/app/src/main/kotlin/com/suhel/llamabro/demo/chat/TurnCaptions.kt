package com.suhel.llamabro.demo.chat

import com.suhel.llamabro.LlamaBroException
import com.suhel.llamabro.MediaSource
import com.suhel.llamabro.Model
import com.suhel.llamabro.Typed
import com.suhel.llamabro.transcribe
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.io.File

/**
 * Captions of the person's turns in voice mode (D109). Upstream's conversation sends no transcript of them (F169), so
 * each turn's saved recording goes to [model]'s `transcribe`, one turn at a time and in order, on the second slot while
 * the reply is made (F170); [onCaption] gets the turn's message and its words. A turn whose transcription fails stays a
 * voice message without words.
 */
class TurnCaptions(
    private val model: Model,
    scope: CoroutineScope,
    dispatcher: CoroutineDispatcher,
    private val onCaption: suspend (row: Long, text: String) -> Unit,
) {
    private val turns = Channel<Pair<Long, File>>(Channel.UNLIMITED)
    private val job =
        scope.launch(dispatcher) {
            for ((row, clip) in turns) caption(clip)?.let { onCaption(row, it) }
        }

    /** The turn saved as message [row], with its recording [clip]. */
    fun add(row: Long, clip: File) {
        turns.trySend(row to clip)
    }

    /** The session ended: a caption under way stops, and the rest are not made. */
    fun close() {
        turns.close()
        job.cancel()
    }

    private suspend fun caption(clip: File): String? =
        try {
            when (val heard = model.transcribe(MediaSource.file(clip.path))) {
                is Typed.Value -> heard.value
                is Typed.Incomplete -> heard.text
                is Typed.Invalid -> null
            }?.trim()
        } catch (expected: LlamaBroException) {
            null
        }
}
