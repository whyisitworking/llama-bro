package com.suhel.llamabro.demo.data

import com.suhel.llamabro.demo.chat.FakeChatDao
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The pictures and recordings no row names: a cut chat takes its own with it, and a sweep finds the stray ones, never
 * a draft's pictures, which only the chat screen's saved state names (A12).
 */
class OrphanFilesTest {
    @get:Rule val folder = TemporaryFolder()

    private val now = 10 * DAY
    private val images by lazy { ImageStore(folder.newFolder("images")) }
    private val audio by lazy { AudioStore(folder.newFolder("audio")) }
    private val chats by lazy { ChatRepository(FakeChatDao(), images, { now }, audio) }

    private fun File.written(ageMillis: Long): File {
        writeBytes(byteArrayOf(1))
        setLastModified(now - ageMillis)
        return this
    }

    private fun picture(name: String, ageMillis: Long = 0) = images.file(name).written(ageMillis)

    @Test fun cuttingTheChatDeletesTheRemovedRowsPicturesButNotThoseKept() =
        runBlocking {
            val id = chats.createChat("org/model:Q4_K_M")
            val survivor = picture("survivor.jpg")
            val gone = picture("gone.jpg")
            val reused = picture("reused.jpg")
            chats.append(id, NewMessage(MessageKind.User, "first", listOf("survivor.jpg")))
            chats.append(id, NewMessage(MessageKind.Assistant, "reply"))
            chats.append(id, NewMessage(MessageKind.User, "second", listOf("gone.jpg", "reused.jpg")))
            chats.truncateFrom(id, 2, keepImages = setOf("reused.jpg"))
            assertTrue(survivor.exists())
            assertFalse(gone.exists())
            assertTrue(reused.exists())
        }

    @Test fun aSweepDeletesOldFilesNoRowNamesAndLeavesTheDraftsPictures() =
        runBlocking {
            val id = chats.createChat("org/model:Q4_K_M")
            val oldStray = picture("old.jpg", ageMillis = 5 * DAY)
            val freshStray = picture("fresh.jpg", ageMillis = DAY / 2)
            val named = picture("named.jpg", ageMillis = 5 * DAY)
            val draft = images.draftFile("draft.jpg").apply { parentFile!!.mkdirs() }.written(5 * DAY)
            chats.append(id, NewMessage(MessageKind.User, "look", listOf("named.jpg")))
            val freshRecording = audio.file("fresh.wav").written(DAY / 2)
            val recording = audio.file("kept.wav").written(5 * DAY)
            val strayRecording = audio.file("stray.wav").written(2 * DAY)
            chats.append(id, NewMessage(MessageKind.User, "", voice = NewVoice("kept.wav", 1_000, emptyList())))
            chats.sweepOrphans()
            assertFalse("an old picture no row names is swept", oldStray.exists())
            assertTrue("a recent one may be mid-send", freshStray.exists())
            assertTrue("a row's picture stays", named.exists())
            assertTrue("a draft's pictures live in saved state, so the draft folder is left", draft.exists())
            assertTrue(freshRecording.exists())
            assertTrue(recording.exists())
            assertFalse(strayRecording.exists())
        }

    private companion object {
        const val DAY = 24 * 60 * 60 * 1000L
    }
}
