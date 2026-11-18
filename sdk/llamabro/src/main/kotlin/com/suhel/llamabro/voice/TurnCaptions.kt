package com.suhel.llamabro.voice

import com.suhel.llamabro.LlamaBroException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.launch

/**
 * Captions of the user's turns (D99): keeps the audio of the open turn, and at each commit transcribes it while the
 * reply is made, then sends [VoiceEvent.UserTranscript] after the turn's [VoiceEvent.TurnCommitted]. Turns are
 * committed in order, so the n-th commit with audio is the n-th `TurnCommitted`. Turns are transcribed one at a time,
 * in order: the spare slot would take them one at a time anyway, and the captions then come in the turns' order.
 */
internal class TurnCaptions(
    private val scope: CoroutineScope,
    private val events: SendChannel<VoiceEvent>,
    private val sampleRate: Int,
    /** The text of a WAV clip: `Model.transcribe` with the session's [UserTranscription]. */
    private val transcribe: suspend (ByteArray) -> String,
) {
    private val lock = Any()
    private var turn = ShortArray(INITIAL_SAMPLES)
    private var size = 0
    private val itemIds = ArrayDeque<CompletableDeferred<String>>()
    private val turns = Channel<Pair<CompletableDeferred<String>, ByteArray>>(Channel.UNLIMITED)

    init {
        scope.launch {
            for ((itemId, wav) in turns) {
                val event =
                    try {
                        VoiceEvent.UserTranscript(itemId.await(), transcribe(wav))
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: LlamaBroException) {
                        VoiceEvent.Error(FAILED, e.message ?: "the user's turn could not be transcribed")
                    }
                events.trySend(event) // closed when the session has ended: the caption is no longer wanted
            }
        }
    }

    fun append(pcm: ShortArray, offset: Int, length: Int) {
        synchronized(lock) {
            if (size + length > turn.size) turn = turn.copyOf(maxOf(turn.size * 2, size + length))
            pcm.copyInto(turn, size, offset, offset + length)
            size += length
        }
    }

    /** The open turn was committed with audio: its transcription starts now. */
    fun commit() {
        val itemId = CompletableDeferred<String>()
        val wav =
            synchronized(lock) {
                itemIds.addLast(itemId)
                wav(turn, size, sampleRate).also { size = 0 }
            }
        turns.trySend(itemId to wav)
    }

    /** The server's `TurnCommitted`, after it went to the app. */
    fun committed(itemId: String) {
        synchronized(lock) { itemIds.removeFirstOrNull() }?.complete(itemId)
    }

    companion object {
        /** [VoiceEvent.Error.type] of a turn whose transcription failed. */
        const val FAILED = "user_transcription_failed"

        /** Ten seconds at 16 kHz; a longer turn grows it. */
        private const val INITIAL_SAMPLES = 160_000

        /** A 16-bit mono WAV of the first [n] samples: the formats `transcribe` takes (D84). */
        @Suppress("MagicNumber") // the canonical 44-byte header's fixed offsets and values
        fun wav(pcm: ShortArray, n: Int, rate: Int): ByteArray {
            val data = n * 2
            val out = ByteArray(HEADER + data)

            fun int(at: Int, v: Int) {
                for (i in 0 until 4) out[at + i] = (v shr (8 * i)).toByte()
            }

            fun short(at: Int, v: Int) {
                out[at] = v.toByte()
                out[at + 1] = (v shr 8).toByte()
            }
            "RIFF".encodeToByteArray().copyInto(out, 0)
            int(4, HEADER - 8 + data)
            "WAVEfmt ".encodeToByteArray().copyInto(out, 8)
            int(16, 16) // fmt chunk size
            short(20, 1) // PCM
            short(22, 1) // mono
            int(24, rate)
            int(28, rate * 2) // bytes per second
            short(32, 2) // bytes per frame
            short(34, 16) // bits per sample
            "data".encodeToByteArray().copyInto(out, 36)
            int(40, data)
            for (i in 0 until n) short(HEADER + 2 * i, pcm[i].toInt())
            return out
        }

        private const val HEADER = 44
    }
}
