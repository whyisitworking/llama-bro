package com.suhel.llamabro.demo.chat

import androidx.tracing.trace
import com.suhel.llamabro.MediaSource
import com.suhel.llamabro.chat.Content
import com.suhel.llamabro.chat.Message
import com.suhel.llamabro.demo.data.MessageKind
import com.suhel.llamabro.demo.data.MessageState
import com.suhel.llamabro.demo.data.StoredMessage
import java.io.File

/** The chat history as the model sees it, rebuilt from stored messages (Review focus 1). */
object History {
    /**
     * The messages before position [upTo], for a model. Divider rows and failed replies aren't sent. A user message
     * with
     * no reply after it (its reply failed or was held) is joined to the next user message with a blank line, because
     * some templates refuse two user turns in a row (F27). Stopped replies go with what they hold. Pictures and voice
     * messages go as their files, which the SDK reads natively (D84); for a model that can't [hear], each voice message
     * is its caption when voice mode captioned it (D100), else a short note saying one was there (D76). A voice
     * message's text is its caption, never sent beside its audio.
     */
    fun forModel(messages: List<StoredMessage>, upTo: Int = messages.size, hears: Boolean = true): List<Message> =
        trace("History.forModel") { build(messages, upTo, hears) }

    /**
     * What stands for a voice message the model can't hear (D76): the conversation stays readable, and the model knows
     * something was said.
     */
    const val VOICE_PLACEHOLDER = "(A voice message was sent here; this model can't hear it.)"

    /** [user]'s message for the model: its pictures and voice message as files, then its text (D76, D84). */
    fun userMessage(user: StoredMessage, hears: Boolean): Message = PendingUser.of(user).toMessage(hears)

    private fun build(messages: List<StoredMessage>, upTo: Int, hears: Boolean): List<Message> {
        val out = mutableListOf<Message>()
        var pending: PendingUser? = null

        fun flush() {
            pending?.let { out += it.toMessage(hears) }
            pending = null
        }
        for (m in messages) {
            if (m.position >= upTo) break
            when {
                m.kind == MessageKind.Switch -> {
                    Unit
                }

                m.kind == MessageKind.User -> {
                    val p = pending
                    val next = PendingUser.of(m)
                    pending =
                        if (p == null) {
                            next
                        } else {
                            PendingUser(joinText(p.text, next.text), p.images + next.images, p.voices + next.voices)
                        }
                }

                m.kind == MessageKind.Assistant && (m.state == MessageState.Failed || m.state == MessageState.Held) -> {
                    Unit
                }

                m.kind == MessageKind.Assistant -> {
                    flush()
                    out += Message.Assistant(content = m.text, reasoning = null, toolCalls = emptyList())
                }
            }
        }
        flush()
        return out
    }

    private fun joinText(a: String, b: String) =
        if (a.isEmpty()) {
            b
        } else if (b.isEmpty()) {
            a
        } else {
            "$a\n\n$b"
        }

    /** A recording and its caption, empty when it has none. */
    private class Spoken(val file: File, val caption: String)

    private class PendingUser(val text: String, val images: List<File>, val voices: List<Spoken>) {
        fun toMessage(hears: Boolean): Message {
            val words =
                if (hears || voices.isEmpty()) {
                    text
                } else {
                    joinText(voices.joinToString("\n\n") { it.caption.ifEmpty { VOICE_PLACEHOLDER } }, text)
                }
            if (images.isEmpty() && (voices.isEmpty() || !hears)) return Message.user(words)
            val content =
                buildList<Content> {
                    images.forEach { add(Content.Image(MediaSource.file(it.path), "image/jpeg")) }
                    if (hears) voices.forEach { add(Content.Audio(MediaSource.file(it.file.path), "audio/wav")) }
                    if (words.isNotEmpty()) add(Content.Text(words))
                }
            return Message.user(*content.toTypedArray())
        }

        companion object {
            /** [m]'s parts: a voice message's text is its caption (D100), kept with its recording. */
            fun of(m: StoredMessage): PendingUser {
                val voice = m.voice ?: return PendingUser(m.text, m.images, emptyList())
                return PendingUser("", m.images, listOf(Spoken(voice.file, m.text)))
            }
        }
    }
}

/** The system prompt from Settings > Instructions (demo design §4.5). */
object Instructions {
    fun system(about: String, style: String): String? {
        val parts =
            buildList {
                if (about.isNotBlank()) add("About me: ${about.trim()}")
                if (style.isNotBlank()) add("How to answer: ${style.trim()}")
            }
        return parts.joinToString("\n\n").ifEmpty { null }
    }
}
