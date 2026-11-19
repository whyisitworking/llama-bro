package com.suhel.llamabro.chat

import com.suhel.llamabro.MediaSource

/** A part of a user message. */
public sealed interface Content {
    /** Text. */
    public data class Text(public val text: String) : Content

    /**
     * A picture, for a model that sees ([com.suhel.llamabro.ModelInfo.supportsVision]). The SDK never reads a file's
     * or a descriptor's contents into the Java heap: the native side reads them and writes the base64 llama-server
     * takes (D84).
     */
    public data class Image(public val source: MediaSource, public val mimeType: String = "image/jpeg") : Content {
        /** A picture already in memory. */
        public constructor(bytes: ByteArray, mimeType: String = "image/jpeg") : this(MediaSource.bytes(bytes), mimeType)
    }

    /**
     * Speech or sound, for a model that hears ([com.suhel.llamabro.ModelInfo.supportsAudio]). WAV, MP3 or FLAC only,
     * the formats llama.cpp decodes; the SDK converts nothing, so record WAV or convert first. [mimeType] names the
     * format: `audio/wav`, `audio/mpeg` or `audio/flac`.
     */
    public data class Audio(public val source: MediaSource, public val mimeType: String = "audio/wav") : Content
}
