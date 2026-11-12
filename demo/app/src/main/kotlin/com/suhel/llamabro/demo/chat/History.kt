package com.suhel.llamabro.demo.chat

import androidx.tracing.trace
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
     * some templates refuse two user turns in a row (F27). Stopped replies go with what they hold.
     */
    fun forModel(
        messages: List<StoredMessage>,
        upTo: Int = messages.size,
        readImage: (File) -> ByteArray = { it.readBytes() },
    ): List<Message> = trace("History.forModel") { build(messages, upTo, readImage) }

    private fun build(messages: List<StoredMessage>, upTo: Int, readImage: (File) -> ByteArray): List<Message> {
        val out = mutableListOf<Message>()
        var pending: PendingUser? = null

        fun flush() {
            pending?.let { out += it.toMessage(readImage) }
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
                    pending =
                        if (p == null) {
                            PendingUser(m.text, m.images)
                        } else {
                            PendingUser(
                                joinText(p.text, m.text),
                                p.images + m.images,
                            )
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

    private class PendingUser(val text: String, val images: List<File>) {
        fun toMessage(readImage: (File) -> ByteArray): Message {
            if (images.isEmpty()) return Message.user(text)
            val content =
                buildList<Content> {
                    images.forEach { add(Content.Image(readImage(it), "image/jpeg")) }
                    if (text.isNotEmpty()) add(Content.Text(text))
                }
            return Message.user(*content.toTypedArray())
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
