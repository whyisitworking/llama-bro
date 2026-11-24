package com.suhel.llamabro.demo.chat

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.suhel.llamabro.LlamaBroException
import com.suhel.llamabro.chat.ChatEvent
import com.suhel.llamabro.demo.data.ChatRepository
import com.suhel.llamabro.demo.data.ImageStore
import com.suhel.llamabro.demo.data.MessageKind
import com.suhel.llamabro.demo.data.MessageState
import com.suhel.llamabro.demo.data.NewMessage
import com.suhel.llamabro.demo.data.SettingsRepository
import com.suhel.llamabro.demo.data.StoredMessage
import com.suhel.llamabro.demo.eventually
import com.suhel.llamabro.demo.models.Catalog
import com.suhel.llamabro.demo.models.DownloadFailure
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
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ChatEngineTest {
    @get:Rule val folder = TemporaryFolder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val swift = HfModel.parse("unsloth/Qwen3.5-0.8B-GGUF:Q4_K_M")
    private val deep = HfModel.parse("unsloth/Qwen3.5-4B-GGUF:Q4_K_M")
    private val llamaKey: HfModel = Catalog.suggested.first { !it.sees && !it.thinks }.key

    private val fake = FakeLlamaBro()
    private val testHub = TestHub()
    private val hub get() = testHub.hub
    private val dao = FakeChatDao()
    private val scheduler = FakeScheduler()
    private val settings by lazy {
        SettingsRepository(
            PreferenceDataStoreFactory.create(scope = scope) { File(folder.root, "settings.preferences_pb") },
        )
    }
    private val images by lazy { ImageStore(File(folder.root, "images")) }
    private val chats by lazy { ChatRepository(dao, images, System::currentTimeMillis) }
    private val host by lazy { ModelHost(fake.llamaBro, { hub.installedModel(it) }, scope) }
    private val models by lazy { ModelRepository(hub, settings, scheduler, scope, host) }

    private var streamer: Streamer? = null
    private var frameMillis = 16L
    private val engine by lazy {
        val custom = streamer
        if (custom != null) {
            ChatEngine(chats, host, models, settings, System::currentTimeMillis, scope, frameMillis, streamer = custom)
        } else {
            ChatEngine(chats, host, models, settings, System::currentTimeMillis, scope, frameMillis)
        }
    }

    @After fun stop() {
        runBlocking { host.release() }
        scope.cancel()
        testHub.close()
    }

    private fun onPhone(vararg keys: HfModel) {
        testHub.install(*keys)
        models.changed()
    }

    private suspend fun rows(handle: ChatEngine.ChatHandle): List<StoredMessage> = handle.messages.value

    private fun reply(text: String, reasoning: String? = null) =
        fake.reply {
            reasoning?.let { reasoning(it) }
            content(text)
            timings(promptTokens = 10, predictedTokens = 20)
        }

    private suspend fun newChat(): ChatEngine.ChatHandle {
        settings.setLastModel(swift.toString())
        onPhone(swift)
        return engine.open(0)
    }

    @Test fun replyStreamsIntoOneStoredRow() =
        runBlocking {
            reply("Hello there.", reasoning = "thinking it over")
            val chat = newChat()
            chat.send("Hi", emptyList())
            eventually("reply done") {
                rows(chat).any {
                    it.kind == MessageKind.Assistant &&
                        it.state == MessageState.Done
                }
            }
            val all = rows(chat)
            assertEquals(listOf(MessageKind.User, MessageKind.Assistant), all.map { it.kind })
            val a = all[1]
            assertEquals("Hello there.", a.text)
            assertEquals("thinking it over", a.reasoning)
            assertNotNull(a.reasoningMillis)
            assertTrue(a.wordsPerSecond != null && a.wordsPerSecond!! > 0)
            assertEquals(MessageState.Done, all[0].state)
            assertNull(chat.streaming.value)
        }

    @Test fun aRefusedDownloadBecomesAPlainLineAndRetryStartsItAgain() =
        runBlocking {
            settings.setLastModel(swift.toString())
            testHub.refuse(swift, TestHub.Refusal.Unauthorized)
            val chat = engine.open(0)
            eventually("the model says its download stopped") { chat.model.value is ChatModelState.DownloadFailed }
            // the held message is still waiting for it: the line says why, and what the user can do about it
            chat.send("hello", emptyList())
            eventually("held") { rows(chat).size == 1 }
            assertEquals(MessageState.Held, rows(chat).single().state)
            assertEquals(
                Limits.forDownload(DownloadFailure(DownloadFailure.Kind.Unauthorized, retryable = false)),
                (chat.model.value as ChatModelState.DownloadFailed).let { Limits.forDownload(it.failure) },
            )

            chat.retryDownload()
            eventually("the download was started again") { scheduler.enqueued == listOf(swift) }
        }

    @Test fun heldMessagesAreAnsweredInOrderOnce() =
        runBlocking {
            reply("first answer")
            reply("second answer")
            settings.setLastModel(swift.toString())
            testHub.downloading(swift)
            val chat = engine.open(0)
            chat.send("one", emptyList())
            eventually("first held") { rows(chat).size == 1 }
            chat.send("two", emptyList())
            eventually("both held") { rows(chat).size == 2 }
            assertEquals(listOf(MessageState.Held, MessageState.Held), rows(chat).map { it.state })
            assertEquals(0, fake.chatRequests.size)

            onPhone(swift) // the download finished
            eventually("both answered") {
                rows(chat).count { it.kind == MessageKind.Assistant && it.state == MessageState.Done } ==
                    2
            }
            assertEquals(2, fake.chatRequests.size)
            // each held message is answered once, in order; the replies follow the held questions
            assertEquals(listOf("one", "two", "first answer", "second answer"), rows(chat).map { it.text })
            assertTrue(fake.chatRequests[0].contains("one") && fake.chatRequests[1].contains("first answer"))
        }

    @Test fun stopKeepsWhatArrived() =
        runBlocking {
            streamer = { _, _, _, _ ->
                flow {
                    emit(ChatEvent.ContentDelta("part one "))
                    delay(60_000)
                }
            }
            val chat = newChat()
            chat.send("go", emptyList())
            eventually("started") { rows(chat).any { it.kind == MessageKind.Assistant } && chat.busy.value }
            eventually("text arrived") { chat.streaming.value?.text == "part one " }
            chat.stop()
            eventually("stopped") { rows(chat).any { it.state == MessageState.Stopped } }
            assertEquals("part one ", rows(chat).first { it.kind == MessageKind.Assistant }.text)
            eventually("idle") { !chat.busy.value }
        }

    @Test fun regenerateReplacesTheReplyAndRebuildsFromHistory() =
        runBlocking {
            reply("first")
            reply("second")
            val chat = newChat()
            chat.send("question", emptyList())
            eventually(
                "first answered",
            ) { rows(chat).any { it.state == MessageState.Done && it.kind == MessageKind.Assistant } }
            chat.regenerate(1)
            eventually("regenerated") {
                rows(chat).let { r ->
                    r.lastOrNull()?.text == "second" &&
                        r.last().state == MessageState.Done
                }
            }
            assertEquals(listOf("question", "second"), rows(chat).map { it.text })
            // the second request carries only the question: the old reply is not in it
            assertTrue(!fake.chatRequests.last().contains("first"))
        }

    @Test fun editTruncatesAndResends() =
        runBlocking {
            reply("a1")
            reply("a2")
            val chat = newChat()
            chat.send("original", emptyList())
            eventually("answered") { rows(chat).let { r -> r.size == 2 && r[1].state == MessageState.Done } }
            chat.edit(0, "edited")
            eventually("re-answered") {
                rows(chat).let { r ->
                    r.size == 2 && r[1].text == "a2" && r[1].state == MessageState.Done
                }
            }
            assertEquals(listOf("edited", "a2"), rows(chat).map { it.text })
            assertTrue(fake.chatRequests.last().contains("edited") && !fake.chatRequests.last().contains("original"))
        }

    @Test fun switchAddsADividerAndUsesTheNewModel() =
        runBlocking {
            reply("hi")
            reply("again")
            val chat = newChat()
            onPhone(deep)
            chat.send("hello", emptyList())
            eventually("answered") { rows(chat).let { r -> r.size == 2 && r[1].state == MessageState.Done } }
            chat.switchModel(deep)
            eventually("divider") { rows(chat).any { it.kind == MessageKind.Switch } }
            assertEquals("Switched to Qwen3.5 4B", rows(chat).first { it.kind == MessageKind.Switch }.text)
            eventually("recorded") {
                settings.settings.first().lastModel == deep.toString() &&
                    chats.modelKey(chat.chatId.value) == deep.toString()
            }
            chat.send("more", emptyList())
            eventually("answered by deep") {
                rows(chat).let { r ->
                    r.size == 5 && r.last().kind == MessageKind.Assistant &&
                        r.last().state == MessageState.Done
                }
            }
            assertEquals(deep.toString(), rows(chat).last().modelKey)
            assertTrue(fake.chatRequests.last().contains("hello")) // the full history goes to the new model
        }

    @Test fun theHandleNamesItsModelInEveryState() =
        runBlocking {
            reply("hi")
            val chat = newChat()
            eventually("named") { chat.name.value == "Qwen3.5 0.8B" && chat.current.value == swift }
            chat.switchModel(deep)
            eventually("switched") { chat.name.value == "Qwen3.5 4B" && chat.current.value == deep }
            Unit
        }

    @Test fun sendSaysWhetherItWasTaken() =
        runBlocking {
            reply("ok")
            val chat = newChat()
            eventually("the model is known") { chat.name.value.isNotEmpty() }
            assertFalse(chat.send("   ")) // nothing to send
            assertTrue(chat.send("hello"))
            streamer = null
            Unit
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

    @Test fun closingAHandleStopsItsReplyAndItsObservers() =
        runBlocking {
            streamer = { _, _, _, _ ->
                flow {
                    emit(ChatEvent.ContentDelta("writing"))
                    delay(60_000)
                }
            }
            val chat = newChat()
            chat.send("go")
            eventually("streaming") { chat.streaming.value != null }
            chat.close()
            eventually(
                "saved as stopped",
            ) { chats.messages(chat.chatId.value).first().any { it.state == MessageState.Stopped } }
        }

    @Test fun customInstructionsAreTheSystemPrompt() =
        runBlocking {
            reply("ok")
            settings.setInstructions("a nurse", "short answers")
            val chat = newChat()
            chat.send("hi", emptyList())
            eventually("answered") { rows(chat).let { r -> r.size == 2 && r[1].state == MessageState.Done } }
            val body = fake.chatRequests.single()
            assertTrue(body, body.contains("About me: a nurse") && body.contains("How to answer: short answers"))
        }

    @Test fun instructionsChangeAppliesToNewChatsAndRebuilds() =
        runBlocking {
            reply("one")
            reply("two")
            settings.setInstructions("a nurse", "short answers")
            val chat = newChat()
            chat.send("first", emptyList())
            eventually("answered") { rows(chat).let { r -> r.size == 2 && r[1].state == MessageState.Done } }
            settings.setInstructions("a pilot", "long answers")
            chat.send("second", emptyList())
            eventually("answered again") { rows(chat).let { r -> r.size == 4 && r[3].state == MessageState.Done } }
            val second = fake.chatRequests.last()
            assertTrue(second, second.contains("About me: a pilot") && !second.contains("a nurse"))
            assertTrue("the history is rebuilt, not lost", second.contains("first") && second.contains("one"))
        }

    @Test fun noInstructionsNoSystemPrompt() =
        runBlocking {
            reply("ok")
            val chat = newChat()
            chat.send("hi", emptyList())
            eventually("answered") { rows(chat).let { r -> r.size == 2 && r[1].state == MessageState.Done } }
            assertTrue(!fake.chatRequests.single().contains("\"system\""))
        }

    @Test fun contextExceededOffersANewChat() =
        runBlocking {
            fake.contextExceeded(promptTokens = 9_000, contextSize = 4_096)
            val chat = newChat()
            chat.send("a very long message", emptyList())
            eventually("limit shown") { chat.limit.value != null }
            assertEquals(LimitAction.StartNewChat, chat.limit.value!!.action)
            assertEquals("This chat is longer than Qwen3.5 0.8B can hold.", chat.limit.value!!.line)
            assertTrue(rows(chat).any { it.kind == MessageKind.Assistant && it.state == MessageState.Failed })
        }

    @Test fun aModelTheMemoryCheckDoubtsAnswersWithANote() =
        runBlocking {
            // the SDK's check refuses the first, budgeted load; with MemoryCheck.Warn it loads at the smallest context
            fake.failLoad(LlamaBroException.ModelLoadFailed.Reason.InsufficientMemory, "not enough memory", times = 1)
            fake.reply { content("Hi!") }
            val chat = newChat()
            chat.send("hello", emptyList())
            eventually(
                "answered",
            ) { rows(chat).any { it.kind == MessageKind.Assistant && it.state == MessageState.Done } }
            // a note, not a wall (F128): the reply came, and the line offers a smaller size
            assertEquals(Limits.memoryWarning("Qwen3.5 0.8B"), chat.limit.value)
            assertEquals(LimitAction.TrySmallerSize, chat.limit.value!!.action)
            // the model stays loaded with its note; the next turn doesn't say it again
            fake.reply { content("Again.") }
            chat.send("once more", emptyList())
            eventually("answered twice") {
                rows(chat).count { it.kind == MessageKind.Assistant && it.state == MessageState.Done } == 2
            }
            assertEquals(null, chat.limit.value)
            // freed (the app left the screen) and loaded again by the next message, doubted again: noted again
            assertTrue(engine.releaseModelIfIdle())
            fake.failLoad(LlamaBroException.ModelLoadFailed.Reason.InsufficientMemory, "not enough memory", times = 1)
            fake.reply { content("Back.") }
            chat.send("and again", emptyList())
            eventually("answered three times") {
                rows(chat).count { it.kind == MessageKind.Assistant && it.state == MessageState.Done } == 3
            }
            assertEquals(Limits.memoryWarning("Qwen3.5 0.8B"), chat.limit.value)
        }

    @Test fun aLoadThatFailsForMemoryAllTheSameOffersASmallerSize() =
        runBlocking {
            // every load fails, the smallest context too: the phone really has no room
            fake.failLoad(LlamaBroException.ModelLoadFailed.Reason.InsufficientMemory, "not enough memory", times = 100)
            val chat = newChat()
            chat.send("hello", emptyList())
            eventually("limit shown") { chat.limit.value != null }
            assertEquals(LimitAction.TrySmallerSize, chat.limit.value!!.action)
            assertEquals(LimitAction.Retry, chat.limit.value!!.second)
            assertEquals("Qwen3.5 0.8B needs more memory than this phone has free right now.", chat.limit.value!!.line)
            assertEquals(
                "the question waits, held, for a retry",
                listOf(MessageState.Held),
                rows(chat).map { it.state },
            )
        }

    @Test fun aLoadThatFailsForAnotherReasonSaysSoAndOffersRetry() =
        runBlocking {
            fake.failLoad(LlamaBroException.ModelLoadFailed.Reason.Other, "the file is corrupt", times = 100)
            val chat = newChat()
            chat.send("hello", emptyList())
            eventually("limit shown") { chat.limit.value != null }
            assertEquals(LimitAction.Retry, chat.limit.value!!.action)
            assertEquals("Qwen3.5 0.8B didn't start. Try again.", chat.limit.value!!.line)
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

    @Test fun chatWithMissingModelIsReadOnly() =
        runBlocking {
            // a stored chat whose model is not in the library
            val id = chats.createChat(deep.toString())
            val chat = engine.open(id)
            eventually("missing") { chat.model.value is ChatModelState.Missing }
            assertEquals("Qwen3.5 4B", (chat.model.value as ChatModelState.Missing).name)
            chat.send("hello?", emptyList())
            eventually("held") { rows(chat).size == 1 }
            assertEquals(0, fake.chatRequests.size) // nothing is sent to a model that isn't there
        }

    @Test fun newChatUsesTheDefaultModel() =
        runBlocking {
            reply("hi")
            onPhone(swift, deep)
            settings.setLastModel(swift.toString())
            settings.pinDefault(deep.toString())
            val chat = engine.open(0)
            chat.send("hello", emptyList())
            eventually("answered") { rows(chat).size == 2 }
            assertEquals(deep.toString(), chats.modelKey(chat.chatId.value))
        }

    @Test fun sendWhileBusyIsIgnored() =
        runBlocking {
            streamer = { _, _, _, _ ->
                flow {
                    emit(ChatEvent.ContentDelta("x"))
                    delay(60_000)
                }
            }
            val chat = newChat()
            chat.send("one", emptyList())
            eventually("busy") { chat.busy.value }
            // send refuses before it stores anything; the reply row shows the rows have caught up with the first
            assertFalse(chat.send("two", emptyList()))
            eventually("the reply row shows") { rows(chat).any { it.kind == MessageKind.Assistant } }
            assertEquals(1, rows(chat).count { it.kind == MessageKind.User })
            chat.stop()
        }

    @Test fun emptyOrBlankMessageIsNotSent() =
        runBlocking {
            val chat = newChat()
            // send refuses before it makes the chat or stores anything
            assertFalse(chat.send("   ", emptyList()))
            assertEquals(0L, chat.chatId.value)
            assertEquals(0, rows(chat).size)
        }

    @Test fun thinkLevelIsSentAsReasoning() =
        runBlocking {
            reply("ok")
            val chat = newChat()
            chat.send("hi", emptyList())
            eventually("answered") { rows(chat).let { r -> r.size == 2 && r[1].state == MessageState.Done } }
            // Qwen3.5 0.8B thinks (catalog), so Think defaults to Off and the request says thinking is off
            assertEquals(Think.Off, chat.think.value)
            assertTrue(fake.chatRequests.single().contains("enable_thinking"))
        }

    @Test fun reopenedChatRebuildsTheConversation() =
        runBlocking {
            reply("fine")
            onPhone(swift)
            val id = chats.createChat(swift.toString())
            chats.append(
                id,
                com.suhel.llamabro.demo.data
                    .NewMessage(MessageKind.User, "earlier question"),
            )
            chats.append(
                id,
                NewMessage(
                    MessageKind.Assistant,
                    "earlier answer",
                    modelKey = swift.toString(),
                ),
            )
            val chat = engine.open(id)
            chat.send("and now", emptyList())
            eventually("answered") { rows(chat).let { r -> r.size == 4 && r[3].state == MessageState.Done } }
            val body = fake.chatRequests.single()
            assertTrue(
                body,
                body.contains("earlier question") && body.contains("earlier answer") && body.contains("and now"),
            )
        }

    @Test fun instructionsChangeRebuildsTheConversation() =
        runBlocking {
            reply("one")
            reply("two")
            val chat = newChat()
            chat.send("a", emptyList())
            eventually("first") { rows(chat).let { r -> r.size == 2 && r[1].state == MessageState.Done } }
            settings.setInstructions("a pilot", "")
            chat.send("b", emptyList())
            eventually("second") { rows(chat).let { r -> r.size == 4 && r[3].state == MessageState.Done } }
            assertTrue(fake.chatRequests.last().contains("About me: a pilot"))
        }

    @Test fun failedReplyCanBeRetried() =
        runBlocking {
            fake.error(500, "server_error", "boom")
            reply("recovered")
            val chat = newChat()
            chat.send("hi", emptyList())
            eventually("failed") { rows(chat).any { it.state == MessageState.Failed } }
            assertEquals("That didn't work.", rows(chat).first { it.state == MessageState.Failed }.failure)
            chat.regenerate(1)
            eventually("recovered") {
                rows(chat).let { r ->
                    r.lastOrNull()?.text == "recovered" &&
                        r.last().state == MessageState.Done
                }
            }
            assertEquals(2, rows(chat).size)
        }

    @Test fun regenerateWhileStreamingStopsFirst() =
        runBlocking {
            var calls = 0
            streamer =
                { _, _, _, _ ->
                    flow {
                        if (calls++ == 0) {
                            emit(ChatEvent.ContentDelta("slow"))
                            delay(60_000)
                        } else {
                            emit(ChatEvent.ContentDelta("fast"))
                        }
                    }
                }
            val chat = newChat()
            chat.send("go", emptyList())
            eventually("streaming") { chat.streaming.value?.text == "slow" }
            chat.regenerate(1)
            eventually("regenerated") {
                rows(chat).let { r ->
                    r.lastOrNull()?.text == "fast" &&
                        r.last().state == MessageState.Done
                }
            }
            assertEquals(2, rows(chat).size)
        }

    @Test fun switchWhileStreamingStopsFirst() =
        runBlocking {
            streamer = { _, _, _, _ ->
                flow {
                    emit(ChatEvent.ContentDelta("slow"))
                    delay(60_000)
                }
            }
            onPhone(deep)
            val chat = newChat()
            chat.send("go", emptyList())
            eventually("streaming") { chat.streaming.value?.text == "slow" }
            chat.switchModel(deep)
            eventually("switched") { rows(chat).any { it.kind == MessageKind.Switch } }
            assertTrue(rows(chat).any { it.state == MessageState.Stopped })
            eventually("idle") { !chat.busy.value }
        }

    @Test fun deletingAChatWhileStreamingCancelsIt() =
        runBlocking {
            streamer = { _, _, _, _ ->
                flow {
                    emit(ChatEvent.ContentDelta("slow"))
                    delay(60_000)
                }
            }
            val chat = newChat()
            chat.send("go", emptyList())
            eventually("streaming") { chat.streaming.value?.text == "slow" }
            val id = chat.chatId.value
            engine.delete(id).join()
            assertNull(chats.modelKey(id))
            eventually("idle") { !chat.busy.value }
        }

    @Test fun downloadFailureLines() {
        val network =
            DownloadFailure(
                DownloadFailure.Kind.Network,
                retryable = true,
            )
        val auth =
            DownloadFailure(
                DownloadFailure.Kind.Unauthorized,
                retryable = false,
            )
        val bad =
            DownloadFailure(
                DownloadFailure.Kind.IntegrityMismatch,
                retryable = false,
            )
        // what stopped and why, never "paused": a pause is the person's own
        assertEquals(Limit("The connection dropped.", LimitAction.Retry, "Try again"), Limits.forDownload(network))
        assertEquals(
            "Hugging Face is busy right now.",
            Limits.forDownload(DownloadFailure(DownloadFailure.Kind.RateLimited, retryable = true)).line,
        )
        assertEquals(
            "The download stopped.",
            Limits.forDownload(DownloadFailure(DownloadFailure.Kind.NotFound, retryable = true)).line,
        )
        assertEquals(
            Limit("This model needs a Hugging Face token.", LimitAction.AddToken, "Add token"),
            Limits.forDownload(auth),
        )
        assertEquals("Hugging Face wouldn't give us this file.", Limits.forDownload(bad).line)
    }
}
