package com.suhel.llamabro.demo.chat

import com.suhel.llamabro.chat.Content
import com.suhel.llamabro.chat.Message
import com.suhel.llamabro.demo.data.MessageKind
import com.suhel.llamabro.demo.data.MessageState
import com.suhel.llamabro.demo.data.StoredMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class HistoryTest {
    private fun row(
        position: Int,
        kind: MessageKind,
        text: String,
        state: MessageState = MessageState.Done,
        images: List<File> = emptyList(),
    ) = StoredMessage(position + 1L, 1, position, kind, text, null, null, images, null, 0, null, state, null)

    private fun texts(history: List<Message>) =
        history.map {
            when (it) {
                is Message.User -> "U:" + it.content.filterIsInstance<Content.Text>().joinToString("") { t -> t.text }
                is Message.Assistant -> "A:" + it.content
                else -> "?"
            }
        }

    @Test fun historyNeverHasTwoUserTurnsInARow() {
        val rows =
            listOf(
                row(0, MessageKind.User, "one"),
                row(1, MessageKind.Assistant, "", MessageState.Failed),
                row(2, MessageKind.User, "two"),
                row(3, MessageKind.Assistant, "reply"),
                row(4, MessageKind.User, "three"),
            )
        val history = History.forModel(rows)
        assertEquals(listOf("U:one\n\ntwo", "A:reply", "U:three"), texts(history))
        assertTrue(history.zipWithNext().none { (a, b) -> a is Message.User && b is Message.User })
    }

    @Test fun switchRowsAreNotSent() {
        val rows =
            listOf(
                row(0, MessageKind.User, "hi"),
                row(1, MessageKind.Assistant, "hello"),
                row(2, MessageKind.Switch, "Switched to Qwen3.5 4B"),
                row(3, MessageKind.User, "more"),
            )
        assertEquals(listOf("U:hi", "A:hello", "U:more"), texts(History.forModel(rows)))
    }

    @Test fun stoppedRepliesAreSentWithWhatTheyHold() {
        val rows =
            listOf(
                row(0, MessageKind.User, "hi"),
                row(1, MessageKind.Assistant, "half", MessageState.Stopped),
                row(2, MessageKind.User, "go on"),
            )
        assertEquals(listOf("U:hi", "A:half", "U:go on"), texts(History.forModel(rows)))
    }

    @Test fun imagesBecomeImageContent() {
        val file =
            File.createTempFile("img", ".jpg").apply {
                writeBytes(byteArrayOf(1, 2, 3))
                deleteOnExit()
            }
        val history = History.forModel(listOf(row(0, MessageKind.User, "what is this", images = listOf(file))))
        val user = history.single() as Message.User
        val image = user.content.filterIsInstance<Content.Image>().single()
        assertEquals("image/jpeg", image.mimeType)
        assertEquals(listOf<Byte>(1, 2, 3), image.bytes.toList())
        assertEquals(
            "what is this",
            user.content
                .filterIsInstance<Content.Text>()
                .single()
                .text,
        )
    }

    @Test fun upToStopsBeforeThatPosition() {
        val rows =
            listOf(
                row(0, MessageKind.User, "a"),
                row(1, MessageKind.Assistant, "b"),
                row(2, MessageKind.User, "c"),
                row(3, MessageKind.Assistant, "d"),
            )
        assertEquals(listOf("U:a", "A:b"), texts(History.forModel(rows, upTo = 2)))
    }

    @Test fun instructionsComposeTheSystemPrompt() {
        assertEquals(null, Instructions.system("", "  "))
        assertEquals("About me: a nurse", Instructions.system("a nurse", ""))
        assertEquals("How to answer: short", Instructions.system("", "short"))
        assertEquals("About me: a nurse\n\nHow to answer: short", Instructions.system("a nurse", "short"))
    }
}
