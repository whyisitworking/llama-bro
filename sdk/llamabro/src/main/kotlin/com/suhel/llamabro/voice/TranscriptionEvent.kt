package com.suhel.llamabro.voice

/**
 * What a [TranscriptionSession] hears from the server, in order: upstream's realtime transcription events
 * (`/v1/realtime`, `intent=transcription`), the ones a dictating app needs typed and every other one as [Other].
 * Each committed piece of audio is an item: its [Text] deltas, then its [Done].
 */
public sealed interface TranscriptionEvent {
    /** The audio since the last commit became item [itemId] (`input_audio_buffer.committed`). */
    public data class Committed internal constructor(public val itemId: String) : TranscriptionEvent

    /**
     * The next piece of item [itemId]'s text (`conversation.item.input_audio_transcription.delta`). In a live session
     * it comes while the audio is still arriving; otherwise once the item is committed.
     */
    public data class Text internal constructor(public val itemId: String, public val delta: String) :
        TranscriptionEvent

    /** Item [itemId] is transcribed (`conversation.item.input_audio_transcription.completed`), [transcript] whole. */
    public data class Done internal constructor(public val itemId: String, public val transcript: String) :
        TranscriptionEvent

    /** The server refused a client event (`error`), e.g. a commit with no audio. The session goes on. */
    public data class Error internal constructor(public val type: String, public val message: String) :
        TranscriptionEvent

    /** Any other server event, as upstream sent it: [type] and the whole event as [json] (the escape hatch). */
    public data class Other internal constructor(public val type: String, public val json: String) : TranscriptionEvent
}
