package com.suhel.llamabro.chat

/** A part of a user message. */
public sealed interface Content {
    /** Text. */
    public data class Text(public val text: String) : Content

    /** Sent as a base64 `data:` URL in an `image_url` part, as llama-server takes images. */
    public class Image(public val bytes: ByteArray, public val mimeType: String = "image/jpeg") : Content {
        override fun equals(other: Any?): Boolean =
            other is Image && other.mimeType == mimeType && other.bytes.contentEquals(bytes)

        override fun hashCode(): Int = 31 * bytes.contentHashCode() + mimeType.hashCode()

        override fun toString(): String = "Image(${bytes.size} bytes, $mimeType)"
    }
}
