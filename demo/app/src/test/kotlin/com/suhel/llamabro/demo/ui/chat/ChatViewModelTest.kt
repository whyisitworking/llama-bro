package com.suhel.llamabro.demo.ui.chat

import androidx.lifecycle.SavedStateHandle
import com.suhel.llamabro.LlamaBroException
import com.suhel.llamabro.demo.chat.LimitAction
import com.suhel.llamabro.demo.data.MessageKind
import com.suhel.llamabro.demo.data.MessageState
import com.suhel.llamabro.demo.data.ModelSenses
import com.suhel.llamabro.demo.designsystem.components.ThinkLevel
import com.suhel.llamabro.demo.eventually
import com.suhel.llamabro.hub.HfModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/** The chat screen's view model: the bar, sending, editing, the rows, sharing and what outlives the process. */
class ChatViewModelTest : ChatViewModelFixture() {
    @Test fun sendClearsTheComposer() =
        runBlocking {
            reply("hello")
            val vm = viewModel()
            vm.onText("Hi there")
            assertEquals("Hi there", vm.composerState.value.text)
            vm.send()
            eventually("the bar is empty") { vm.composerState.value.text == "" }
            assertEquals(emptyList<String>(), vm.composer.value.attachments)
            eventually("the reply") { vm.rows.value.any { it is ReplyItem && it.text == "hello" } }
            assertEquals("Hi there", (vm.rows.value.first() as UserItem).text)
        }

