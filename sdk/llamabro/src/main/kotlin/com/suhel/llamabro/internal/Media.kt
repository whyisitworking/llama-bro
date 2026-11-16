package com.suhel.llamabro.internal

import com.suhel.llamabro.LlamaBroException
import com.suhel.llamabro.MediaSource
import com.suhel.llamabro.ModelInfo
import com.suhel.llamabro.chat.Content
import com.suhel.llamabro.chat.Message
import kotlinx.coroutines.CoroutineDispatcher

/**
 * Media before a request (D84): the checks that make a wrong picture or clip an [LlamaBroException.InvalidRequest]
 * naming what to do, and the [EngineAttachment] each source becomes.
 */
internal object Media {
    /** The prefix of a chat placeholder; the native side replaces `lb-attachment:<name>` with the file's data URL. */
    const val PLACEHOLDER = "lb-attachment:"

    private const val FORMATS = "WAV, MP3 or FLAC, the formats llama.cpp decodes; record WAV or convert it first"

    /** `input_audio.format` by MIME type; upstream ignores it ("it's redundant"), but OpenAI's shape has it. */
    private val audioFormats =
        mapOf(
            "audio/wav" to "wav",
            "audio/x-wav" to "wav",
            "audio/wave" to "wav",
            "audio/vnd.wave" to "wav",
            "audio/mpeg" to "mp3",
            "audio/mp3" to "mp3",
            "audio/flac" to "flac",
            "audio/x-flac" to "flac",
        )

    /** Every picture and clip in [messages] is one [info]'s model can read, in a format llama.cpp decodes. */
    fun checkMessages(info: ModelInfo, messages: List<Message>) {
        messages.forEach { if (it is Message.User) check(info, it.content) }
    }

    fun check(info: ModelInfo, content: List<Content>) {
        for (c in content) {
            when (c) {
                is Content.Text -> {
                    Unit
                }

                is Content.Image -> {
                    if (!info.supportsVision) {
                        throw LlamaBroException.InvalidRequest(
                            "a picture needs a model that sees, and this one doesn't (ModelInfo.supportsVision " +
                                "is false): load a vision model with its image encoder " +
                                "(ModelSource.file(path) { mmproj = … }), or leave the picture out",
                        )
                    }
                }

                is Content.Audio -> {
                    checkAudio(info, c.source, c.mimeType)
                }
            }
        }
    }

    /** A clip [info]'s model can hear: a WAV, MP3 or FLAC type, and for bytes, a WAV, MP3 or FLAC header. */
    fun checkAudio(info: ModelInfo, source: MediaSource, mimeType: String) {
        if (!info.supportsAudio) {
            throw LlamaBroException.InvalidRequest(
                "audio needs a model that hears, and this one doesn't (ModelInfo.supportsAudio is false): load an " +
                    "audio model with its audio encoder (ModelSource.file(path) { mmproj = … })",
            )
        }
        audioFormat(mimeType)
        // a descriptor is checked natively, where it is read (the same check, upstream's is_audio_file); so is a file,
        // and a conversation checks a file's first bytes before adding its message too (checkFile)
        if (source is MediaSource.Bytes && !isAudio(source.bytes)) {
            throw LlamaBroException.InvalidRequest("these bytes are not $FORMATS")
        }
    }

    /** `wav`, `mp3` or `flac` for [mimeType]. */
    fun audioFormat(mimeType: String): String =
        audioFormats[mimeType.substringBefore(';').trim().lowercase()]
            ?: throw LlamaBroException.InvalidRequest("audio is $mimeType; it must be $FORMATS")

    /**
     * llama.cpp's `is_audio_file` (mtmd-helper.cpp), but an MPEG sync word must also be MPEG audio layer I-III: an ADTS
     * AAC header has the sync word with layer bits 00, passes upstream's check, then fails to decode.
     */
    fun isAudio(b: ByteArray): Boolean {
        if (b.size < MIN_HEADER) return false

        fun at(i: Int, s: String) = s.indices.all { b[i + it] == s[it].code.toByte() }
        val first = b[0].toInt() and BYTE
        val second = b[1].toInt() and BYTE
        val mpeg = first == BYTE && (second and SYNC) == SYNC && (second and LAYER) != 0
        return (at(0, "RIFF") && at(WAVE_AT, "WAVE")) || at(0, "ID3") || mpeg || at(0, "fLaC")
    }

