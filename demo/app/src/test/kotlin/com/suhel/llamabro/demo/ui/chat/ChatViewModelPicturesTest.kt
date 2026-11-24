package com.suhel.llamabro.demo.ui.chat

import androidx.lifecycle.SavedStateHandle
import com.suhel.llamabro.demo.data.MessageKind
import com.suhel.llamabro.demo.data.MessageState
import com.suhel.llamabro.demo.eventually
import com.suhel.llamabro.demo.models.Catalog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.util.Collections

/** A draft's pictures: attached, sent, restored and deleted when nothing holds them any more. */
class ChatViewModelPicturesTest : ChatViewModelFixture() {
    /** A picture picked into the draft folder, as the photo picker's save leaves it. */
    private fun staged(name: String): String {
        images.draftFile(name).apply { parentFile!!.mkdirs() }.writeBytes(byteArrayOf(1))
        return name
    }

    /**
     * A view model on a model that sees, with [picture] in the bar and a send of it under way, held where the new chat
     * is created (before its pictures are moved) until [release]; and the send's stored row once released.
     */
    private inner class HeldSend(val vm: ChatViewModel, private val picture: String) {
        private val creating = CompletableDeferred<Unit>()
        private val create = CompletableDeferred<Unit>()

        suspend fun start() {
            dao.beforeInsertChat = {
                creating.complete(Unit)
                create.await()
            }
            vm.attachWith { staged(picture) }
            vm.onText("what is this?")
            vm.send()
            eventually("the send is under way") { creating.isCompleted }
        }

        suspend fun release(): List<File> {
            create.complete(Unit)
            // read from the database: a cleared view model's handle observes it no more
            val user =
                suspend {
                    chats.messages(vm.handle.chatId.value).first().firstOrNull { it.kind == MessageKind.User }
                }
            eventually("stored") { vm.handle.chatId.value != 0L && user() != null }
            return user()!!.images
        }
    }

    private suspend fun heldSend(picture: String): HeldSend =
        HeldSend(viewModel(Catalog.suggested.first { it.sees }.key), picture).also { it.start() }

    @Test fun backDuringASendKeepsTheStoredRowsPicture() =
        runBlocking {
            val send = heldSend("p.jpg")
            // Back: the view model is cleared while its send goes on
            val onCleared = ChatViewModel::class.java.getDeclaredMethod("onCleared").apply { isAccessible = true }
            onCleared.invoke(send.vm)
            assertEquals(listOf(images.file("p.jpg")), send.release())
            assertTrue("the row's picture is there", images.file("p.jpg").exists())
        }

    @Test fun aStarterDuringASendDoesntDeleteTheSentPictures() =
        runBlocking {
            val send = heldSend("p.jpg")
            send.vm.sendStarter("What is rain?")
            assertEquals(listOf(images.file("p.jpg")), send.release())
            assertTrue("the row's picture is there", images.file("p.jpg").exists())
        }

    @Test fun aStarterOrAnEditThatReplacesTheDraftDeletesItsPictures() =
        runBlocking {
            reply("first answer")
            val vm = viewModel()
            vm.attachWith { "starter.jpg" }
            assertEquals(listOf("starter.jpg"), vm.composer.value.attachments)
            vm.sendStarter("What is rain?")
            eventually("deleted") { discarded.toList() == listOf("starter.jpg") }
            eventually("answered") {
                vm.handle.messages.value.size == 2 &&
                    vm.handle.messages.value
                        .all { it.state == MessageState.Done }
            }
            vm.attachWith { "edit.jpg" }
            vm.startEdit(
                vm.handle.messages.value
                    .first { it.kind == MessageKind.User }
                    .position,
            )
            eventually("deleted") { discarded.toList() == listOf("starter.jpg", "edit.jpg") }
        }

    @Test fun attachingKeepsAtMostFourAndRemovingForgets() =
        runBlocking {
            val vm = viewModel()
            var n = 0
            // Main is unconfined and the save never suspends: each pick has finished when attachWith returns
            repeat(6) { vm.attachWith { "pic${n++}.jpg" } }
            assertEquals(listOf("pic0.jpg", "pic1.jpg", "pic2.jpg", "pic3.jpg"), vm.composer.value.attachments)
            vm.removeAttachment("pic1.jpg")
            assertEquals(listOf("pic0.jpg", "pic2.jpg", "pic3.jpg"), vm.composer.value.attachments)
        }