    @Test fun aSendThatFailsKeepsTheDraftAndSaysSoInsteadOfCrashing() =
        runBlocking {
            // the chat's rows go behind the handle's back: the database refuses the message (a foreign key)
            val id = chats.createChat(swift.toString())
            val vm = viewModel(chatId = id)
            // the handle looks its chat's model up as it opens: deleted first, it finds none and the send just stops
            eventually("the chat's model") { vm.handle.current.value == swift }
            chats.delete(id)
            vm.onText("Hi there")
            vm.send()
            eventually("the line") { vm.problem.value != null }
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
            eventually("attached") { vm.composer.value.attachments == listOf("pic.jpg") }
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
            eventually("the chat exists") { vm.handle.chatId.value != 0L }
            val id = vm.handle.chatId.value
            eventually("its id is saved") { saved.get<Long>(ChatViewModel.CHAT_ID_KEY) == id }
            val after = restored(saved)
            assertEquals(id, ChatViewModel.chatIdToOpen(after, 0))
            val reopened = viewModel(saved = after, chatId = ChatViewModel.chatIdToOpen(after, 0))
            eventually("the restored screen shows the chat") {
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
            eventually("the model is known") {
                vm.header.value.name
                    .isNotEmpty()
            }
            assertNull(vm.composerState.value.think)
        }

    @Test fun thinkChipShowsForAModelThatThinks() =
        runBlocking {
            val vm = viewModel(swift)
            eventually("the chip") { vm.composerState.value.think == ThinkLevel.Off }
            vm.setThink(ThinkLevel.Balanced)
            eventually("the level") { vm.composerState.value.think == ThinkLevel.Balanced }
        }

    @Test fun editPutsTextInComposer() =
        runBlocking {
            reply("first answer")
            val vm = viewModel()
            vm.onText("What is rain?")
            vm.send()
            eventually("answered") {
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
            eventually("replaced") {
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
            eventually("answered") {
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

    @Test fun theMemoryNoteCanBeClosedButALimitThatHoldsTheChatCannot() =
        runBlocking {
            // the SDK refuses the budgeted load and, warned, loads at the smallest context: the note shows
            fake.failLoad(LlamaBroException.ModelLoadFailed.Reason.InsufficientMemory, "not enough memory", times = 1)
            reply("Hi!")
            val vm = viewModel()
            eventually("the model is known") {
                vm.header.value.name
                    .isNotEmpty()
            }
            vm.onText("hello")
            vm.send()
            eventually("the note") { vm.limit.value?.dismissible == true }
            vm.notes.close(vm.limit.value)
            eventually("closed") { vm.limit.value == null }

            // a line the chat waits on has no close button, and closing does nothing to it
            fake.failLoad(LlamaBroException.ModelLoadFailed.Reason.Other, "the file is corrupt", times = 100)
            engine.releaseModelIfIdle()
            vm.onText("again")
            vm.send()
            eventually("the retry line") { vm.limit.value?.action == LimitAction.Retry }
            assertEquals(false, vm.limit.value!!.dismissible)
            vm.notes.close(vm.limit.value)
            assertEquals(LimitAction.Retry, vm.limit.value?.action)
        }

    @Test fun retryReplacesAFailedReplyInsteadOfStackingAnother() =
        runBlocking {
            fake.error(500, "server_error", "boom")
            reply("fixed it")
            val vm = viewModel()
            vm.onText("try this")
            vm.send()
            eventually("failed") {
                vm.handle.messages.value
                    .lastOrNull()
                    ?.state == MessageState.Failed
            }
            vm.retry()
            eventually("replaced") {
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
            eventually("answered") { vm.rows.value.any { it is ReplyItem } }
            assertTrue(vm.rows.value.none { it is DayItem })
            styles.value = MessageStyle.Bubbles
            eventually("day label and times") {
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
            eventually("answered") {
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

    @Test fun theThinkChipShowsForANewChatOnASearchModelBeforeAnySend() =
        runBlocking {
            readSenses = { key -> headerThinks.takeIf { key == searchThinker } }
            val vm = viewModel(searchThinker)
            // the header said so: the chip shows, and nothing was loaded to find out (D155)
            eventually("the chip") { vm.composerState.value.think == ThinkLevel.Off }
            assertEquals(0, fake.openModels)
            assertTrue(fake.chatRequests.isEmpty())
        }

    @Test fun aLevelChosenBeforeTheLoadSurvivesIt() =
        runBlocking {
            reply("hello")
            val vm = viewModel(swift)
            eventually("the chip") { vm.composerState.value.think == ThinkLevel.Off }
            vm.setThink(ThinkLevel.Balanced)
            eventually("the level") { vm.composerState.value.think == ThinkLevel.Balanced }
            vm.onText("Hi there")
            vm.send()
            eventually("the request") { fake.chatRequests.isNotEmpty() }
            assertTrue(fake.chatRequests.single(), "\"reasoning_budget_tokens\":2048" in fake.chatRequests.single())
            assertEquals(ThinkLevel.Balanced, vm.composerState.value.think)
        }

    @Test fun aModelTheLoadSaysCantThinkLosesTheChip() =
        runBlocking {
            reply("hello")
            readSenses = { key -> headerThinks.takeIf { key == searchThinker } }
            val vm = viewModel(searchThinker)
            eventually("the chip") { vm.composerState.value.think == ThinkLevel.Off }
            vm.setThink(ThinkLevel.Deep)
            vm.onText("Hi there")
            vm.send()
            // the fake's template has no sign of thinking: the load is the final word
            eventually("the reply") { vm.rows.value.any { it is ReplyItem && it.text == "hello" } }
            eventually("the chip goes") { vm.composerState.value.think == null }
            val request = fake.chatRequests.single()
            assertFalse(request, "enable_thinking" in request || "reasoning_budget_tokens" in request)
        }

    @Test fun aHeaderReadThatThrowsLeavesTheChatAsTheCatalogSaysIt() =
        runBlocking {
            val reads = AtomicInteger()
            readSenses = {
                reads.incrementAndGet()
                throw IllegalArgumentException("not a GGUF header")
            }
            reply("hello")
            val vm = viewModel(swift)
            eventually("the header was read") { reads.get() > 0 }
            // the catalog's word stands, and nothing was stored for the corrupt header
            eventually("the chip") { vm.composerState.value.think == ThinkLevel.Off }
            assertEquals(emptyMap<String, ModelSenses>(), settings.modelSenses.first())
            vm.onText("Hi there")
            vm.send()
            eventually("the reply") { vm.rows.value.any { it is ReplyItem && it.text == "hello" } }
        }

    private companion object {
        val ComposerDraftEmpty = ComposerDraft()
        val searchThinker = HfModel.parse("someone/Thinker-GGUF:Q4_K_M")
        val headerThinks = ModelSenses(thinks = true, sees = false, hears = false)
    }
}
