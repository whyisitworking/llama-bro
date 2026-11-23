package com.suhel.llamabro.demo.ui.chat

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.suhel.llamabro.LlamaBroException
import com.suhel.llamabro.demo.chat.ChatEngine
import com.suhel.llamabro.demo.chat.Dictation
import com.suhel.llamabro.demo.chat.FakeChatDao
import com.suhel.llamabro.demo.chat.LimitAction
import com.suhel.llamabro.demo.chat.ModelHost
import com.suhel.llamabro.demo.chat.SampleSource
import com.suhel.llamabro.demo.chat.VoiceChat
import com.suhel.llamabro.demo.chat.VoiceConversation
import com.suhel.llamabro.demo.chat.VoiceRecorder
import com.suhel.llamabro.demo.data.AudioStore
import com.suhel.llamabro.demo.data.ChatRepository
import com.suhel.llamabro.demo.data.ImageStore
import com.suhel.llamabro.demo.data.MessageKind
import com.suhel.llamabro.demo.data.MessageState
import com.suhel.llamabro.demo.data.SettingsRepository
import com.suhel.llamabro.demo.designsystem.components.ThinkLevel
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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.util.Collections

private const val QUIET_BLOCKS = 4

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelTest {
    @get:Rule val folder = TemporaryFolder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val swift = HfModel.parse("unsloth/Qwen3.5-0.8B-GGUF:Q4_K_M")

    /** The catalog's model that can neither think nor see. */
    private val plain: HfModel = Catalog.suggested.first { !it.thinks && !it.sees }.key

    private val fake = FakeLlamaBro()
    private val testHub = TestHub()
    private val hub get() = testHub.hub
    private val dao = FakeChatDao()
    private val settings by lazy {
        SettingsRepository(
            PreferenceDataStoreFactory.create(scope = scope) { File(folder.root, "settings.preferences_pb") },
        )
    }
    private val audio by lazy { AudioStore(File(folder.root, "audio")) }
    private val chats by lazy {
        ChatRepository(
            dao,
            ImageStore(File(folder.root, "images")),
            System::currentTimeMillis,
            audio,
        )
    }
    private val host by lazy { ModelHost(fake.llamaBro, { hub.installedModel(it) }, scope) }
    private val models by lazy { ModelRepository(hub, settings, FakeScheduler(), scope, host) }
    private val engine by lazy { ChatEngine(chats, host, models, settings, System::currentTimeMillis, scope) }
    private val viewModels = mutableListOf<ChatViewModel>()

    @Before fun main() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After fun stop() {
        viewModels.forEach { it.viewModelScope.cancel() }
        runBlocking { host.release() }
        Dispatchers.resetMain()
        scope.cancel()
        testHub.close()
    }

    private suspend fun until(what: String, condition: () -> Boolean) {
        try {
            withTimeout(10_000) { while (!condition()) delay(10) }
        } catch (e: TimeoutCancellationException) {
            throw AssertionError("timed out waiting for $what")
        }
    }

    private suspend fun viewModel(
        model: HfModel = swift,
        styles: Flow<MessageStyle> = flowOf(MessageStyle.Open),
        saved: SavedStateHandle = SavedStateHandle(),
        chatId: Long = 0,
        recorder: VoiceRecorder? = null,
        voiceMode: ((VoiceChat) -> VoiceConversation)? = null,
        dictating: Int? = null,
    ): ChatViewModel {
        testHub.install(model)
        models.changed()
        settings.setLastModel(model.toString())
        return ChatViewModel(
            engine.open(chatId),
            styles,
            saveImage = { "pic.jpg" },
            discardImages = {},
            savedState = saved,
            background = scope,
            recorder = recorder,
            voiceModeStart = voiceMode,
            dictationStart =
                dictating?.let { blocks ->
                    { model, language ->
                        Dictation(model, steadyVoice(blocks), File(folder.root, "dictation.wav"), scope, language)
                    }
                },
            micHint = settings.settings.map { !it.seenMicHint },
            onMicHintSeen = { settings.setSeenMicHint() },
        ).also(viewModels::add)
    }

    /** A source that hears a steady voice for [blocks] reads of 50 ms, after a moment of quiet, then stops. */
    private fun steadyVoice(blocks: Int): () -> SampleSource =
        {
            object : SampleSource {
                var reads = 0
                override val rate = 16_000

                override fun read(buffer: ShortArray): Int {
                    if (reads++ >= blocks + QUIET_BLOCKS) return -1
                    buffer.fill(if (reads > QUIET_BLOCKS) 4_000 else 0)
                    return buffer.size
                }

                override fun close() = Unit
            }
        }

    /** Voice mode that never loads a model: enough to see it open and close (D100). */
    private fun voiceModeFor(started: MutableList<VoiceChat>): (VoiceChat) -> VoiceConversation =
        { chat ->
            started += chat
            VoiceConversation(
                chat.chatId,
                chat.key,
                chat.name,
                { _, _ -> null },
                settings,
                chats,
                audio,
                microphone = { error("no microphone in this test") },
                speech = { error("no speaker in this test") },
                scope = scope,
                dispatcher = Dispatchers.Default,
            )
        }

    /** What a process restored after its death hands the view model: the values saved, nothing live. */
    private fun restored(saved: SavedStateHandle) = SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) })

    private fun reply(text: String) =
        fake.reply {
            content(text)
            timings(promptTokens = 10, predictedTokens = 20)
        }

    /** A microphone that hears a steady voice for [blocks] reads of 50 ms, then stops, as one taken by a call does. */
    private fun microphone(blocks: Int) =
        VoiceRecorder(
            audio,
            {
                object : SampleSource {
                    var reads = 0
                    override val rate = 16_000

                    override fun read(buffer: ShortArray): Int {
                        if (reads++ >= blocks) return -1
                        buffer.fill(4_000)
                        return buffer.size
                    }

                    override fun close() = Unit
                }
            },
            scope,
        )

    @Test fun theMicShowsOnlyForAModelThatHears() =
        runBlocking {
            settings.setHears(swift.toString(), true)
            val hearing = viewModel(recorder = microphone(1))
            until("the mic") { hearing.composerState.value.showsMic }
            val deaf = viewModel(model = plain, recorder = microphone(1))
            until("the model is known") {
                deaf.hears.value == false &&
                    deaf.handle.name.value
                        .isNotEmpty()
            }
            assertFalse(deaf.composerState.value.canRecord)
        }

    @Test fun voiceModeShowsForAModelThatTalksAndOpensInANewChat() =
        runBlocking {
            val omni = Catalog.suggested.first { it.talks }.key
            settings.setVoices(omni.toString(), listOf("female"))
            val started = mutableListOf<VoiceChat>()
            val talking = viewModel(model = omni, voiceMode = voiceModeFor(started))
            until("the voice-mode button") { talking.composerState.value.showsTalk }
            talking.voiceMode.open()
            until("voice mode opens") { talking.voiceMode.open.value != null }
            assertFalse(
                "a new chat has no earlier messages",
                talking.voiceMode.open.value!!
                    .startsFresh,
            )
            val chat = started.single()
            assertEquals(omni, chat.key)
            assertTrue("the new chat was made", chat.chatId != 0L)
            assertEquals(chat.chatId, talking.handle.chatId.value)
            talking.voiceMode.close()
            assertNull(talking.voiceMode.open.value)

            val quiet = viewModel(voiceMode = voiceModeFor(started))
            until("the model is known") {
                quiet.handle.name.value
                    .isNotEmpty()
            }
            assertFalse(quiet.composerState.value.canTalk)
        }

    @Test fun dictationWritesTheWordsAfterWhatWasTypedAndTheHintGoes() =
        runBlocking {
            fake.supportsAudio = true
            fake.streamsAudio = true
            fake.transcription("call the bank tomorrow.")
            settings.setHears(swift.toString(), true)
            val vm = viewModel(recorder = microphone(1), dictating = 10)
            until("the mic and its hint") { vm.composerState.value.showsMic && vm.composerState.value.micHint }
            vm.onText("Remind me to")
            vm.dictation!!.start()
            until("listening") { vm.composerState.value.dictation != null }
            // the mic leaves while dictating: neither a voice message nor voice mode can start
            assertFalse(vm.composerState.value.showsMic)
            until("the voice was heard") {
                vm.dictation!!
                    .shown.value
                    ?.dictation
                    ?.millis == 500L + QUIET_BLOCKS * 50
            }
            vm.dictation!!.finish()
            until("the words in the draft") { vm.composer.value.text == "Remind me to call the bank tomorrow." }
            until("the bar is back") { vm.composerState.value.dictation == null }
            until("the hint is gone") { !vm.composerState.value.micHint }
        }

    @Test fun discardingADictationLeavesTheDraftAsItWas() =
        runBlocking {
            fake.supportsAudio = true
            fake.streamsAudio = true
            settings.setHears(swift.toString(), true)
            val vm = viewModel(recorder = microphone(1), dictating = 400)
            until("the mic") { vm.composerState.value.canRecord }
            vm.onText("Hello")
            vm.dictation!!.start()
            until("listening") {
                vm.composerState.value.dictation
                    ?.preparing == false
            }
            vm.dictation!!.discard()
            until("the bar is back") { vm.composerState.value.dictation == null }
            assertEquals("Hello", vm.composer.value.text)
        }

    @Test fun aRecordingIsSentAsAVoiceMessageAndLeavesTheBar() =
        runBlocking {
            fake.supportsAudio = true
            settings.setHears(swift.toString(), true)
            reply("I hear you")
            val vm = viewModel(recorder = microphone(10))
            until("the mic") { vm.composerState.value.showsMic }
            val voice = vm.voice!!
            voice.start()
            // the recorder's take is the bar's source: the bar's own state is drawn from it each frame
            until("the recording stopped and waits") { voice.take.value?.live == false }
            assertEquals("0:01", ChatViewModel.recordingState(voice.take.value!!).elapsed)
            voice.send()
            until("the voice message") { vm.rows.value.any { it is UserItem && it.voice != null } }
            until("the take is let go") { voice.take.value == null }
            val sent = (vm.rows.value.first { it is UserItem } as UserItem).voice!!
            assertEquals("0:01", sent.length)
            assertTrue(sent.file.exists())
            until("the reply") { vm.rows.value.any { it is ReplyItem && it.text == "I hear you" } }
        }

    @Test fun discardingARecordingDeletesItAndARefusedMicSaysWhereToAllowIt() =
        runBlocking {
            settings.setHears(swift.toString(), true)
            val vm = viewModel(recorder = microphone(2))
            until("the mic") { vm.composerState.value.showsMic }
            vm.voice!!.refused()
            until("the line") { vm.limit.value?.action == LimitAction.OpenAppSettings }
            vm.voice!!.start()
            until("the line goes once recording starts") { vm.limit.value == null }
            until("the recording stopped") {
                vm.voice!!
                    .take.value
                    ?.live == false
            }
            vm.voice!!.discard()
            until("the take is let go") { vm.voice!!.take.value == null }
            val folder = audio.file("x").parentFile!!
            until("the recording is deleted") { folder.listFiles().orEmpty().isEmpty() }
        }

    @Test fun sendClearsTheComposer() =
        runBlocking {
            reply("hello")
            val vm = viewModel()
            vm.onText("Hi there")
            assertEquals("Hi there", vm.composerState.value.text)
            vm.send()
            until("the bar is empty") { vm.composerState.value.text == "" }
            assertEquals(emptyList<String>(), vm.composer.value.attachments)
            until("the reply") { vm.rows.value.any { it is ReplyItem && it.text == "hello" } }
            assertEquals("Hi there", (vm.rows.value.first() as UserItem).text)
        }

    @Test fun aSendThatFailsKeepsTheDraftAndSaysSoInsteadOfCrashing() =
        runBlocking {
            // the chat's rows go behind the handle's back: the database refuses the message (a foreign key)
            val id = chats.createChat(swift.toString())
            val vm = viewModel(chatId = id)
            // the handle looks its chat's model up as it opens: deleted first, it finds none and the send just stops
            until("the chat's model") { vm.handle.current.value == swift }
            chats.delete(id)
            vm.onText("Hi there")
            vm.send()
            until("the line") { vm.problem.value != null }
            assertEquals("That message couldn't be sent.", vm.problem.value)
            assertEquals("Hi there", vm.composer.value.text)
        }

    @Test fun typingReachesTheBarInTheSameCall() =
        runBlocking {
            val vm = viewModel()
            for (text in listOf("H", "He", "Hel")) {
                vm.onText(text)
                assertEquals(text, vm.composerState.value.text) // no coroutine between the keystroke and the field
            }
            assertTrue(vm.composerState.value.canAct)
        }

    @Test fun theDraftSurvivesProcessDeathThroughTheSavedState() =
        runBlocking {
            val saved = SavedStateHandle()
            val vm = viewModel(saved = saved)
            vm.onText("half a thought")
            vm.attachWith { "pic.jpg" }
            until("attached") { vm.composer.value.attachments == listOf("pic.jpg") }
            val again = viewModel(saved = restored(saved))
            assertEquals(ComposerDraft("half a thought", listOf("pic.jpg")), again.composer.value)
            assertTrue(again.composerState.value.hasAttachment)
        }

    @Test fun aNewChatSavesItsIdSoTheRestoredScreenReopensIt() =
        runBlocking {
            reply("hello")
            val saved = SavedStateHandle(mapOf(ChatViewModel.CHAT_ID_KEY to 0L)) // the route's argument: a new chat
            val vm = viewModel(saved = saved)
            assertEquals(0L, ChatViewModel.chatIdToOpen(saved, 0))
            vm.onText("Hi there")
            vm.send()
            until("the chat exists") { vm.handle.chatId.value != 0L }
            val id = vm.handle.chatId.value
            until("its id is saved") { saved.get<Long>(ChatViewModel.CHAT_ID_KEY) == id }
            val after = restored(saved)
            assertEquals(id, ChatViewModel.chatIdToOpen(after, 0))
            val reopened = viewModel(saved = after, chatId = ChatViewModel.chatIdToOpen(after, 0))
            until("the restored screen shows the chat") {
                reopened.rows.value.any { it is UserItem && it.text == "Hi there" }
            }
        }

    @Test fun sendingNothingDoesNothing() =
        runBlocking {
            val vm = viewModel()
            vm.onText("   ")
            vm.send()
            assertEquals("   ", vm.composer.value.text)
            assertTrue(vm.rows.value.isEmpty())
        }

    @Test fun thinkBulbHiddenWhenTheModelCantThink() =
        runBlocking {
            val vm = viewModel(plain)
            until("the model is known") {
                vm.header.value.name
                    .isNotEmpty()
            }
            assertNull(vm.composerState.value.think)
        }

    @Test fun thinkChipShowsForAModelThatThinks() =
        runBlocking {
            val vm = viewModel(swift)
            until("the chip") { vm.composerState.value.think == ThinkLevel.Off }
            vm.setThink(ThinkLevel.Balanced)
            until("the level") { vm.composerState.value.think == ThinkLevel.Balanced }
        }

    @Test fun editPutsTextInComposer() =
        runBlocking {
            reply("first answer")
            val vm = viewModel()
            vm.onText("What is rain?")
            vm.send()
            until("answered") {
                vm.rows.value.any { it is ReplyItem && !it.stopped } &&
                    vm.handle.messages.value
                        .all { it.state == MessageState.Done }
            }
            val user =
                vm.handle.messages.value
                    .first { it.kind == MessageKind.User }
            vm.startEdit(user.position)
            assertEquals("What is rain?", vm.composer.value.text)
            assertEquals(user.position, vm.composer.value.editing)
            reply("second answer")
            vm.onText("What is snow?")
            vm.send()
            until("replaced") {
                vm.rows.value
                    .filterIsInstance<UserItem>()
                    .singleOrNull()
                    ?.text == "What is snow?"
            }
            assertNull(vm.composer.value.editing)
            assertEquals("", vm.composer.value.text)
        }

    @Test fun cancelEditEmptiesTheBar() =
        runBlocking {
            reply("ok")
            val vm = viewModel()
            vm.onText("one")
            vm.send()
            until("answered") {
                vm.handle.messages.value.size == 2 &&
                    vm.handle.messages.value
                        .all { it.state == MessageState.Done }
            }
            vm.startEdit(
                vm.handle.messages.value
                    .first()
                    .position,
            )
            vm.cancelEdit()
            assertEquals(ComposerDraftEmpty, vm.composer.value)
        }

    @Test fun shareCardTextIsCutAt600() =
        runBlocking {
            val long = "word ".repeat(200)
            reply(long)
            val vm = viewModel()
            vm.onText("write a lot")
            vm.send()
            until("answered") {
                vm.handle.messages.value.size == 2 &&
                    vm.handle.messages.value
                        .all { it.state == MessageState.Done }
            }
            val shared =
                vm.shareText(
                    vm.handle.messages.value
                        .last()
                        .position,
                )!!
            assertTrue(shared.endsWith("…"))
            assertTrue(shared.length <= 601)
            assertEquals(
                null,
                vm.shareText(
                    vm.handle.messages.value
                        .first()
                        .position,
                ),
            ) // only replies are shared
        }

    @Test fun attachingKeepsAtMostFourAndRemovingForgets() =
        runBlocking {
            val vm = viewModel()
            var n = 0
            repeat(6) { vm.attachWith { "pic${n++}.jpg" } }
            until("four attached") { vm.composer.value.attachments.size == 4 }
            delay(100)
            assertEquals(listOf("pic0.jpg", "pic1.jpg", "pic2.jpg", "pic3.jpg"), vm.composer.value.attachments)
            vm.removeAttachment("pic1.jpg")
            assertEquals(listOf("pic0.jpg", "pic2.jpg", "pic3.jpg"), vm.composer.value.attachments)
        }

    @Test fun theMemoryNoteCanBeClosedButALimitThatHoldsTheChatCannot() =
        runBlocking {
            // the SDK refuses the budgeted load and, warned, loads at the smallest context: the note shows
            fake.failLoad(LlamaBroException.ModelLoadFailed.Reason.InsufficientMemory, "not enough memory", times = 1)
            reply("Hi!")
            val vm = viewModel()
            until("the model is known") {
                vm.header.value.name
                    .isNotEmpty()
            }
            vm.onText("hello")
            vm.send()
            until("the note") { vm.limit.value?.dismissible == true }
            vm.notes.close(vm.limit.value)
            until("closed") { vm.limit.value == null }

            // a line the chat waits on has no close button, and closing does nothing to it
            fake.failLoad(LlamaBroException.ModelLoadFailed.Reason.Other, "the file is corrupt", times = 100)
            engine.releaseModelIfIdle()
            vm.onText("again")
            vm.send()
            until("the retry line") { vm.limit.value?.action == LimitAction.Retry }
            assertEquals(false, vm.limit.value!!.dismissible)
            vm.notes.close(vm.limit.value)
            assertEquals(LimitAction.Retry, vm.limit.value?.action)
        }

    @Test fun picturesSentToAModelThatCantSeeKeepTheDraftAndSayWhy() =
        runBlocking {
            val vm = viewModel(plain)
            until("the model is known") {
                vm.header.value.name
                    .isNotEmpty()
            }
            vm.onText("what is this?")
            vm.attachWith { "pic.jpg" }
            until("attached") { vm.composer.value.attachments.size == 1 }
            vm.send()
            until("the limit line") {
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
            until("the line") { vm.problem.value != null }
            assertEquals("That photo couldn't be opened.", vm.problem.value)
            assertTrue(
                vm.composer.value.attachments
                    .isEmpty(),
            )
            vm.attachWith { "ok.jpg" }
            until("attached") { vm.composer.value.attachments == listOf("ok.jpg") }
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
            until("four attached and one deleted") { vm.composer.value.attachments.size == 4 && deleted.size == 1 }
        }

    @Test fun whileEditingNoPhotoCanBeAdded() =
        runBlocking {
            reply("ok")
            val vm = viewModel()
            vm.onText("one")
            vm.send()
            until("answered") {
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
            vm.attachWith { "late.jpg" }
            delay(100)
            assertTrue(
                vm.composer.value.attachments
                    .isEmpty(),
            )
        }

    @Test fun retryReplacesAFailedReplyInsteadOfStackingAnother() =
        runBlocking {
            fake.error(500, "server_error", "boom")
            reply("fixed it")
            val vm = viewModel()
            vm.onText("try this")
            vm.send()
            until("failed") {
                vm.handle.messages.value
                    .lastOrNull()
                    ?.state == MessageState.Failed
            }
            vm.retry()
            until("replaced") {
                vm.rows.value
                    .filterIsInstance<ReplyItem>()
                    .singleOrNull()
                    ?.text == "fixed it"
            }
            assertEquals(
                1,
                vm.rows.value
                    .filterIsInstance<UserItem>()
                    .size,
            )
            assertNull(vm.limit.value)
        }

    @Test fun theLayoutCanChangeWhileAChatIsOpen() =
        runBlocking {
            reply("hello")
            // the layout setting, as the settings hold it
            val styles = MutableStateFlow(MessageStyle.Open)
            val vm = viewModel(styles = styles)
            vm.onText("hi")
            vm.send()
            until("answered") { vm.rows.value.any { it is ReplyItem } }
            assertTrue(vm.rows.value.none { it is DayItem })
            styles.value = MessageStyle.Bubbles
            until("day label and times") {
                vm.rows.value.any { it is DayItem } &&
                    (vm.rows.value.first { it is UserItem } as UserItem).time != null
            }
        }

    @Test fun shareNamesTheModelThatWroteTheReply() =
        runBlocking {
            reply("a reply")
            val vm = viewModel()
            vm.onText("hi")
            vm.send()
            until("answered") {
                vm.handle.messages.value.size == 2 &&
                    vm.handle.messages.value
                        .all { it.state == MessageState.Done }
            }
            assertEquals(
                "Qwen3.5 0.8B",
                vm.shareModelName(
                    vm.handle.messages.value
                        .last()
                        .position,
                ),
            )
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
                    savedState = SavedStateHandle(),
                    background = scope,
                )
            vm.attachWith { "draft.jpg" }
            until("attached") { vm.composer.value.attachments == listOf("draft.jpg") }
            val onCleared = ChatViewModel::class.java.getDeclaredMethod("onCleared").apply { isAccessible = true }
            onCleared.invoke(vm)
            until("deleted") { deleted == listOf("draft.jpg") }
        }

    private companion object {
        val ComposerDraftEmpty = ComposerDraft()
    }
}
