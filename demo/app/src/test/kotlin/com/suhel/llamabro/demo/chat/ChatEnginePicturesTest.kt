package com.suhel.llamabro.demo.chat

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.suhel.llamabro.demo.data.ChatRepository
import com.suhel.llamabro.demo.data.ImageStore
import com.suhel.llamabro.demo.data.MessageKind
import com.suhel.llamabro.demo.data.SettingsRepository
import com.suhel.llamabro.demo.data.StoredMessage
import com.suhel.llamabro.demo.eventually
import com.suhel.llamabro.demo.models.Catalog
import com.suhel.llamabro.demo.models.FakeScheduler
import com.suhel.llamabro.demo.models.ModelRepository
import com.suhel.llamabro.demo.models.TestHub
import com.suhel.llamabro.demo.models.installedModel
import com.suhel.llamabro.hub.HfModel
import com.suhel.llamabro.testing.FakeLlamaBro
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * A message's pictures through the engine: refused by a model that can't see, moved out of the draft when the message
 * is stored, and given back when it isn't (the engine's set-up as ChatEngineTest's).
 */
class ChatEnginePicturesTest {
    @get:Rule val folder = TemporaryFolder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val swift = HfModel.parse("unsloth/Qwen3.5-0.8B-GGUF:Q4_K_M")
    private val llamaKey: HfModel = Catalog.suggested.first { !it.sees && !it.thinks }.key

    private val fake = FakeLlamaBro()
    private val testHub = TestHub()
    private val hub get() = testHub.hub
    private val dao = FakeChatDao()
    private val settings by lazy {
        SettingsRepository(
            PreferenceDataStoreFactory.create(scope = scope) { File(folder.root, "settings.preferences_pb") },
        )
    }
    private val images by lazy { ImageStore(File(folder.root, "images")) }
    private val chats by lazy { ChatRepository(dao, images, System::currentTimeMillis) }
    private val host by lazy { ModelHost(fake.llamaBro, { hub.installedModel(it) }, scope) }
    private val models by lazy { ModelRepository(hub, settings, FakeScheduler(), scope, host) }
    private val engine by lazy { ChatEngine(chats, host, models, settings, System::currentTimeMillis, scope) }

    @After fun stop() {
        runBlocking { host.release() }
        scope.cancel()
        testHub.close()
    }

    private fun onPhone(vararg keys: HfModel) {
        testHub.install(*keys)
        models.changed()
    }

    private fun rows(handle: ChatEngine.ChatHandle): List<StoredMessage> = handle.messages.value

    private fun staged(name: String): File =
        images.draftFile(name).apply {
            parentFile!!.mkdirs()
            writeBytes(byteArrayOf(1))
        }

    @Test fun picturesToAModelThatCantSeeAreRefusedWithTheLine() =
        runBlocking {
            settings.setLastModel(llamaKey.toString())
            onPhone(llamaKey)
            val chat = engine.open(0)
            eventually("the model is known") { chat.current.value == llamaKey }
            eventually("ready") { chat.model.value is ChatModelState.Ready }
            assertFalse(chat.send("what is this?", listOf("pic.jpg")))
            assertTrue(
                chat.limit.value
                    ?.line
                    ?.contains("can't see images") == true,
            )
            assertTrue(rows(chat).isEmpty())
        }

    @Test fun aSentDraftsPicturesMoveIntoTheMessagesFolder() =
        runBlocking {
            val seer = Catalog.suggested.first { it.sees }.key
            settings.setLastModel(seer.toString())
            onPhone(seer)
            val draft = images.draftFile("p.jpg").apply { parentFile!!.mkdirs() }
            draft.writeBytes(byteArrayOf(1))
            val chat = engine.open(0)
            assertTrue(chat.send("what is this?", listOf("p.jpg")))
            assertFalse("the draft's copy is gone", draft.exists())
            assertTrue(images.file("p.jpg").exists())
            eventually("the row names it") { rows(chat).firstOrNull()?.images == listOf(images.file("p.jpg")) }
        }

    @Test fun aSendCancelledOnceItsMessageIsStoredKeepsItsPictureWhereTheRowNamesIt() =
        runBlocking {
            // the database stored the row, and the call resumes cancelled: the picture must not go back to the draft
            val seer = Catalog.suggested.first { it.sees }.key
            settings.setLastModel(seer.toString())
            val draft = staged("p.jpg")
            val chat = engine.open(0)
            eventually("the model is known") { chat.current.value == seer }
            val stored = CompletableDeferred<Unit>()
            val resume = CompletableDeferred<Unit>()
            dao.afterInsertMessage = {
                stored.complete(Unit)
                resume.await()
            }
            val send = scope.launch { chat.send("what is this?", listOf("p.jpg")) }
            stored.await()
            send.cancel()
            resume.complete(Unit)
            send.join()
            assertFalse(draft.exists())
            assertTrue(images.file("p.jpg").exists())
            eventually("the row names it") { rows(chat).firstOrNull()?.images == listOf(images.file("p.jpg")) }
        }

    @Test fun aSendNamingASentPictureFailsAndOneNotStoredGivesItsPicturesBack() =
        runBlocking {
            val seer = Catalog.suggested.first { it.sees }.key
            settings.setLastModel(seer.toString())
            staged("a.jpg")
            val chat = engine.open(0)
            eventually("the model is known") { chat.current.value == seer }
            assertTrue(chat.send("first", listOf("a.jpg")))
            staged("b.jpg")
            // a.jpg is the first message's now: a second row naming it would share its file
            assertTrue(runCatching { chat.send("again", listOf("a.jpg", "b.jpg")) }.isFailure)
            assertTrue("the first message keeps its picture", images.file("a.jpg").exists())
            assertTrue("nothing moved", images.draftFile("b.jpg").exists())
            assertEquals(1, rows(chat).count { it.kind == MessageKind.User })
            dao.beforeInsertMessage = { error("disk full") }
            assertTrue(runCatching { chat.send("again", listOf("b.jpg")) }.isFailure)
            assertTrue("the picture is the draft's again", images.draftFile("b.jpg").exists())
            assertFalse(images.file("b.jpg").exists())
        }

    @Test fun imageToANonVisionModelIsALimitNotASend() =
        runBlocking {
            val llama = HfModel.parse("unsloth/Llama-3.2-3B-Instruct-GGUF:Q4_K_M")
            settings.setLastModel(llama.toString())
            onPhone(llama, swift)
            val chat = engine.open(0)
            eventually("model ready") { chat.model.value is ChatModelState.Ready }
            chat.send("look", listOf("a.jpg"))
            eventually("limit shown") { chat.limit.value != null }
            assertEquals("Llama 3.2 3B can't see images. Qwen3.5 0.8B can.", chat.limit.value!!.line)
            assertEquals(LimitAction.UseVisionModel, chat.limit.value!!.action)
            assertEquals(0, fake.chatRequests.size)
            assertEquals(0, rows(chat).size)
        }
}
