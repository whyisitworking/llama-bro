package com.suhel.llamabro.demo.data

import com.suhel.llamabro.demo.chat.FakeChatDao
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Voice messages as the chat stores them (D76): the recording, its length and levels, and when the file goes. */
class VoiceMessagesTest {
    @get:Rule val folder = TemporaryFolder()

    private val audio by lazy { AudioStore(folder.newFolder("audio")) }
    private val chats by lazy {
        ChatRepository(FakeChatDao(), ImageStore(File(folder.root, "images")), { 1_000L }, audio)
    }

    private fun recording(): NewVoice {
        val name = audio.newName()
        audio.file(name).writeBytes(byteArrayOf(1))
        return NewVoice(name, 42_000, listOf(0f, 0.5f, 1f))
    }

    @Test fun aVoiceMessageKeepsItsRecordingLengthAndLevels() =
        runBlocking {
            val id = chats.createChat("org/model:Q4_K_M")
            val voice = recording()
            chats.append(id, NewMessage(MessageKind.User, "", voice = voice))
            val stored =
                chats
                    .messages(id)
                    .first()
                    .single()
                    .voice!!
            assertEquals(audio.file(voice.name), stored.file)
            assertEquals(42_000L, stored.millis)
            // kept in 26 steps, a letter each
            listOf(0f, 0.5f, 1f).zip(stored.levels).forEach { (a, b) -> assertEquals(a, b, 0.03f) }
        }

    @Test fun aChatThatStartsWithAVoiceMessageIsTitledSo() {
        assertEquals("Voice message", titleFrom("", hasImages = true, isVoice = true))
    }

    @Test fun deletingTheChatDeletesItsRecordings() =
        runBlocking {
            val id = chats.createChat("org/model:Q4_K_M")
            val voice = recording()
            chats.append(id, NewMessage(MessageKind.User, "", voice = voice))
            chats.delete(id)
            assertFalse(audio.file(voice.name).exists())
        }

    @Test fun cuttingTheChatDeletesOnlyTheRecordingsItRemoves() =
        runBlocking {
            val id = chats.createChat("org/model:Q4_K_M")
            val kept = recording()
            val cut = recording()
            chats.append(id, NewMessage(MessageKind.User, "", voice = kept))
            chats.append(id, NewMessage(MessageKind.Assistant, "heard you"))
            chats.append(id, NewMessage(MessageKind.User, "", voice = cut))
            chats.truncateFrom(id, 2)
            assertTrue(audio.file(kept.name).exists())
            assertFalse(audio.file(cut.name).exists())
        }

    @Test fun levelsSurviveTheirShortForm() {
        val levels = listOf(0f, 0.2f, 0.6f, 1f)
        val back = Levels.decode(Levels.encode(levels))
        levels.zip(back).forEach { (a, b) -> assertEquals(a, b, 0.03f) }
    }
}
