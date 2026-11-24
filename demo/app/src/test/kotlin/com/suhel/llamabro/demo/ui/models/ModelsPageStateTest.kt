package com.suhel.llamabro.demo.ui.models

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.SavedStateHandle
import com.suhel.llamabro.DeviceKind
import com.suhel.llamabro.demo.data.SettingsRepository
import com.suhel.llamabro.demo.models.Catalog
import com.suhel.llamabro.demo.models.DeviceFacts
import com.suhel.llamabro.demo.models.FakeScheduler
import com.suhel.llamabro.demo.models.LibraryModel
import com.suhel.llamabro.demo.models.ModelLibrary
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.ZoneOffset
import kotlin.coroutines.EmptyCoroutineContext

/**
 * First run as the Models page's empty state (docs/design/models-page.md §2, §7): the first Get on a fresh install
 * makes a chat on that model, marks first run seen and opens the chat; later Gets stay; searching keeps first run.
 * Back in search mode: the query and the focus go, and the page browses again. A download that has landed while its
 * pending key is still listed is one row, on the phone; the intro is known from the first frame.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ModelsPageStateTest {
    @get:Rule val folder = TemporaryFolder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val testHub = TestHub()
    private val cache = FakeHubSearch()
    private val viewModels = mutableListOf<ModelsViewModel>()
    private val settings by lazy {
        SettingsRepository(PreferenceDataStoreFactory.create(scope = scope) { File(folder.root, "s.preferences_pb") })
    }
    private val models by lazy {
        ModelRepository(
            testHub.hub,
            settings,
            FakeScheduler(),
            CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        )
    }
    private val firstRun = MutableStateFlow(false)
    private val chatsMade = mutableListOf<HfModel>()
    private val swift = Catalog.suggested[0].key
    private val deep = Catalog.suggested[2].key

    private val main = StandardTestDispatcher()

    @Before fun setUpMain() = Dispatchers.setMain(main)

    @After fun stop() {
        shutDown(main, viewModels)
        scope.cancel()
        testHub.close()
    }

    private fun TestScope.viewModel(initialIntro: Boolean? = null, library: ModelLibrary = models): ModelsViewModel =
        ModelsViewModel(
            hub = cache,
            models = library,
            facts = { DeviceFacts(budgetBytes = 8L shl 30, chip = DeviceKind.Gpu) },
            freeBytes = { 120_000_000_000L },
            firstRunSeen = firstRun,
            initialIntro = initialIntro,
            markFirstRunSeen = { firstRun.value = true },
            startChat = { key ->
                chatsMade += key
                CHAT_ID
            },
            clock = { 0L },
            zone = ZoneOffset.UTC,
            io = EmptyCoroutineContext,
            savedState = SavedStateHandle(),
        ).also {
            viewModels += it
            it.browse.keepAlive(backgroundScope)
            it.search.keepAlive(backgroundScope)
            it.searching.keepAlive(backgroundScope)
            it.fetching.keepAlive(backgroundScope)
        }

    /** The repository's threads are real: a first read is awaited in real time while the test dispatcher runs. */
    private suspend fun <T> Flow<T>.arrives(predicate: (T) -> Boolean): T =
        withContext(Dispatchers.Default) { withTimeout(5_000) { first(predicate) } }

    @Test fun theFirstGetOnAFreshInstallOpensAChatOnThatModelAndLaterGetsStay() =
        runTest {
            val vm = viewModel()
            val opened = mutableListOf<Long>()
            backgroundScope.launch { vm.chatOpened.collect { opened += it } }
            assertTrue("the intro shows on a fresh install", vm.browse.arrives { it != null }!!.intro)
            vm.started(deep)
            runCurrent()
            assertEquals("the chat is made on the model that was got", listOf(deep), chatsMade)
            assertEquals("and opened", listOf(CHAT_ID), opened)
            assertTrue("first run is seen", firstRun.value)
            assertFalse("the intro goes", vm.browse.arrives { it != null && !it.intro }!!.intro)
            vm.started(swift)
            runCurrent()
            assertEquals("a later Get stays on the page", listOf(deep), chatsMade)
            assertEquals(listOf(CHAT_ID), opened)
        }

    @Test fun twoGetsAtOnceOnAFreshInstallOpenOneChat() =
        runTest {
            val vm = viewModel()
            vm.browse.arrives { it != null }
            vm.started(deep)
            vm.started(swift)
            runCurrent()
            assertEquals(listOf(deep), chatsMade)
        }

    @Test fun aGetAfterFirstRunMakesNoChat() =
        runTest {
            firstRun.value = true
            val vm = viewModel()
            vm.browse.arrives { it != null }
            vm.started(deep)
            runCurrent()
            assertEquals(emptyList<HfModel>(), chatsMade)
        }

    @Test fun searchingNoLongerMarksFirstRunSeen() =
        runTest {
            val vm = viewModel()
            vm.browse.arrives { it != null }
            vm.setFocused(true)
            vm.setQuery("qwen")
            advanceTimeBy(351)
            runCurrent()
            vm.submit()
            runCurrent()
            assertFalse(firstRun.value)
        }

    @Test fun leavingSearchClearsTheQueryAndTheFocus() =
        runTest {
            val vm = viewModel()
            vm.browse.arrives { it != null }
            vm.setFocused(true)
            vm.setQuery("qwen")
            vm.setFocused(false)
            runCurrent()
            assertTrue("a query left in the field keeps search mode", vm.searching.value)
            vm.setFocused(true)
            vm.leaveSearch()
            runCurrent()
            assertEquals("", vm.query.value)
            assertFalse("browse again", vm.searching.value)
        }

    @Test fun aDownloadInTheQueueAndOnThePhoneIsListedOnce() =
        runTest {
            // the repository rescans the library before the finished download's pending key goes: both list it
            val library = FakeModelLibrary()
            library.shelf.value = listOf(LibraryModel(swift, "Qwen3.5 0.8B", 700_000_000, null, isDefault = false))
            library.queue.value = listOf(swift)
            val vm = viewModel(library = library)
            val browse = vm.browse.arrives { it != null && it.onPhone.isNotEmpty() }!!
            assertEquals(listOf(swift), browse.onPhone.map { it.key })
            assertEquals("not also under Downloading", emptyList<ModelEntry>(), browse.downloading)
            vm.setFocused(true)
            runCurrent()
            val keys =
                vm.search
                    .arrives { it.local.isNotEmpty() }
                    .local
                    .map(ModelEntry::listKey)
            assertEquals("every list key once in search too", keys.distinct(), keys)
        }

    @Test fun theIntroIsKnownFromTheFirstValue() =
        runTest {
            // the shell has read the settings before it shows the page: the intro needs no library scan
            assertEquals(true, viewModel(initialIntro = true).intro.value)
            firstRun.value = true
            assertEquals(false, viewModel(initialIntro = false).intro.value)
        }

    private companion object {
        const val CHAT_ID = 7L
    }
}
