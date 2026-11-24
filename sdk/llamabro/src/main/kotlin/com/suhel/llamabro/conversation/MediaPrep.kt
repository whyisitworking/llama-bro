package com.suhel.llamabro.conversation

import com.suhel.llamabro.MediaSource
import com.suhel.llamabro.Model
import com.suhel.llamabro.chat.Content
import com.suhel.llamabro.chat.Message
import com.suhel.llamabro.internal.Media
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.util.IdentityHashMap

/**
 * Makes a conversation's media ready for the history (D84): checks a new message's pictures and clips, and reads each
 * descriptor once into bytes, so later turns and `toJson` never read it again. It exists apart from [Conversation]
 * because the reads are slow, run on the model's IO dispatcher, and must not hold the history while they run.
 */
internal object MediaPrep {
    /**
     * A new user message's [parts], checked against what the model reads, each file there and readable, and each
     * descriptor read into bytes, whose header is then checked too. Throws InvalidRequest when any check fails.
     */
    suspend fun forMessage(model: Model, parts: List<Content>): List<Content> {
        Media.check(model.info, parts)
        checkFiles(model, parts)
        val read = parts.withDescriptorsRead { model.read(it) }
        // again with a descriptor's bytes, whose header only now can be checked
        if (read != parts) Media.check(model.info, read)
        return read
    }

    /**
     * Descriptors in a `history` the conversation started from, read once: before its first request (on IO, through
     * `model.read`) or by `toJson` (on the caller's thread), whichever comes first. Returns the history it read from,
     * with them as bytes.
     *
     * The reads are slow and `toJson` runs beside a send, so they happen outside any write; then only those
     * descriptors are replaced, atomically, in whatever [history] is by then, and messages appended meanwhile stay.
     */
    inline fun readHistory(
        history: MutableStateFlow<List<Message>>,
        read: (MediaSource.Descriptor) -> ByteArray,
    ): List<Message> {
        val snapshot = history.value
        val unread = IdentityHashMap<MediaSource.Descriptor, ByteArray>()
        snapshot
            .flatMap { (it as? Message.User)?.content.orEmpty() }
            .mapNotNull(::descriptorOf)
            .forEach { d -> if (d !in unread) unread[d] = read(d) }
        if (unread.isEmpty()) return snapshot
        history.update { list -> list.withDescriptorsRead { unread[it] } }
        return snapshot.withDescriptorsRead { unread[it] }
    }

    /**
     * Each picture's or clip's file is there and readable, and a clip's starts as WAV, MP3 or FLAC, checked on the
     * model's IO dispatcher (D84).
     */
    private suspend fun checkFiles(model: Model, parts: List<Content>) {
        val files =
            parts.mapNotNull { c ->
                when (c) {
                    is Content.Image -> (c.source as? MediaSource.File)?.let { it to false }
                    is Content.Audio -> (c.source as? MediaSource.File)?.let { it to true }
                    is Content.Text -> null
                }
            }
        if (files.isNotEmpty()) {
            withContext(model.io) { files.forEach { (file, audio) -> Media.checkFile(file, audio) } }
        }
    }

    /**
     * [this] with each descriptor replaced by the bytes [read] gives, or kept when it gives null; the same list when
     * nothing is replaced.
     */
    inline fun List<Content>.withDescriptorsRead(read: (MediaSource.Descriptor) -> ByteArray?): List<Content> {
        var replaced = false
        val out =
            map { c ->
                val bytes = descriptorOf(c)?.let(read)
                when {
                    bytes == null -> {
                        c
                    }

                    c is Content.Image -> {
                        replaced = true
                        Content.Image(MediaSource.bytes(bytes), c.mimeType)
                    }

                    else -> {
                        replaced = true
                        Content.Audio(MediaSource.bytes(bytes), (c as Content.Audio).format)
                    }
                }
            }
        return if (replaced) out else this
    }

    /** The history with user messages' descriptors replaced as [withDescriptorsRead] does; the same list if none. */
    @JvmName("messagesWithDescriptorsRead")
    inline fun List<Message>.withDescriptorsRead(read: (MediaSource.Descriptor) -> ByteArray?): List<Message> {
        var replaced = false
        val out =
            map { m ->
                if (m !is Message.User) return@map m
                val content = m.content.withDescriptorsRead(read)
                if (content === m.content) {
                    m
                } else {
                    replaced = true
                    Message.User(content)
                }
            }
        return if (replaced) out else this
    }

    fun descriptorOf(c: Content): MediaSource.Descriptor? =
        when (c) {
            is Content.Image -> c.source as? MediaSource.Descriptor
            is Content.Audio -> c.source as? MediaSource.Descriptor
            is Content.Text -> null
        }
}
