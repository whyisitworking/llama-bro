package com.suhel.llamabro.voice

import com.suhel.llamabro.internal.RealtimeLink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A spoken conversation with a model that listens and talks (D95): upstream's `/v1/realtime` conversation, in memory.
 * Open it with `Model.voiceSession`. The app gives the user's audio with [append] and ends each turn with [commit];
 * the SDK has no turn detection of its own. The reply arrives on [events] as text ([VoiceEvent.ReplyText], the
 * captions) and speech ([VoiceEvent.ReplyAudio]), then [VoiceEvent.ReplyDone].
 *
 * The sending calls queue the event and return at once; they are safe from any thread and answer false once the
 * session has ended (closed, the model closed, or the server ended it). The server answers a client event it refuses
 * with [VoiceEvent.Error], and the session goes on.
 */
public class VoiceSession internal constructor(
    private val link: RealtimeLink,
    private val events0: ReceiveChannel<VoiceEvent>,
    /** upstream's session id */
    public val id: String,
    /** The rate [append] takes, from the session's config: the model's own unless set. */
    public val inputSampleRate: Int,
    /** The rate of [VoiceEvent.ReplyAudio]'s PCM (24 kHz for LFM3); null when the model doesn't speak. */
    public val outputSampleRate: Int?,
    /** The voice the server will speak with, null for the model's default. */
    public val voice: String?,
    /** The language the server will speak, null for the model's default. */
    public val language: String?,
    /** The session's coroutines: its events' collector; [close] cancels them. */
    private val scope: CoroutineScope,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)

    // whether audio went in since the last commit: a commit without it is refused, and a reply to it would answer
    // nothing
    private val hasAudio = AtomicBoolean(false)

    /**
     * The server's events, in order, from the moment the session opened. One collector; it completes when the session
     * ends, and fails with `LlamaBroException.ModelClosed` when the model is closed under it. Events not yet collected
     * are kept.
     */
    public val events: Flow<VoiceEvent> = events0.receiveAsFlow()

    /**
     * The user's audio: [length] samples of 16-bit mono PCM from [offset], at [inputSampleRate]
     * (`input_audio_buffer.append`), as `AudioRecord.read(short[])` gives it. The server encodes it as it arrives.
     */
    public fun append(pcm: ShortArray, offset: Int = 0, length: Int = pcm.size - offset): Boolean {
        require(offset >= 0 && length >= 0 && offset + length <= pcm.size) { "offset and length outside the array" }
        if (length == 0) return !closed.get()
        return send(RealtimeJson.append(pcm, offset, length)).also {
            if (it) hasAudio.set(true)
        }
    }

    /**
     * Ends the user's turn (`input_audio_buffer.commit`) and, with [respond], asks for the reply (`response.create`),
     * as push-to-talk does. With no audio since the last commit, the commit goes to the server, which refuses it
     * with [VoiceEvent.Error] (`input_audio_buffer_commit_empty`), and no reply is asked for. With `respond = false`
     * several turns can go in before one [respond].
     */
    public fun commit(respond: Boolean = true): Boolean {
        val audio = hasAudio.getAndSet(false)
        if (!send(RealtimeJson.commit)) return false
        return if (respond && audio) send(RealtimeJson.respond) else true
    }

    /** Asks for a reply to what went in so far (`response.create`). */
    public fun respond(): Boolean = send(RealtimeJson.respond)

    /** A typed user message (`conversation.item.create`, `input_text`) and, with [respond], the reply to it. */
    public fun sendText(text: String, respond: Boolean = true): Boolean {
        if (!send(RealtimeJson.userText(text))) return false
        return if (respond) send(RealtimeJson.respond) else true
    }

    /**
     * Stops the reply being made (`response.cancel`): its [VoiceEvent.ReplyDone] follows with `cancelled`. What it
     * said so far stays in the conversation, as upstream keeps it.
     */
    public fun cancelReply(): Boolean = send(RealtimeJson.cancel)

    /** Any client event, as upstream takes it (a JSON object with its `type`): the escape hatch, e.g. for tools. */
    public fun sendRaw(json: String): Boolean = send(json)

    /** Hangs up: the server ends the conversation, and [events] completes. Calling it again does nothing. */
    override fun close() {
        if (closed.compareAndSet(false, true)) scope.cancel()
    }

    private fun send(message: String): Boolean = !closed.get() && link.send(message)
}
