package com.suhel.llamabro.demo.ui.shell

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.suhel.llamabro.demo.chat.FakeChatDao
import com.suhel.llamabro.demo.data.ChatRepository
import com.suhel.llamabro.demo.data.ChatSummary
import com.suhel.llamabro.demo.data.ImageStore
import com.suhel.llamabro.demo.data.MessageKind
import com.suhel.llamabro.demo.data.NewMessage
import com.suhel.llamabro.demo.data.SettingsRepository
import com.suhel.llamabro.demo.models.FakeScheduler
import com.suhel.llamabro.demo.models.ModelRepository
import com.suhel.llamabro.demo.models.TestHub
import com.suhel.llamabro.demo.ui.keepAlive
import com.suhel.llamabro.demo.ui.shutDown
import com.suhel.llamabro.hub.HfModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale

@OptIn(ExperimentalCoroutinesApi::class)
class ShellViewModelTest {
    @get:Rule val folder = TemporaryFolder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val zone: ZoneId = ZoneOffset.UTC
    private val day = 24L * 3600 * 1000
    private val now = 1_800_000_000_000L // a fixed "now"
    private var clockNow = now

    private val dao = FakeChatDao()
    private val chats = ChatRepository(dao, ImageStore(File("/tmp/none")), { clockNow })
    private val settings by lazy {
        SettingsRepository(PreferenceDataStoreFactory.create(scope = scope) { File(folder.root, "s.preferences_pb") })
    }
    private val swift = "unsloth/Qwen3.5-0.8B-GGUF:Q4_K_M"

    @Before fun main() = Dispatchers.setMain(UnconfinedTestDispatcher())

    private val viewModels = mutableListOf<ShellViewModel>()
    private val collectors = CoroutineScope(Dispatchers.Unconfined)

    private val testHub = TestHub()

    @After fun stop() {
        viewModels.forEach { it.shutDown() }
        collectors.cancel()
        Dispatchers.resetMain()
        scope.cancel()
        testHub.close()
    }

    private fun viewModel() =
        ShellViewModel(
            chats,
            settings,
            ModelRepository(testHub.hub, settings, FakeScheduler(), scope),
            { now },
            zone,
            SavedStateHandle(),
        ).also {
            viewModels += it
            it.chats.keepAlive(collectors)
            it.groups.keepAlive(collectors)
            it.modelCount.keepAlive(collectors)
            it.layout.keepAlive(collectors)
            it.theme.keepAlive(collectors)
            it.startsWithFirstRun.keepAlive(collectors)
        }

    private suspend fun chatAt(title: String, age: Long, last: String = "hi", fromUser: Boolean = true): Long {
        clockNow = now - age
        val id = chats.createChat(swift)
        chats
            .append(
                id,
                NewMessage(
                    if (fromUser) MessageKind.User else MessageKind.Assistant,
                    last,
                    modelKey =
                        swift
                            .takeIf {
                                !fromUser
                            },
                ),
            )
        chats.rename(id, title)
        return id
    }

    private suspend fun <T> awaitValue(read: () -> T, timeout: Long = 5_000, ok: (T) -> Boolean): T =
        withTimeout(timeout) {
            while (!ok(read())) delay(10)
            read()
        }

    private fun viewModelOver(hub: TestHub) =
        ShellViewModel(
            chats,
            settings,
            ModelRepository(hub.hub, settings, FakeScheduler(), scope),
            { now },
            zone,
            SavedStateHandle(),
        ).also {
            viewModels += it
            it.chats.keepAlive(collectors)
            it.groups.keepAlive(collectors)
            it.modelCount.keepAlive(collectors)
            it.layout.keepAlive(collectors)
            it.theme.keepAlive(collectors)
            it.startsWithFirstRun.keepAlive(collectors)
        }

    @Test fun startsOnFirstRunOnlyWhenNothingIsSeenAndNoModelIsOnThePhone() =
        runBlocking<Unit> {
            // one view model per launch, read until it has decided (the library scan is the hub's, off this thread)
            suspend fun launch() =
                viewModelOver(testHub).let { vm -> awaitValue({ vm.startsWithFirstRun.value }) { it != null } }
            assertEquals(true, launch())

            testHub.install(HfModel.parse(swift))
            assertEquals("a model on the phone is enough", false, launch())

            testHub.hub.delete(HfModel.parse(swift))
            settings.markFirstRunSeen()
            assertEquals("seen once is seen for good", false, launch())
        }

