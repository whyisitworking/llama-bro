package com.suhel.llamabro.demo.chat

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.suhel.llamabro.MediaSource
import com.suhel.llamabro.chat.ChatEvent
import com.suhel.llamabro.chat.Content
import com.suhel.llamabro.demo.data.ChatRepository
import com.suhel.llamabro.demo.data.ImageStore
import com.suhel.llamabro.demo.data.MessageState
import com.suhel.llamabro.demo.data.NewVoice
import com.suhel.llamabro.demo.data.SettingsRepository
import com.suhel.llamabro.demo.data.StoredMessage
import com.suhel.llamabro.demo.models.FakeScheduler
import com.suhel.llamabro.demo.models.ModelRepository
import com.suhel.llamabro.demo.models.TestHub
import com.suhel.llamabro.demo.models.installedModel
import com.suhel.llamabro.hub.HfModel
import com.suhel.llamabro.testing.FakeLlamaBro
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Voice messages through the engine (D76): who may take one, and how it reaches the model. */
class ChatEngineVoiceTest {
    @get:Rule val folder = TemporaryFolder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val swift = HfModel.parse("unsloth/Qwen3.5-0.8B-GGUF:Q4_K_M")

    private val fake = FakeLlamaBro()
    private val testHub = TestHub()
    private val hub get() = testHub.hub
    private val settings by lazy {
        SettingsRepository(
            PreferenceDataStoreFactory.create(scope = scope) { File(folder.root, "settings.preferences_pb") },
        )
    }
    private val chats by lazy {
        ChatRepository(FakeChatDao(), ImageStore(File(folder.root, "images")), System::currentTimeMillis)
    }
    private val host by lazy { ModelHost(fake.llamaBro, { hub.installedModel(it) }, scope) }
    private val models by lazy { ModelRepository(hub, settings, FakeScheduler(), scope, host) }

    private var streamer: Streamer? = null
    private val engine by lazy {
        val custom = streamer
        if (custom != null) {
            ChatEngine(chats, host, models, settings, System::currentTimeMillis, scope, streamer = custom)
        } else {
            ChatEngine(chats, host, models, settings, System::currentTimeMillis, scope)
        }
    }

    @After fun stop() {
        runBlocking { host.release() }
        scope.cancel()
        testHub.close()
    }

    private suspend fun until(what: String, timeoutMillis: Long = 10_000, condition: suspend () -> Boolean) {
        withTimeoutOrNull(timeoutMillis) { while (!condition()) delay(10) }
            ?: throw AssertionError("timed out waiting for: $what")
    }

    private fun rows(handle: ChatEngine.ChatHandle): List<StoredMessage> = handle.messages.value

    private suspend fun newChat(): ChatEngine.ChatHandle {
        settings.setLastModel(swift.toString())
        testHub.install(swift)
        models.changed()
        return engine.open(0)
    }

    @Test fun aVoiceMessageToAModelThatCantHearIsRefusedWithTheLine() =
        runBlocking {
            val chat = newChat()
            until("ready") { chat.model.value is ChatModelState.Ready }
            assertFalse((chat.model.value as ChatModelState.Ready).hears)
            assertFalse(chat.send("", emptyList(), NewVoice("a.wav", 1_000, emptyList())))
            assertTrue(
                chat.limit.value
                    ?.line
                    ?.contains("can't hear voice messages") == true,
            )
            assertTrue(rows(chat).isEmpty())
        }

    @Test fun aModelThatHearsIsRememberedAndGetsTheVoiceMessageAsItsFile() =
        runBlocking {
            fake.supportsAudio = true
            val sent = mutableListOf<List<Content>>()
            streamer = { _, _, media, _ ->
                sent += media.toList()
                flow { emit(ChatEvent.ContentDelta("heard")) }
            }
            val chat = newChat()
            assertTrue(chat.send("hi"))
            until("the first reply") { rows(chat).count { it.state == MessageState.Done } == 2 }
            until("the model is known to hear") { (chat.model.value as? ChatModelState.Ready)?.hears == true }
            assertTrue(swift.toString() in settings.hearingModels.first())
            assertTrue(chat.send("", emptyList(), NewVoice("a.wav", 1_000, listOf(0.5f))))
            until("the second reply") { rows(chat).count { it.state == MessageState.Done } == 4 }
            val audio = sent.last().filterIsInstance<Content.Audio>().single()
            // this repository has no audio store, so the stored name is the file's whole path
            assertEquals(MediaSource.file("a.wav"), audio.source)
            assertEquals(1_000L, rows(chat)[2].voice?.millis)
        }
}
