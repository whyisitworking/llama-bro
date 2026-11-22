package com.suhel.llamabro.demo.chat

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.suhel.llamabro.demo.data.ChatRepository
import com.suhel.llamabro.demo.data.ImageStore
import com.suhel.llamabro.demo.data.MessageKind
import com.suhel.llamabro.demo.data.MessageState
import com.suhel.llamabro.demo.data.SettingsRepository
import com.suhel.llamabro.demo.data.StoredMessage
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
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** D60 in the engine: a turn's sampling and length limit, a reply cut at the limit, and Continue. */
class ChatEngineSamplingTest {
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
    private val engine by lazy { ChatEngine(chats, host, models, settings, System::currentTimeMillis, scope) }
    private val store by lazy { SamplingStore(settings, chats) }

    @After fun stop() {
        runBlocking { host.release() }
        scope.cancel()
        testHub.close()
    }

    private suspend fun until(what: String, condition: suspend () -> Boolean) {
        withTimeoutOrNull(10_000) { while (!condition()) delay(10) } ?: throw AssertionError("timed out: $what")
    }

    private suspend fun newChat(): ChatEngine.ChatHandle {
        settings.setLastModel(swift.toString())
        testHub.install(swift)
        models.changed()
        return engine.open(0)
    }

    private fun rows(chat: ChatEngine.ChatHandle): List<StoredMessage> = chat.messages.value

    private fun request(n: Int): JsonObject = Json.parseToJsonElement(fake.chatRequests[n]).jsonObject

    private fun JsonObject.float(key: String) = getValue(key).jsonPrimitive.float

    @Test fun aTurnSendsTheCardsValuesForItsModeAndTheLengthLimit() =
        runBlocking {
            fake.reply { content("Hi.") }
            val chat = newChat()
            chat.send("Hello", emptyList())
            until("answered") { rows(chat).lastOrNull()?.state == MessageState.Done }
            // Think is Off: Qwen3.5 0.8B's card without thinking, and the answer's share of the limit
            val body = request(0)
            assertEquals(1f, body.float("temperature"))
            assertEquals(1f, body.float("top_p"))
            assertEquals(20, body.getValue("top_k").jsonPrimitive.int)
            assertEquals(2f, body.float("presence_penalty"))
            assertEquals(2_048, body.getValue("max_tokens").jsonPrimitive.int)
        }

    @Test fun aTurnOnAModelTheCatalogLacksDoesNotWaitForTheHubsValues() =
        runBlocking {
            // the hub's lookup hangs, as on Wi-Fi with no route: the turn goes on without a card
            val thing = HfModel.parse("someone/Thing-GGUF:Q4_K_M")
            val hanging = SamplingStore(settings, chats, { awaitCancellation() }, scope)
            val engine =
                ChatEngine(chats, host, models, settings, System::currentTimeMillis, scope, card = hanging::card)
            fake.reply { content("Hi.") }
            settings.setLastModel(thing.toString())
            testHub.install(thing)
            models.changed()
            val chat = engine.open(0)
            chat.send("Hello", emptyList())
            withTimeoutOrNull(10_000) { while (rows(chat).lastOrNull()?.state != MessageState.Done) delay(10) }
                ?: throw AssertionError("the turn waited for the lookup")
            // nothing the hub didn't say is sent, so the file's own values apply (F17)
            assertFalse(request(0).containsKey("temperature"))
        }

    @Test fun openingAChatOnAModelTheCatalogLacksStartsTheHubsLookup() =
        runBlocking {
            // looked up as the chat opens, not at its first turn, so the first reply can have the hub's values too
            val thing = HfModel.parse("someone/Thing-GGUF:Q4_K_M")
            val asked = CompletableDeferred<HfModel>()
            val engine =
                ChatEngine(chats, host, models, settings, System::currentTimeMillis, scope, card = {
                    asked.complete(it)
                    null
                })
            settings.setLastModel(thing.toString())
            testHub.install(thing)
            models.changed()
            engine.open(0)
            assertEquals(thing, withTimeoutOrNull(5_000) { asked.await() })
            assertEquals("no turn yet", 0, fake.chatRequests.size)
        }

