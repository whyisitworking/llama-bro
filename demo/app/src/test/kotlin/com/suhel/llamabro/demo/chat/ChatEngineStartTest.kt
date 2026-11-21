package com.suhel.llamabro.demo.chat

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.suhel.llamabro.demo.data.ChatRepository
import com.suhel.llamabro.demo.data.ImageStore
import com.suhel.llamabro.demo.data.MessageKind
import com.suhel.llamabro.demo.data.SettingsRepository
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
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** A new chat started on a model (a model card's "Chat with"): that model, and no chat until the first message. */
class ChatEngineStartTest {
    @get:Rule val folder = TemporaryFolder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val swift = HfModel.parse("unsloth/Qwen3.5-0.8B-GGUF:Q4_K_M")
    private val deep = HfModel.parse("unsloth/Qwen3.5-4B-GGUF:Q4_K_M")

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
    private val engine by lazy { ChatEngine(chats, host, models, settings, System::currentTimeMillis, scope) }

    @After fun stop() {
        runBlocking { host.release() }
        scope.cancel()
        testHub.close()
    }

    private suspend fun until(what: String, condition: suspend () -> Boolean) {
        withTimeoutOrNull(10_000) { while (!condition()) delay(10) }
            ?: throw AssertionError("timed out waiting for: $what")
    }

    @Test fun aNewChatStartedOnAModelUsesItAndMakesNoChatUntilItsFirstMessage() =
        runBlocking {
            fake.reply { content("hi") }
            testHub.install(swift, deep)
            models.changed()
            settings.setLastModel(swift.toString())
            settings.pinDefault(swift.toString())
            val chat = engine.open(0, startModel = deep)
            until("the model is known") { chat.current.value != null }
            assertEquals("the model asked for wins over the default", deep, chat.current.value)
            delay(200)
            assertEquals("no chat until the first message", 0L, chat.chatId.value)
            assertTrue("no chat row", chats.chats().first().isEmpty())
            chat.send("hello", emptyList())
            until("answered") { chat.messages.value.size == 2 }
            assertEquals(deep.toString(), chats.modelKey(chat.chatId.value))
            assertTrue("no switch divider", chat.messages.value.none { it.kind == MessageKind.Switch })
            chat.close()
        }

    @Test fun aNewChatWithNoModelChosenAsksForOneInItsHeader() =
        runBlocking {
            // the header was blank for a new chat with no model on the phone or chosen
            val chat = engine.open(0)
            until("looked up") { chat.name.value.isNotEmpty() }
            assertEquals(NO_MODEL_TITLE, chat.name.value)
        }

    @Test fun aNewChatWithNoModelChosenShowsNoLoadingLine() =
        runBlocking {
            // a model on the phone, downloaded from its card, but no default, no last model and no chats
            testHub.install(swift)
            models.changed()
            val chat = engine.open(0)
            until("looked up") { chat.name.value == NO_MODEL_TITLE }
            until("no model, not waking") { chat.model.value == ChatModelState.NoModel }
            chat.close()
        }

    @Test fun choosingAModelInANewChatWithNoneSetsIt() =
        runBlocking {
            testHub.install(swift)
            models.changed()
            val chat = engine.open(0)
            until("looked up") { chat.name.value == NO_MODEL_TITLE }
            chat.switchModel(swift)
            until("the chat's model is the one chosen") { chat.current.value == swift }
            until("the chat row exists with that model") {
                chat.chatId.value != 0L && chats.modelKey(chat.chatId.value) == swift.toString()
            }
            until("the model wakes") {
                chat.model.value.let { it is ChatModelState.Waking || it is ChatModelState.Ready }
            }
            until("remembered for the next new chat") { settings.settings.first().lastModel == swift.toString() }
            assertEquals("Qwen3.5 0.8B", chat.name.value)
            assertTrue("no switch divider", chat.messages.value.none { it.kind == MessageKind.Switch })
            chat.close()
        }

    @Test fun aSwitchBeforeTheLookupEndsStands() =
        runBlocking {
            testHub.install(swift, deep)
            models.changed()
            settings.pinDefault(swift.toString())
            val chat = engine.open(0)
            chat.switchModel(deep) // at once: the lookup of the default may not have answered yet
            until("the chat's model is the one chosen") { chat.current.value == deep }
            delay(300)
            assertEquals("the lookup's default doesn't replace the switch", deep, chat.current.value)
            assertEquals("Qwen3.5 4B", chat.name.value)
            chat.close()
        }

    @Test fun theLookupKeepsAKeyChosenMeanwhileAndOtherwiseSaysWhatItFound() {
        val chosen = ModelChoice.Chosen(deep)
        assertEquals("a send's or a switch's key stands", chosen, chosen.lookedUp(swift))
        assertEquals("a send's key stands when the lookup finds none", chosen, chosen.lookedUp(null))
        assertEquals(ModelChoice.Chosen(swift), ModelChoice.Unknown.lookedUp(swift))
        assertEquals("none found, none chosen", ModelChoice.None, ModelChoice.Unknown.lookedUp(null))
        assertEquals(null, ModelChoice.None.key)
        assertEquals(deep, chosen.key)
    }
}
