package com.suhel.llamabro

import android.os.ParcelFileDescriptor

/**
 * Where a picture's or a clip's bytes are (D84): a [File], an open [Descriptor] (a `content://` URI has no path) or
 * [Bytes] in memory. A file and a descriptor are read natively, so the Java heap never holds them; bytes cross into
 * native memory once.
 *
 * In a conversation, a descriptor is read once into bytes when its message is added, off the main thread: from then on
 * the chat keeps the bytes (later turns and `toJson` use them) and the app may close the descriptor. `Model.chat` and
 * `Model.transcribe` read it during the call.
 */
public sealed class MediaSource {
    /**
     * A file the native side opens and reads on each request that sends it; it must stay there until the reply ends. In
     * a chat, llama-server reads it while the request runs, through a link in the SDK's media folder rather than a
     * base64 copy (D84, F157: about 100 ms less per turn with three voice notes).
     */
    public data class File internal constructor(public val path: String) : MediaSource()

    /**
     * An open descriptor, read from offset 0 and left open: the app closes it. [fd] gives its number when it is read
     * (a seam, so JVM tests need no `ParcelFileDescriptor`).
     */
    public class Descriptor internal constructor(internal val fd: () -> Int) : MediaSource() {
        override fun toString(): String = "Descriptor"
    }

    /** Bytes in memory. The array is not copied: don't change it while a request may send it. */
    public class Bytes internal constructor(public val bytes: ByteArray) : MediaSource() {
        // not a data class: a data class compares arrays by reference, and two copies of a picture are the same picture
        override fun equals(other: Any?): Boolean = other is Bytes && other.bytes.contentEquals(bytes)

        override fun hashCode(): Int = bytes.contentHashCode()

        override fun toString(): String = "Bytes(${bytes.size} bytes)"
    }

    public companion object {
        /** A file on the phone, read natively each time a request sends it. */
        public fun file(path: String): MediaSource = File(path)

        /** An open descriptor, e.g. from `ContentResolver.openFileDescriptor(uri, "r")`; the app closes it. */
        public fun descriptor(pfd: ParcelFileDescriptor): MediaSource = Descriptor { pfd.fd }

        /** Bytes already in memory, e.g. a recording held by the app. */
        public fun bytes(bytes: ByteArray): MediaSource = Bytes(bytes)
    }
}
