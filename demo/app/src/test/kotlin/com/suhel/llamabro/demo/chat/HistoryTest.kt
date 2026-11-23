package com.suhel.llamabro.demo.chat

import com.suhel.llamabro.MediaSource
import com.suhel.llamabro.chat.AudioFormat
import com.suhel.llamabro.chat.Content
import com.suhel.llamabro.chat.Message
import com.suhel.llamabro.demo.data.MessageKind
import com.suhel.llamabro.demo.data.MessageState
import com.suhel.llamabro.demo.data.StoredMessage
import com.suhel.llamabro.demo.data.StoredVoice
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

    @Test fun imagesGoAsTheirFiles() {
        // the SDK reads the file natively on each request; the app never reads it into memory (D76, D84)
        val file = File("/data/images/a.jpg")
        val history = History.forModel(listOf(row(0, MessageKind.User, "what is this", images = listOf(file))))
        val user = history.single() as Message.User
        val image = user.content.filterIsInstance<Content.Image>().single()
        assertEquals("image/jpeg", image.mimeType)
        assertEquals(MediaSource.file(file.path), image.source)
        assertEquals(
            "what is this",
            user.content
                .filterIsInstance<Content.Text>()
                .single()
                .text,
        )
    }

    private fun voiceRow(position: Int, name: String) =
        row(position, MessageKind.User, "").copy(voice = StoredVoice(File("/data/audio/$name"), 4_000, emptyList()))

    @Test fun aVoiceMessageGoesAsItsFileToAModelThatHears() {
        val history = History.forModel(listOf(voiceRow(0, "a.wav")), hears = true)
        val user = history.single() as Message.User
        val audio = user.content.filterIsInstance<Content.Audio>().single()
        assertEquals(MediaSource.file("/data/audio/a.wav"), audio.source)
        assertEquals(AudioFormat.Wav, audio.format)
        // asked to reply to it, not to describe the recording
        assertEquals(listOf(Content.Text(History.VOICE_REPLY)), user.content.filterIsInstance<Content.Text>())
    }

    @Test fun aModelThatCantHearGetsANoteForEachVoiceMessage() {
        val rows =
            listOf(
                voiceRow(0, "a.wav"),
                row(1, MessageKind.Assistant, "", MessageState.Failed),
                voiceRow(2, "b.wav"),
                row(3, MessageKind.Assistant, "ok"),
                row(4, MessageKind.User, "and now?"),
            )
        val history = History.forModel(rows, hears = false)
        val note = History.VOICE_PLACEHOLDER
        assertEquals(listOf("U:$note\n\n$note", "A:ok", "U:and now?"), texts(history))
        assertTrue(history.filterIsInstance<Message.User>().all { u -> u.content.none { it is Content.Audio } })
    }

    @Test fun aCaptionedVoiceTurnGoesAsAudioToAModelThatHearsAndAsItsCaptionToOneThatCant() {
        // voice mode keeps a turn's caption as the voice message's text (D100): never sent beside its audio
        val captioned = voiceRow(0, "a.wav").copy(text = "Tell me a story.")
        val heard = History.forModel(listOf(captioned), hears = true).single() as Message.User
        assertEquals(1, heard.content.filterIsInstance<Content.Audio>().size)
        assertEquals(listOf(Content.Text(History.VOICE_REPLY)), heard.content.filterIsInstance<Content.Text>())
        val rows = listOf(captioned, row(1, MessageKind.Assistant, "Once upon a time."), voiceRow(2, "b.wav"))
        assertEquals(
            listOf("U:Tell me a story.", "A:Once upon a time.", "U:${History.VOICE_PLACEHOLDER}"),
            texts(History.forModel(rows, hears = false)),
        )
    }

    @Test fun theNewMessageIsBuiltTheSameWay() {
        val heard = History.userMessage(voiceRow(0, "a.wav"), hears = true) as Message.User
        assertEquals(1, heard.content.filterIsInstance<Content.Audio>().size)
        val unheard = History.userMessage(voiceRow(0, "a.wav"), hears = false) as Message.User
        assertEquals(listOf(Content.Text(History.VOICE_PLACEHOLDER)), unheard.content)
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