    @Test fun aChatOnAModelNotOnThePhoneLooksNothingUpUntilItArrives() =
        runBlocking {
            // the lookup reads the file's header, which for a model not on the phone is a network request (D64)
            val thing = HfModel.parse("someone/Thing-GGUF:Q4_K_M")
            val asked = CompletableDeferred<HfModel>()
            val engine =
                ChatEngine(chats, host, models, settings, System::currentTimeMillis, scope, card = {
                    asked.complete(it)
                    null
                })
            settings.setLastModel(thing.toString())
            val chat = engine.open(0)
            until("the model is known") { chat.current.value == thing }
            delay(300)
            assertFalse("asked before the model was on the phone", asked.isCompleted)
            testHub.install(thing)
            models.changed()
            assertEquals(thing, withTimeoutOrNull(5_000) { asked.await() })
        }

    @Test fun aChatsOwnValueGoesOverTheModelsAndTheCards() =
        runBlocking {
            fake.reply { content("One.") }
            val chat = newChat()
            chat.send("Hello", emptyList())
            until("answered") { rows(chat).lastOrNull()?.state == MessageState.Done }
            store.setModel(swift, Knob.Temperature, 0.5f)
            store.setModel(swift, Knob.TopK, 30f)
            store.setChat(chat.chatId.value, Knob.Temperature, 0.2f)
            fake.reply { content("Two.") }
            chat.send("Again", emptyList())
            until("answered again") {
                rows(chat).count {
                    it.state == MessageState.Done &&
                        it.kind == MessageKind.Assistant
                } ==
                    2
            }
            val body = request(1)
            assertEquals(0.2f, body.float("temperature"))
            assertEquals(30, body.getValue("top_k").jsonPrimitive.int)
            store.resetChat(chat.chatId.value)
            assertEquals(Knobs.None, chats.sampling(chat.chatId.value).first())
        }

    @Test fun aReplyAtItsLimitIsCutAndContinueGoesOnInTheSameRow() =
        runBlocking {
            fake.reply {
                reasoning("Counting.")
                content("One, two,")
                finishLength()
            }
            val chat = newChat()
            chat.send("Count to four", emptyList())
            until("cut") { rows(chat).lastOrNull()?.state == MessageState.Cut }
            val cut = rows(chat).last()
            assertEquals("One, two,", cut.text)

            fake.reply { content(" three, four.") }
            chat.continueReply(cut.position)
            until("continued") { rows(chat).lastOrNull()?.state == MessageState.Done }
            assertEquals(listOf(MessageKind.User, MessageKind.Assistant), rows(chat).map { it.kind })
            assertEquals("One, two, three, four.", rows(chat).last().text)
            assertEquals("Counting.", rows(chat).last().reasoning)
            // the continue sends the chat with the cut reply last, its thinking with it: llama-server goes on with it
            val messages = request(1).getValue("messages") as JsonArray
            val last = messages.last().jsonObject
            assertEquals("assistant", last.getValue("role").jsonPrimitive.content)
            assertEquals("One, two,", last.getValue("content").jsonPrimitive.content)
            assertEquals("Counting.", last.getValue("reasoning_content").jsonPrimitive.content)
        }

    @Test fun onlyTheChatsLastRowCanBeContinued() =
        runBlocking {
            fake.reply {
                content("Cut")
                finishLength()
            }
            val chat = newChat()
            chat.send("One", emptyList())
            until("cut") { rows(chat).lastOrNull()?.state == MessageState.Cut }
            val cut = rows(chat).last()
            fake.reply { content("Next.") }
            chat.send("Two", emptyList())
            until("answered") { rows(chat).lastOrNull()?.state == MessageState.Done }
            chat.continueReply(cut.position)
            delay(200)
            assertEquals(2, fake.chatRequests.size)
            assertEquals(MessageState.Cut, rows(chat).first { it.id == cut.id }.state)
        }
}
