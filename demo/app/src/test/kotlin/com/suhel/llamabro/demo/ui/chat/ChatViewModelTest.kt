package com.suhel.llamabro.demo.ui.chat

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.viewModelScope
import com.suhel.llamabro.demo.chat.ChatEngine
import com.suhel.llamabro.demo.chat.FakeChatDao
import com.suhel.llamabro.demo.chat.ModelHost
import com.suhel.llamabro.demo.data.ChatRepository
import com.suhel.llamabro.demo.data.ImageStore
import com.suhel.llamabro.demo.data.MessageKind
import com.suhel.llamabro.demo.data.MessageState
import com.suhel.llamabro.demo.data.SettingsRepository
import com.suhel.llamabro.demo.designsystem.components.ThinkLevel
import com.suhel.llamabro.demo.models.CachedEntry
import com.suhel.llamabro.demo.models.Catalog
import com.suhel.llamabro.demo.models.FakeHubGateway
import com.suhel.llamabro.demo.models.FakeScheduler
import com.suhel.llamabro.demo.models.ModelRepository
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
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.util.Collections

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelTest {
    @get:Rule val folder = TemporaryFolder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val swift = HfModel.parse("unsloth/Qwen3.5-0.8B-GGUF:Q4_K_M")

    /** The catalog's model that can neither think nor see. */
    private val plain: HfModel = Catalog.suggested.first { !it.thinks && !it.sees }.key

    private val fake = FakeLlamaBro()
    private val hub = FakeHubGateway()
    private val dao = FakeChatDao()
    private val settings by lazy {
        SettingsRepository(
            PreferenceDataStoreFactory.create(scope = scope) { File(folder.root, "settings.preferences_pb") },
        )
    }
    private val chats by lazy {
        ChatRepository(
            dao,
            ImageStore(File(folder.root, "images")),
            System::currentTimeMillis,
        )
    }
    private val host by lazy { ModelHost(fake.llamaBro, hub, scope) }
    private val models by lazy { ModelRepository(hub, settings, FakeScheduler(), scope, host) }
    private val engine by lazy { ChatEngine(chats, host, models, settings, System::currentTimeMillis, scope) }
    private val viewModels = mutableListOf<ChatViewModel>()

    @Before fun main() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After fun stop() {
        viewModels.forEach { it.viewModelScope.cancel() }
        runBlocking { host.release() }
        Dispatchers.resetMain()
        scope.cancel()
    }

    private suspend fun until(what: String, condition: () -> Boolean) {
        try {
            withTimeout(10_000) { while (!condition()) delay(10) }
        } catch (e: TimeoutCancellationException) {
            throw AssertionError("timed out waiting for $what")
        }
    }

    private suspend fun viewModel(model: HfModel = swift, style: MessageStyle = MessageStyle.Open): ChatViewModel {
        hub.cachedEntries += CachedEntry(model, 1_000)
        models.changed()
        settings.setLastModel(model.toString())
        return ChatViewModel(engine.open(0), style, saveImage = { "pic.jpg" }, discardImages = {}).also(viewModels::add)
    }

    private fun reply(text: String) =
        fake.reply {
            content(text)
            timings(promptTokens = 10, predictedTokens = 20)
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
            hub.cachedEntries += CachedEntry(swift, 1_000)
            models.changed()
            settings.setLastModel(swift.toString())
            val vm = ChatViewModel(engine.open(0), MessageStyle.Open, { "x" }, { deleted += it }).also(viewModels::add)
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
            val vm = viewModel(style = MessageStyle.Open)
            vm.onText("hi")
            vm.send()
            until("answered") { vm.rows.value.any { it is ReplyItem } }
            assertTrue(vm.rows.value.none { it is DayItem })
            vm.setStyle(MessageStyle.Bubbles)
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
            hub.cachedEntries += CachedEntry(swift, 1_000)
            models.changed()
            settings.setLastModel(swift.toString())
            val vm = ChatViewModel(engine.open(0), MessageStyle.Open, { "x" }, { deleted += it })
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
