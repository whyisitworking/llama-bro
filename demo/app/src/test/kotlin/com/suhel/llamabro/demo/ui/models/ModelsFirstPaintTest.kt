package com.suhel.llamabro.demo.ui.models

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.SavedStateHandle
import com.suhel.llamabro.DeviceKind
import com.suhel.llamabro.demo.data.SettingsRepository
import com.suhel.llamabro.demo.eventually
import com.suhel.llamabro.demo.models.Catalog
import com.suhel.llamabro.demo.models.DeviceFacts
import com.suhel.llamabro.demo.models.FakeScheduler
import com.suhel.llamabro.demo.models.FitLabel
import com.suhel.llamabro.demo.models.Fits
import com.suhel.llamabro.demo.models.ModelRepository
import com.suhel.llamabro.demo.models.SearchModel
import com.suhel.llamabro.demo.models.TestHub
import com.suhel.llamabro.demo.ui.keepAlive
import com.suhel.llamabro.demo.ui.shutDown
import com.suhel.llamabro.hub.HfModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.ZoneOffset
import kotlin.coroutines.EmptyCoroutineContext

/**
 * The Models page's first paint from local data alone (D119): the library before
 * the phone's facts, the Hub's stored facts in the first value, a library row's fit and maker without its card. The
 * setup is [ModelsViewModelTest]'s.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ModelsFirstPaintTest {
    @get:Rule val folder = TemporaryFolder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val testHub = TestHub()
    private val hub get() = testHub.hub
    private val settings by lazy {
        SettingsRepository(PreferenceDataStoreFactory.create(scope = scope) { File(folder.root, "s.preferences_pb") })
    }
    private val scheduler = FakeScheduler()
    private val models by lazy {
        ModelRepository(hub, settings, scheduler, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))
    }
    private val viewModels = mutableListOf<ModelsViewModel>()
    private val cache = FakeHubSearch()
    private var factsAsked = 0
    private val phone = DeviceFacts(budgetBytes = 8L shl 30, chip = DeviceKind.Gpu)

    /** A phone whose memory holds none of the catalog's models. */
    private val tiny = DeviceFacts(budgetBytes = 300_000_000, chip = DeviceKind.Cpu)

    private val swift = Catalog.suggested[0].key
    private val deep = Catalog.suggested[2].key
    private val day = 24L * 3600 * 1000
    private val now = 1_800_000_000_000L

    private val main = StandardTestDispatcher()

    @Before fun setUpMain() = Dispatchers.setMain(main)

    @After fun stop() {
        shutDown(main, viewModels)
        scope.cancel()
        testHub.close()
    }

    /** The page collects `browse`, `search`, `searching` and `fetching`, so the test does too, while it runs. */
    private fun TestScope.viewModel(
        initialQuery: String = "",
        free: Long = 120_000_000_000L,
        facts: DeviceFacts = phone,
        seen: Boolean = true,
        savedState: SavedStateHandle = SavedStateHandle(),
        repository: ModelRepository = models,
    ): ModelsViewModel =
        ModelsViewModel(
            hub = cache,
            models = repository,
            facts = {
                factsAsked++
                facts
            },
            freeBytes = { free },
            firstRunSeen = MutableStateFlow(seen),
            initialQuery = initialQuery,
            clock = { now },
            zone = ZoneOffset.UTC,
            io = EmptyCoroutineContext,
            savedState = savedState,
        ).also {
            viewModels += it
            it.browse.keepAlive(backgroundScope)
            it.search.keepAlive(backgroundScope)
            it.searching.keepAlive(backgroundScope)
            it.fetching.keepAlive(backgroundScope)
        }

    private fun result(
        name: String,
        repo: String = "someone/$name-GGUF",
        parameters: Long? = null,
        downloads: Long = 1_200_000,
        groupKey: String = name.lowercase(),
        repos: List<String> = emptyList(),
        sees: Boolean = false,
    ) = SearchModel(
        groupKey = groupKey,
        name = name,
        repo = repo,
        author = "someone",
        downloads = downloads,
        sees = sees,
        catalog = null,
        parameters = parameters,
        repos = repos,
    )

    /** Waits in real time for what the repository's threads deliver, while the test dispatcher keeps running. */
    private suspend fun <T> Flow<T>.arrives(predicate: (T) -> Boolean): T =
        withContext(Dispatchers.Default) { withTimeout(5_000) { first(predicate) } }

    /** The page as it is once the library is read: typing after this is in virtual time alone. */
    private suspend fun ModelsViewModel.ready(): Browse = browse.arrives { it != null }!!

    private fun TestScope.type(vm: ModelsViewModel, text: String) {
        vm.setQuery(text)
        runCurrent()
    }

    private fun TestScope.searchFor(vm: ModelsViewModel, text: String) {
        type(vm, text)
        advanceTimeBy(351)
        runCurrent()
    }

    // the first paint

    @Test fun browseShowsTheLibraryBeforeThePhonesFactsAreRead() =
        runTest {
            testHub.install(swift)
            val facts = CompletableDeferred<DeviceFacts>()
            val vm =
                ModelsViewModel(
                    hub = cache,
                    models = models,
                    facts = { facts.await() },
                    freeBytes = { 0L },
                    firstRunSeen = MutableStateFlow(true),
                    clock = { now },
                    zone = ZoneOffset.UTC,
                    io = EmptyCoroutineContext,
                    savedState = SavedStateHandle(),
                ).also { viewModels += it }
            vm.browse.keepAlive(backgroundScope)
            // the page was empty ~800 ms while the facts were read; what is on the phone is local
            val first = vm.browse.arrives { it != null && it.onPhone.isNotEmpty() }!!
            assertEquals(listOf("Qwen3.5 0.8B"), first.onPhone.map { it.name })
            assertTrue("the suggestions, chosen by fit, wait for the facts", first.suggested.isEmpty())
            assertNull(first.shortOnMemory)
            facts.complete(phone)
            assertTrue(
                vm.browse
                    .arrives { it?.suggested?.isNotEmpty() == true }!!
                    .suggested
                    .isNotEmpty(),
            )
        }

    @Test fun storedDownloadCountsAreInEveryBrowseValueFromTheFirst() =
        runTest {
            val first = Catalog.suggested.first { Fits.quick(it, phone).label == FitLabel.RunsGreat }
            cache.facts.value =
                mapOf(first.key.repo.lowercase() to result("x", repo = first.key.repo, downloads = 812_000))
            val vm = viewModel()
            val seen = mutableListOf<Browse>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                vm.browse.filterNotNull().collect { seen += it }
            }
            vm.ready()
            runCurrent()
            assertTrue(seen.isNotEmpty())
            for (browse in seen) {
                val row = browse.suggested.first { it.repo == first.key.repo }
                // the counts popped in after the first paint, one row at a time
                assertEquals(812_000L, row.downloads)
            }
        }

    @Test fun aLibraryRowHasItsFitFromItsFileAndItsRepoFactsAreAskedForOnce() =
        runTest {
            val foreign = HfModel.parse("someone/Foo-2B-GGUF:Q4_K_M")
            testHub.install(foreign)
            val vm = viewModel()
            val row =
                vm.browse
                    .arrives { it != null && it.onPhone.isNotEmpty() }!!
                    .onPhone
                    .single()
            // a library row lacked its fit and maker until its card was opened
            assertNotNull("its fit, from its own file", vm.fit(row, backgroundScope).arrives { it != null })
            withContext(Dispatchers.Default) {
                eventually("its repo facts are asked for") { "someone/foo-2b-gguf" in cache.factsAskedOnce }
            }
        }

    // search finds a catalog model by name whatever its fit; browse suggests only what runs well

    @Test fun searchListsEveryMatchingCatalogModelButBrowseOnlyTheOnesThatRunWell() =
        runTest {
            val vm = viewModel(facts = tiny)
            val browse = vm.ready()
            assertTrue("nothing runs well on this phone", browse.suggested.isEmpty())
            vm.setFocused(true)
            vm.setQuery("qwen")
            runCurrent()
            val search = vm.search.arrives { it.query == "qwen" && it.suggested.isNotEmpty() }
            val names = search.suggested.map { it.name }
            assertTrue("$names", names.containsAll(listOf("Qwen3.5 0.8B", "Qwen3.5 4B", "Qwen3.5 9B")))
            assertTrue("$names", names.all { it.contains("qwen", ignoreCase = true) })
            assertTrue("its own sizes", search.suggested.all { it.size != null })
        }
}
