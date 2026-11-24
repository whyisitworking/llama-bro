package com.suhel.llamabro.demo.ui.models

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.SavedStateHandle
import androidx.paging.testing.asSnapshot
import com.suhel.llamabro.DeviceKind
import com.suhel.llamabro.demo.data.SettingsRepository
import com.suhel.llamabro.demo.data.settings.SearchFilters
import com.suhel.llamabro.demo.models.Catalog
import com.suhel.llamabro.demo.models.DeviceFacts
import com.suhel.llamabro.demo.models.FakeScheduler
import com.suhel.llamabro.demo.models.ModelRepository
import com.suhel.llamabro.demo.models.SearchModel
import com.suhel.llamabro.demo.models.TestHub
import com.suhel.llamabro.demo.ui.keepAlive
import com.suhel.llamabro.demo.ui.shutDown
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.ZoneOffset
import kotlin.coroutines.EmptyCoroutineContext

/**
 * What the Models page's view model tests set up (docs/design/models-page.md §2). The library is the real repository
 * over the fake hub (its threads are real, so a first read is awaited in real time); the Hub's cache is
 * [FakeHubSearch], driven in virtual time.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal abstract class ModelsViewModelFixture {
    @get:Rule val folder = TemporaryFolder()
    protected val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    protected val testHub = TestHub()
    protected val hub get() = testHub.hub
    protected val settings by lazy {
        SettingsRepository(PreferenceDataStoreFactory.create(scope = scope) { File(folder.root, "s.preferences_pb") })
    }
    protected val scheduler = FakeScheduler()
    protected val models by lazy {
        ModelRepository(hub, settings, scheduler, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))
    }
    protected val viewModels = mutableListOf<ModelsViewModel>()
    protected val cache = FakeHubSearch()
    protected var factsAsked = 0
    protected val phone = DeviceFacts(budgetBytes = 8L shl 30, chip = DeviceKind.Gpu)

    /** A phone whose memory holds none of the catalog's models. */
    protected val tiny = DeviceFacts(budgetBytes = 300_000_000, chip = DeviceKind.Cpu)

    protected val swift = Catalog.suggested[0].key
    protected val deep = Catalog.suggested[2].key
    protected val day = 24L * 3600 * 1000
    protected val now = 1_800_000_000_000L

    protected val main = StandardTestDispatcher()

    @Before fun setUpMain() = Dispatchers.setMain(main)

    @After fun stop() {
        shutDown(main, viewModels)
        scope.cancel()
        testHub.close()
    }

    /** The page collects `browse`, `search`, `searching` and `fetching`, so the test does too, while it runs. */
    protected fun TestScope.viewModel(
        initialQuery: String = "",
        free: Long = 120_000_000_000L,
        facts: DeviceFacts = phone,
        seen: Boolean = true,
        savedState: SavedStateHandle = SavedStateHandle(),
        repository: ModelRepository = models,
        filters: MutableStateFlow<SearchFilters> = MutableStateFlow(SearchFilters()),
        savedFilters: Flow<SearchFilters> = filters,
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
            searchFilters = savedFilters,
            saveSearchFilters = { change -> filters.update(change) },
        ).also {
            viewModels += it
            it.browse.keepAlive(backgroundScope)
            it.search.keepAlive(backgroundScope)
            it.searching.keepAlive(backgroundScope)
            it.fetching.keepAlive(backgroundScope)
        }

    protected fun result(
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
    protected suspend fun <T> Flow<T>.arrives(predicate: (T) -> Boolean): T =
        withContext(Dispatchers.Default) { withTimeout(5_000) { first(predicate) } }

    /** The page as it is once the library is read: typing after this is in virtual time alone. */
    protected suspend fun ModelsViewModel.ready(): Browse = browse.arrives { it != null }!!

    protected fun TestScope.type(vm: ModelsViewModel, text: String) {
        vm.setQuery(text)
        runCurrent()
    }

    protected fun TestScope.searchFor(vm: ModelsViewModel, text: String) {
        type(vm, text)
        advanceTimeBy(351)
        runCurrent()
    }

    /** Hugging Face's rows as the page reads them: the Pager's, less the local matches. */
    protected suspend fun ModelsViewModel.hubRows(): List<String> = hubPages.asSnapshot().map { it.name }
}