    @Test fun picturesSentToAModelThatCantSeeKeepTheDraftAndSayWhy() =
        runBlocking {
            val vm = viewModel(plain)
            eventually("the model is known") {
                vm.header.value.name
                    .isNotEmpty()
            }
            vm.onText("what is this?")
            vm.attachWith { "pic.jpg" }
            eventually("attached") { vm.composer.value.attachments.size == 1 }
            vm.send()
            eventually("the limit line") {
                vm.limit.value
                    ?.line
                    ?.contains("can't see images") == true
            }
            assertEquals("what is this?", vm.composer.value.text) // nothing was lost
            assertEquals(listOf("pic.jpg"), vm.composer.value.attachments)
            assertTrue(vm.rows.value.isEmpty())
        }

    @Test fun aPhotoThatCantBeOpenedLeavesAPlainLineNotACrash() =
        runBlocking {
            val vm = viewModel()
            vm.attachWith { throw IOException("not an image") }
            eventually("the line") { vm.problem.value != null }
            assertEquals("That photo couldn't be opened.", vm.problem.value)
            assertTrue(
                vm.composer.value.attachments
                    .isEmpty(),
            )
            vm.attachWith { "ok.jpg" }
            eventually("attached") { vm.composer.value.attachments == listOf("ok.jpg") }
            assertNull(vm.problem.value) // a good pick clears it
        }

    @Test fun aPhotoThatLosesTheRaceForTheLastSlotIsDeleted() =
        runBlocking {
            val deleted = Collections.synchronizedList(mutableListOf<String>())
            testHub.install(swift)
            models.changed()
            settings.setLastModel(swift.toString())
            val vm =
                ChatViewModel(
                    engine.open(0),
                    flowOf(MessageStyle.Open),
                    { "x" },
                    { deleted += it },
                    draftThumbnail = { null },
                    savedState = SavedStateHandle(),
                    background = scope,
                ).also(viewModels::add)
            val gate = CompletableDeferred<Unit>()
            var n = 0
            repeat(5) {
                vm.attachWith {
                    gate.await()
                    "p${n++}.jpg"
                }
            } // five in flight at once, room for four
            gate.complete(Unit)
            eventually("four attached and one deleted") { vm.composer.value.attachments.size == 4 && deleted.size == 1 }
        }

    @Test fun whileEditingNoPhotoCanBeAdded() =
        runBlocking {
            reply("ok")
            val vm = viewModel()
            vm.onText("one")
            vm.send()
            eventually("answered") {
                vm.handle.messages.value.size == 2 &&
                    vm.handle.messages.value
                        .all { it.state == MessageState.Done }
            }
            vm.startEdit(
                vm.handle.messages.value
                    .first()
                    .position,
            )
            assertFalse(vm.composerState.value.canAttach)
            // Main is unconfined and the save never suspends: a pick that were taken would be in the draft by now
            vm.attachWith { "late.jpg" }
            assertTrue(
                vm.composer.value.attachments
                    .isEmpty(),
            )
        }

    @Test fun aRestoredDraftDropsAPhotoWhoseFileIsGoneAndSaysSo() =
        runBlocking {
            val saved =
                SavedStateHandle(
                    mapOf("draft.text" to "look", "draft.images" to arrayListOf("kept.jpg", "gone.jpg")),
                )
            val vm = viewModel(saved = saved, draftPictureExists = { it == "kept.jpg" })
            assertEquals(listOf("kept.jpg"), vm.composer.value.attachments)
            assertEquals("look", vm.composer.value.text)
            assertEquals("A photo in your draft is gone.", vm.problem.value)
            // the saved state is cleaned too, so the next restore doesn't say it again
            assertEquals(arrayListOf("kept.jpg"), saved.get<ArrayList<String>>("draft.images"))
        }

    @Test fun aRestoredDraftWithAllItsPhotosSaysNothing() =
        runBlocking {
            val saved = SavedStateHandle(mapOf("draft.images" to arrayListOf("a.jpg", "b.jpg")))
            val vm = viewModel(saved = saved)
            assertEquals(listOf("a.jpg", "b.jpg"), vm.composer.value.attachments)
            assertNull(vm.problem.value)
        }

    @Test fun leavingTheChatClosesItsHandleAndDeletesTheUnsentPictures() =
        runBlocking {
            val deleted = Collections.synchronizedList(mutableListOf<String>())
            testHub.install(swift)
            models.changed()
            settings.setLastModel(swift.toString())
            val vm =
                ChatViewModel(
                    engine.open(0),
                    flowOf(MessageStyle.Open),
                    { "x" },
                    { deleted += it },
                    draftThumbnail = { null },
                    savedState = SavedStateHandle(),
                    background = scope,
                )
            vm.attachWith { "draft.jpg" }
            eventually("attached") { vm.composer.value.attachments == listOf("draft.jpg") }
            val onCleared = ChatViewModel::class.java.getDeclaredMethod("onCleared").apply { isAccessible = true }
            onCleared.invoke(vm)
            eventually("deleted") { deleted == listOf("draft.jpg") }
        }
}
