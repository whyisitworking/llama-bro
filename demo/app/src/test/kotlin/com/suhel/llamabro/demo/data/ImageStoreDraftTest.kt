package com.suhel.llamabro.demo.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** A draft's pictures are staged in their own folder and move into the messages' folder when the message is sent. */
class ImageStoreDraftTest {
    @get:Rule val folder = TemporaryFolder()

    private val store by lazy { ImageStore(File(folder.root, "images")) }

    private fun staged(name: String): File =
        store
            .draftFile(name)
            .apply {
                parentFile!!.mkdirs()
            }.also { it.writeBytes(byteArrayOf(1)) }

    @Test fun aDraftsPictureLivesApartFromAMessagesPicture() {
        assertEquals(File(folder.root, "images/draft/a.jpg"), store.draftFile("a.jpg"))
        assertEquals(File(folder.root, "images/a.jpg"), store.file("a.jpg"))
    }

    @Test fun sendingMovesThePicturesIntoTheMessagesFolderStampedNow() =
        runBlocking {
            val draft = staged("a.jpg").also { it.setLastModified(1_000L) }
            store.commit(listOf("a.jpg", "never-staged.jpg"), now = 5_000_000L)
            assertFalse(draft.exists())
            assertTrue(store.file("a.jpg").exists())
            assertEquals(5_000_000L, store.file("a.jpg").lastModified())
            assertFalse("a name with no draft file is left as it is", store.file("never-staged.jpg").exists())
        }

    @Test fun aMessageThatWasNotStoredGivesItsPicturesBackToTheDraft() =
        runBlocking {
            staged("a.jpg")
            store.commit(listOf("a.jpg"), now = 1L)
            store.uncommit(listOf("a.jpg"))
            assertTrue(store.draftFile("a.jpg").exists())
            assertFalse(store.file("a.jpg").exists())
        }

    @Test fun removingADraftsPictureNeverTouchesASentOne() =
        runBlocking {
            staged("draft.jpg")
            store.file("sent.jpg").writeBytes(byteArrayOf(1))
            store.deleteDrafts(listOf("draft.jpg", "sent.jpg"))
            assertFalse(store.draftFile("draft.jpg").exists())
            assertTrue("a sent picture is not in the draft folder", store.file("sent.jpg").exists())
        }

    @Test fun theUnreferencedSweepLeavesTheDraftFolder() =
        runBlocking {
            val draft = staged("draft.jpg").also { it.setLastModified(1L) }
            val stray = store.file("stray.jpg").also { it.writeBytes(byteArrayOf(1)) }.also { it.setLastModified(1L) }
            store.deleteUnreferenced(emptySet(), cutoff = 10_000L)
            assertTrue(draft.exists())
            assertFalse(stray.exists())
        }
}