    /** The native side's format refusal (a path or descriptor clip), with what to do added. */
    fun advise(message: String): String =
        if (message.contains("is not WAV, MP3 or FLAC audio") && !message.contains("convert")) {
            "$message; record WAV or convert it first"
        } else {
            message
        }

    /**
     * [descriptor]'s number. A closed `ParcelFileDescriptor` throws `IllegalStateException` from `getFd()`: that is the
     * request's fault, so it becomes [LlamaBroException.InvalidRequest].
     */
    fun fdOf(descriptor: MediaSource.Descriptor): Int =
        try {
            descriptor.fd()
        } catch (e: IllegalStateException) {
            throw LlamaBroException.InvalidRequest(
                "the descriptor is closed: keep it open until its message is added to the conversation, or until " +
                    "chat, embed or transcribe returns (${e.message})",
                e,
            )
        }

    /**
     * A picture or clip [file] that isn't there or can't be read, or (for a clip, [audio]) whose first bytes aren't
     * WAV, MP3 or FLAC, checked before its message is added (on an IO thread): the native side would fail every later
     * request with it, so the message never enters the chat. Only a clip's first bytes are read into the heap.
     */
    fun checkFile(file: MediaSource.File, audio: Boolean) {
        val f = java.io.File(file.path)
        if (!f.isFile || !f.canRead()) {
            val why = if (!f.exists()) "there is no such file" else "it can't be read"
            throw LlamaBroException.InvalidRequest("cannot send ${file.path}: $why; nothing was added")
        }
        if (audio && !isAudio(head(f))) {
            throw LlamaBroException.InvalidRequest("cannot send ${file.path}: it is not $FORMATS; nothing was added")
        }
    }

    /** [f]'s first [MIN_HEADER] bytes, or fewer when it is shorter. */
    private fun head(f: java.io.File): ByteArray =
        try {
            f.inputStream().use { input ->
                val buf = ByteArray(MIN_HEADER)
                var n = 0
                while (n < MIN_HEADER) {
                    val r = input.read(buf, n, MIN_HEADER - n)
                    if (r < 0) break
                    n += r
                }
                buf.copyOf(n)
            }
        } catch (e: java.io.IOException) {
            throw LlamaBroException.InvalidRequest("cannot send ${f.path}: it can't be read; nothing was added", e)
        }

    /** [source] as an attachment named [name]: a path or a descriptor is read natively, bytes are copied once. */
    fun attachment(name: String, mime: String, source: MediaSource): EngineAttachment =
        when (source) {
            is MediaSource.File -> EngineAttachment.Path(name, mime, source.path)
            is MediaSource.Descriptor -> EngineAttachment.Fd(name, mime, fdOf(source))
            is MediaSource.Bytes -> EngineAttachment.Bytes(name, mime, source.bytes)
        }

    private const val MIN_HEADER = 12
    private const val WAVE_AT = 8
    private const val BYTE = 0xFF

    /** An MPEG frame header's second byte: the sync word's last three bits, and the layer (00 is ADTS AAC). */
    private const val SYNC = 0xE0
    private const val LAYER = 0x06
}

/**
 * How a model reads media (D84): [readFd] is `lb_read_fd`, and [dispatcher] runs whatever reads a file, which may block
 * (a remote `content://` descriptor), off the caller's thread.
 */
internal class MediaIo(val readFd: (Int) -> ByteArray, val dispatcher: CoroutineDispatcher)

/** How a picture's or a clip's place in a request body is written: a placeholder the native side fills (D84). */
internal fun interface MediaWriter {
    fun url(source: MediaSource, mime: String): String
}

/** The attachments of one chat request, named in order: `lb-attachment:0`, `lb-attachment:1`, … */
internal class Attachments : MediaWriter {
    private val list = mutableListOf<EngineAttachment>()

    val all: List<EngineAttachment> get() = list.toList()

    override fun url(source: MediaSource, mime: String): String {
        val name = list.size.toString()
        list += Media.attachment(name, mime, source)
        return Media.PLACEHOLDER + name
    }
}
