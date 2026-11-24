package com.suhel.llamabro.voice

import com.suhel.llamabro.internal.RealtimeLink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * Speech to text as it is spoken: upstream's realtime transcription (`/v1/realtime` with `intent=transcription`), in
 * memory. Open it with `Model.transcriptionSession`. The app gives audio with [append]; each [commit] makes the audio
 * since the last one an item, whose text arrives on [events]. [finish] ends the input.
 *
 * Upstream holds two kinds (`server-context.cpp` ws_realtime), and [isLive] says which this is:
 * - **live** (a streaming audio encoder and a transcription prompt in the template, such as LFM3 or Voxtral
 *   Realtime): text streams while the audio arrives; a commit only marks where an item ends.
 * - **per commit** (a model that holds realtime conversations without that prompt): nothing is transcribed until a
 *   commit, then that piece alone. An app commits at the speaker's pauses to keep the text close behind them; the SDK
 *   has no pause detection of its own, as upstream's server has none (`turn_detection` is always null).
 *
 * The sending calls queue the event and return at once; they are safe from any thread and answer false once the
 * session has ended.
 */
public class TranscriptionSession internal constructor(
    link: RealtimeLink,
    private val events0: ReceiveChannel<TranscriptionEvent>,
    /** upstream's session id */
    public val id: String,
    /** Whether text streams while the audio arrives (upstream's streaming ASR), rather than once per commit. */
    public val isLive: Boolean,
    /** The rate [append] takes, from the session's config: the model's own unless set. */
    public val inputSampleRate: Int,
    /** The session's coroutines: its events' collector; [close] cancels them. */
    scope: CoroutineScope,
) : AutoCloseable {
    private val sender = RealtimeSender(link, scope)

    /**
     * The server's events, in order, from the moment the session opened. One collector; it completes when the session
     * ends, and fails with `LlamaBroException.ModelClosed` when the model is closed under it, or with
     * `LlamaBroException.ServerError` on an event the SDK can't read.
     */
    public val events: Flow<TranscriptionEvent> = events0.receiveAsFlow()

    /**
     * Audio: [length] samples of 16-bit mono PCM from [offset], at [inputSampleRate] (`input_audio_buffer.append`), as
     * `AudioRecord.read(short[])` gives it.
     */
    public fun append(pcm: ShortArray, offset: Int = 0, length: Int = pcm.size - offset): Boolean =
        sender.append(pcm, offset, length)

    /**
     * Makes the audio since the last commit an item (`input_audio_buffer.commit`): [TranscriptionEvent.Committed],
     * then its text. A per-commit session refuses a commit with no audio since with [TranscriptionEvent.Error].
     */
    public fun commit(): Boolean = sender.send(RealtimeJson.commit)

    /**
     * Ends the input (`input_audio_buffer.commit` with `final: true`): the audio since the last commit becomes the last
     * item, whose [TranscriptionEvent.Done] follows (a live session sends no [TranscriptionEvent.Committed] for it).
     * Close the session after it.
     */
    public fun finish(): Boolean = sender.send(RealtimeJson.commitFinal)

    /** Any client event, as upstream takes it (a JSON object with its `type`): the escape hatch. */
    public fun sendRaw(json: String): Boolean = sender.send(json)

    /** Ends the session: [events] completes. Calling it again does nothing. */
    override fun close(): Unit = sender.close()
}
