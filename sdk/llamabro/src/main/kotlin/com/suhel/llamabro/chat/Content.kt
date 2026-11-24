package com.suhel.llamabro.chat

import com.suhel.llamabro.MediaSource

/** A part of a user message. */
public sealed interface Content {
    /** Text. */
    public data class Text(public val text: String) : Content

    /**
     * A picture, for a model that sees ([com.suhel.llamabro.ModelInfo.supportsVision]). The SDK never reads a file's
     * or a descriptor's contents into the Java heap: the native side reads them and writes the base64 llama-server
     * takes ([D84](https://github.com/whyisitworking/llama-bro/blob/main/docs/decisions.md#d84)).
     */
    public data class Image(public val source: MediaSource, public val mimeType: String = "image/jpeg") : Content {
        /** A picture already in memory. */
        public constructor(bytes: ByteArray, mimeType: String = "image/jpeg") : this(MediaSource.bytes(bytes), mimeType)
    }

    /**
     * Speech or sound, for a model that hears ([com.suhel.llamabro.ModelInfo.supportsAudio]), in one of the formats
     * llama.cpp decodes ([AudioFormat]); the SDK converts nothing, so record WAV or convert first.
     */
    public data class Audio(public val source: MediaSource, public val format: AudioFormat = AudioFormat.Wav) :
        Content {
        /** A clip already in memory. */
        public constructor(bytes: ByteArray, format: AudioFormat = AudioFormat.Wav) :
            this(MediaSource.bytes(bytes), format)
    }
}

/** The audio formats llama.cpp decodes, and so the only ones a [Content.Audio] can be. */
public enum class AudioFormat(
    /** The MIME type the request names it by. */
    public val mimeType: String,
) {
    Wav("audio/wav"),
    Mp3("audio/mpeg"),
    Flac("audio/flac"),
}
