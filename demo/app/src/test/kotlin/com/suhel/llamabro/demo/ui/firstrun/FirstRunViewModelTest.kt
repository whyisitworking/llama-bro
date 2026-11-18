package com.suhel.llamabro.demo.ui.firstrun

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.viewModelScope
import com.suhel.llamabro.DeviceKind
import com.suhel.llamabro.demo.chat.FakeChatDao
import com.suhel.llamabro.demo.data.ChatRepository
import com.suhel.llamabro.demo.data.ImageStore
import com.suhel.llamabro.demo.data.SettingsRepository
import com.suhel.llamabro.demo.models.Catalog
import com.suhel.llamabro.demo.models.DeviceFacts
import com.suhel.llamabro.demo.models.FakeScheduler
import com.suhel.llamabro.demo.models.FitLabel
import com.suhel.llamabro.demo.models.Fits
import com.suhel.llamabro.demo.models.ModelRepository
import com.suhel.llamabro.demo.models.TestHub
import com.suhel.llamabro.demo.ui.keepAlive
import com.suhel.llamabro.demo.ui.shutDown
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneOffset

@OptIn(ExperimentalCoroutinesApi::class)
class FirstRunViewModelTest {
    @get:Rule val folder = TemporaryFolder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val testHub = TestHub()
    private val dao = FakeChatDao()
    private val chats = ChatRepository(dao, ImageStore(File("/tmp/none")), { 1_000L })
    private val settings by lazy {
        SettingsRepository(PreferenceDataStoreFactory.create(scope = scope) { File(folder.root, "s.preferences_pb") })
    }
    private val scheduler = FakeScheduler()
    private val models by lazy {
        ModelRepository(
            testHub.hub,
            settings,
            scheduler,
            CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        )
    }
    private val phone = DeviceFacts(budgetBytes = 8L shl 30, chip = DeviceKind.Npu)
    private val viewModels = mutableListOf<FirstRunViewModel>()
    private val collectors = CoroutineScope(Dispatchers.Unconfined)

    @Before fun main() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After fun stop() {
        viewModels.forEach { it.shutDown() }
        Dispatchers.resetMain()
        collectors.cancel()
        scope.cancel()
        testHub.close()
    }

    private fun viewModel(hour: Int = 9) =
        FirstRunViewModel(
            facts = { phone },
            chats = chats,
            settings = settings,
            models = models,
            clock = {
                LocalDateTime
                    .of(2026, 11, 10, hour, 30)
                    .toInstant(ZoneOffset.UTC)
                    .toEpochMilli()
            },
            zone = ZoneOffset.UTC,
        ).also(viewModels::add)

    @Test fun greetingFollowsTheClock() {
        assertEquals("Hello", viewModel(hour = 4).greeting)
        assertEquals("Good morning", viewModel(hour = 5).greeting)
        assertEquals("Good morning", viewModel(hour = 11).greeting)
        assertEquals("Good afternoon", viewModel(hour = 12).greeting)
        assertEquals("Good afternoon", viewModel(hour = 16).greeting)
        assertEquals("Good evening", viewModel(hour = 17).greeting)
        assertEquals("Good evening", viewModel(hour = 21).greeting)
        assertEquals("Hello", viewModel(hour = 22).greeting)
        assertEquals("Hello", viewModel(hour = 0).greeting)
    }

    @Test fun picksAreTintedFromTheirShippedSizes() {
        // the view model has no network seam: a pick's label comes from the sizes the app ships with (D101)
        val vm = viewModel()
        val picks = vm.picks.keepAlive(collectors).value!!
        assertEquals(listOf("Qwen3.5 0.8B", "Gemma 3 4B", "Qwen3.5 4B"), picks.map { it.entry.realName })
        assertTrue(picks.all { it.fit == Fits.quick(it.entry, phone) })
        assertEquals(FitLabel.RunsGreat, picks.first { it.entry.realName == "Qwen3.5 0.8B" }.fit.label)
        assertEquals(listOf("0.7 GB", "3.3 GB", "3.4 GB"), picks.map { it.sizeText })
    }

    @Test fun picksAreTightOnASmallPhone() {
        val small =
            FirstRunViewModel(
                facts = { DeviceFacts(budgetBytes = 3L shl 30, chip = DeviceKind.Cpu) },
                chats = chats,
                settings = settings,
                models = models,
            ).also(viewModels::add)
        val labels =
            small.picks
                .keepAlive(collectors)
                .value!!
                .associate { it.entry.realName to it.fit.label }
        assertEquals(FitLabel.TooBig, labels["Gemma 3 4B"])
        assertEquals(FitLabel.TooBig, labels["Qwen3.5 4B"])
    }

    @Test fun theSheetShowsTheCardsFitAndTheModelWithItsEncoder() {
        val swift = Catalog.picks.first()
        val vm = viewModel()
        vm.sheet.keepAlive(collectors)
        vm.open(swift)
        val sheet = vm.sheet.value!!
        assertEquals("the card's fit, from the shipped sizes", Fits.quick(swift, phone), sheet.fit)
        assertEquals("Uses about 0.7 GB", sheet.memoryText) // 0.53 GB of weights and a 0.21 GB encoder
        assertEquals("0.7 GB · about 2 min", sheet.downloadText)
        vm.close()
        assertNull(vm.sheet.value)
    }

    @Test fun getStartsTheDownloadMakesTheChatAndMarksFirstRunSeen() =
        runBlocking {
            val deep = Catalog.picks.last()
            val vm = viewModel()
            vm.get(deep)
            val id = kotlinx.coroutines.withTimeout(5_000) { vm.chatOpened.first() }
            scheduler.awaitEnqueued(deep.key) // the repository starts it off this thread
            assertEquals(deep.key.toString(), chats.modelKey(id))
            val saved = settings.settings.first()
            assertTrue(saved.seenFirstRun)
            assertEquals(deep.key.toString(), saved.lastModel)
            assertEquals(
                listOf(deep.key),
                kotlinx.coroutines.withTimeout(5_000) { models.downloading.first { it.isNotEmpty() } },
            )
        }
}