    @Test fun chatsAreGroupedByDay() =
        runBlocking<Unit> {
            chatAt("today", 1_000)
            chatAt("this week", 3 * day)
            chatAt("earlier", 20 * day)
            val vm = viewModel()
            val groups = awaitValue({ vm.groups.value }) { it.size == 3 }
            assertEquals(listOf("Today", "This week", "Earlier"), groups.map { it.group.label })
            assertEquals(listOf("today"), groups[0].chats.map { it.title })
            assertEquals(listOf("earlier"), groups[2].chats.map { it.title })
        }

    @Test fun searchFilters() =
        runBlocking<Unit> {
            chatAt("Portfolio bio", 1_000, last = "write my bio")
            chatAt("Trip plan", 2_000, last = "three days in Lisbon")
            val vm = viewModel()
            awaitValue({ vm.chats.value }) { it.size == 2 }
            vm.search("lisbon")
            assertEquals(listOf("Trip plan"), awaitValue({ vm.chats.value }) { it.size == 1 }.map { it.title })
            vm.search("")
            awaitValue<List<ChatSummary>>({ vm.chats.value }) { it.size == 2 }
        }

    @Test fun chatRowPreviewPrefixesTheSender() {
        fun summary(last: String, fromUser: Boolean, model: String = swift) =
            ChatSummary(1, "t", now, model, last, fromUser)
        assertEquals(
            "Qwen3.5 0.8B: Here's a tighter version",
            ChatListFormat.preview(summary("Here's a tighter version", false)),
        )
        assertEquals("You: what about the intro?", ChatListFormat.preview(summary("what about the intro?", true)))
        assertEquals("You: Image", ChatListFormat.preview(summary("Image", true)))
        assertEquals("Q", ChatListFormat.avatarLetter(swift))
    }

    @Test fun aReplyWithNoTextSaysStoppedOnlyWhenItWasStopped() {
        fun summary(stopped: Boolean, empty: Boolean = false) =
            ChatSummary(1, "t", now, swift, "", false, lastStopped = stopped, isEmpty = empty)
        assertEquals("Qwen3.5 0.8B: Stopped", ChatListFormat.preview(summary(stopped = true)))
        // still streaming or failed with no text: no claim; an empty chat has no preview at all
        assertEquals("Qwen3.5 0.8B: ", ChatListFormat.preview(summary(stopped = false)))
        assertEquals("", ChatListFormat.preview(summary(stopped = false, empty = true)))
    }

    @Test fun rowTimeIsTimeTodayWeekdayThisWeekElseDate() {
        val us = Locale.US
        // today: a clock time; this week: a weekday; else: day and month
        assertTrue(ChatListFormat.rowTime(now - 1_000, now, zone, us).contains(":"))
        assertTrue(ChatListFormat.rowTime(now - 3 * day, now, zone, us).matches(Regex("[A-Z][a-z]{2}")))
        assertTrue(ChatListFormat.rowTime(now - 20 * day, now, zone, us).matches(Regex("\\d{1,2} [A-Z][a-z]{2}")))
    }

    @Test fun renameAndDeleteWork() =
        runBlocking<Unit> {
            val id = chatAt("old", 1_000)
            val vm = viewModel()
            awaitValue({ vm.chats.value }) { it.size == 1 }
            vm.rename(id, "  renamed ")
            awaitValue({ vm.chats.value }) { it.firstOrNull()?.title == "renamed" }
            vm.rename(id, "   ") // blank is ignored
            vm.delete(id)
            awaitValue<List<ChatSummary>>({ vm.chats.value }) { it.isEmpty() }
        }

    @Test fun theSearchSurvivesProcessDeath() {
        val saved = SavedStateHandle()
        val models = ModelRepository(testHub.hub, settings, FakeScheduler(), scope)
        val vm = ShellViewModel(chats, settings, models, { now }, zone, saved).also { viewModels += it }
        vm.search("rain")
        val restored = SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) })
        val again = ShellViewModel(chats, settings, models, { now }, zone, restored).also { viewModels += it }
        assertEquals("rain", again.searchText.value)
    }
}
