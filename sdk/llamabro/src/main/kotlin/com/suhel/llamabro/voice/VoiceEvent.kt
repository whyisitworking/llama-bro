package com.suhel.llamabro.voice

/**
 * What a [VoiceSession] hears from the server, in order: upstream's realtime events (`/v1/realtime`, D95), the ones a
 * voice app needs typed and every other one as [Other]. Upstream sends no transcript of the user's words in a
 * conversation (F169): an app that captions them transcribes its own recording of each turn (D109).
 */
public sealed interface VoiceEvent {
    /** The audio given since the last commit became the user's turn (`input_audio_buffer.committed`). */
    public data class TurnCommitted internal constructor(public val itemId: String) : VoiceEvent

    /** A reply began (`response.created`). */
    public data class ReplyStarted internal constructor(public val replyId: String) : VoiceEvent

    /**
     * The next piece of the reply's text (`response.output_text.delta`): the captions, when the reply is spoken
     * too. The model writes its text ahead of its speech (F167).
     */
    public data class ReplyText internal constructor(public val replyId: String, public val delta: String) : VoiceEvent

    /**
     * The next piece of the reply's speech (`response.output_audio.delta`): 16-bit mono PCM at
     * [VoiceSession.outputSampleRate], ready for `AudioTrack.write(short[])`.
     */
    public class ReplyAudio internal constructor(public val replyId: String, public val pcm: ShortArray) : VoiceEvent {
        // not a data class: a data class compares arrays by reference
        override fun equals(other: Any?): Boolean =
            other is ReplyAudio && other.replyId == replyId && other.pcm.contentEquals(pcm)

        override fun hashCode(): Int = replyId.hashCode() * 31 + pcm.contentHashCode()

        override fun toString(): String = "ReplyAudio(replyId=$replyId, samples=${pcm.size})"
    }

    /**
     * The reply ended (`response.done`): [text] is its whole text, [cancelled] whether `VoiceSession.cancelReply`
     * stopped it, [outputTokens] what it generated (null if the server didn't say).
     */
    public data class ReplyDone internal constructor(
        public val replyId: String,
        public val text: String,
        public val cancelled: Boolean,
        public val outputTokens: Int?,
    ) : VoiceEvent

    /**
     * The server refused a client event (`error`), e.g. a commit with no audio (`input_audio_buffer_commit_empty`), or
     * a turn's caption failed (`user_transcription_failed`, D99). The session goes on. [type] is upstream's error type,
     * or that one.
     */
    public data class Error internal constructor(public val type: String, public val message: String) : VoiceEvent

    /** Any other server event, as upstream sent it: [type] and the whole event as [json] (the escape hatch). */
    public data class Other internal constructor(public val type: String, public val json: String) : VoiceEvent
}
