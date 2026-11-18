package com.suhel.llamabro.voice

/**
 * What a [VoiceSession] hears from the server, in order: upstream's realtime events (`/v1/realtime`, D95), the ones a
 * voice app needs typed and every other one as [Other]. Classes, not data classes, so they can gain fields (D26).
 * Upstream sends no transcript of the user's words in a conversation (F169); the SDK adds [UserTranscript] when asked.
 */
public sealed interface VoiceEvent {
    /** The audio given since the last commit became the user's turn (`input_audio_buffer.committed`). */
    public class TurnCommitted internal constructor(public val itemId: String) : VoiceEvent {
        override fun equals(other: Any?): Boolean = other is TurnCommitted && other.itemId == itemId

        override fun hashCode(): Int = itemId.hashCode()

        override fun toString(): String = "TurnCommitted(itemId=$itemId)"
    }

    /**
     * What the user said in a turn, as `Model.transcribe` heard it, when the session captions the user's words
     * (`VoiceConfig.Builder.transcribeUser`, D99). [itemId] is the turn's [TurnCommitted.itemId]; it follows that
     * event, usually while the reply is being made. Not upstream's: its conversation sends no such event (F169).
     */
    public class UserTranscript internal constructor(public val itemId: String, public val text: String) :
        VoiceEvent {
            override fun equals(other: Any?): Boolean =
                other is UserTranscript && other.itemId == itemId && other.text == text

            override fun hashCode(): Int = itemId.hashCode() * 31 + text.hashCode()

            override fun toString(): String = "UserTranscript(itemId=$itemId, text=$text)"
        }

    /** A reply began (`response.created`). */
    public class ReplyStarted internal constructor(public val replyId: String) : VoiceEvent {
        override fun equals(other: Any?): Boolean = other is ReplyStarted && other.replyId == replyId

        override fun hashCode(): Int = replyId.hashCode()

        override fun toString(): String = "ReplyStarted(replyId=$replyId)"
    }

    /**
     * The next piece of the reply's text (`response.output_text.delta`): the captions, when the reply is spoken
     * too. The model writes its text ahead of its speech (F167).
     */
    public class ReplyText internal constructor(public val replyId: String, public val delta: String) : VoiceEvent {
        override fun equals(other: Any?): Boolean =
            other is ReplyText && other.replyId == replyId && other.delta == delta

        override fun hashCode(): Int = replyId.hashCode() * 31 + delta.hashCode()

        override fun toString(): String = "ReplyText(replyId=$replyId, delta=$delta)"
    }

    /**
     * The next piece of the reply's speech (`response.output_audio.delta`): 16-bit mono PCM at
     * [VoiceSession.outputSampleRate], ready for `AudioTrack.write(short[])`.
     */
    public class ReplyAudio internal constructor(public val replyId: String, public val pcm: ShortArray) : VoiceEvent {
        override fun equals(other: Any?): Boolean =
            other is ReplyAudio && other.replyId == replyId && other.pcm.contentEquals(pcm)

        override fun hashCode(): Int = replyId.hashCode() * 31 + pcm.contentHashCode()

        override fun toString(): String = "ReplyAudio(replyId=$replyId, samples=${pcm.size})"
    }

    /**
     * The reply ended (`response.done`): [text] is its whole text, [cancelled] whether `VoiceSession.cancelReply`
     * stopped it, [outputTokens] what it generated (null if the server didn't say).
     */
    public class ReplyDone internal constructor(
        public val replyId: String,
        public val text: String,
        public val cancelled: Boolean,
        public val outputTokens: Int?,
    ) : VoiceEvent {
        override fun equals(other: Any?): Boolean =
            other is ReplyDone && other.replyId == replyId && other.text == text && other.cancelled == cancelled &&
                other.outputTokens == outputTokens

        override fun hashCode(): Int = listOf(replyId, text, cancelled, outputTokens).hashCode()

        override fun toString(): String =
            "ReplyDone(replyId=$replyId, text=$text, cancelled=$cancelled, outputTokens=$outputTokens)"
    }

    /**
     * The server refused a client event (`error`), e.g. a commit with no audio (`input_audio_buffer_commit_empty`), or
     * a turn's caption failed (`user_transcription_failed`, D99). The session goes on. [type] is upstream's error type,
     * or that one.
     */
    public class Error internal constructor(public val type: String, public val message: String) : VoiceEvent {
        override fun equals(other: Any?): Boolean = other is Error && other.type == type && other.message == message

        override fun hashCode(): Int = type.hashCode() * 31 + message.hashCode()

        override fun toString(): String = "Error(type=$type, message=$message)"
    }

    /** Any other server event, as upstream sent it: [type] and the whole event as [json] (the escape hatch). */
    public class Other internal constructor(public val type: String, public val json: String) : VoiceEvent {
        override fun equals(other: Any?): Boolean = other is Other && other.type == type && other.json == json

        override fun hashCode(): Int = type.hashCode() * 31 + json.hashCode()

        override fun toString(): String = "Other(type=$type, json=$json)"
    }
}
