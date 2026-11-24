package com.suhel.llamabro.demo.ui.models

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.SavedStateHandle
import androidx.paging.testing.asSnapshot
import com.suhel.llamabro.DeviceKind
import com.suhel.llamabro.demo.data.SettingsRepository
import com.suhel.llamabro.demo.data.settings.SearchFilters
import com.suhel.llamabro.demo.data.settings.SearchOrder
import com.suhel.llamabro.demo.data.settings.SizeFilter
import com.suhel.llamabro.demo.designsystem.components.DownloadStage
import com.suhel.llamabro.demo.eventually
import com.suhel.llamabro.demo.models.Catalog
import com.suhel.llamabro.demo.models.DeviceFacts
import com.suhel.llamabro.demo.models.FakeScheduler
import com.suhel.llamabro.demo.models.FitLabel
import com.suhel.llamabro.demo.models.FitView
import com.suhel.llamabro.demo.models.Fits
import com.suhel.llamabro.demo.models.Format
import com.suhel.llamabro.demo.models.Guess
import com.suhel.llamabro.demo.models.LibraryModel
import com.suhel.llamabro.demo.models.ModelRepository
import com.suhel.llamabro.demo.models.RepoFile
import com.suhel.llamabro.demo.models.SearchModel
import com.suhel.llamabro.demo.models.TestHub
import com.suhel.llamabro.demo.models.installed
import com.suhel.llamabro.demo.models.query
import com.suhel.llamabro.demo.ui.keepAlive
import com.suhel.llamabro.demo.ui.shutDown
import com.suhel.llamabro.hub.HfModel
import com.suhel.llamabro.hub.SearchSort
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
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
 * The Models page's state (docs/design/models-page.md §2): browse and search over local data, the Hub's stored answer
 * first and its refresh after the debounce. The library is the real repository over the fake hub (its threads are
 * real, so a first read is awaited in real time); the Hub's cache is [FakeHubSearch], driven in virtual time.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ModelsViewModelTest {
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

    /** Hugging Face's rows as the page reads them: the Pager's, less the local matches. */
    private suspend fun ModelsViewModel.hubRows(): List<String> = hubPages.asSnapshot().map { it.name }

    // browse

    @Test fun browseIsNullUntilTheLibraryIsRead() =
        runTest {
            val read = CompletableDeferred<Unit>()
            val slow =
                ModelRepository(
                    hub,
                    settings,
                    scheduler,
                    CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
                    installed = {
                        read.await()
                        hub.installed()
                    },
                )
            val vm = viewModel(repository = slow)
            runCurrent()
            assertNull("nothing is known yet: not \"No models yet\"", vm.browse.value)
            read.complete(Unit)
            assertNotNull(vm.browse.arrives { it != null })
        }

    @Test fun suggestedIsTheCatalogsModelsThatRunGreatOrWellBestFirst() =
        runTest {
            val browse = viewModel().ready()
            val good = setOf(FitLabel.RunsGreat, FitLabel.RunsWell)
            val labels = browse.suggested.map { Fits.quick(it.catalog!!, phone).label }
            assertTrue("$labels", labels.isNotEmpty() && labels.all { it in good })
            assertEquals("best first", labels.sortedBy { it.ordinal }, labels)
            assertEquals(
                Catalog.suggested.count { Fits.quick(it, phone).label in good },
                browse.suggested.size,
            )
            assertNull("something runs well: no short-on-memory line", browse.shortOnMemory)
        }

    @Test fun aPhoneWhereNothingRunsWellSuggestsNothingAndNamesTheSmallestModel() =
        runTest {
            val browse = viewModel(facts = tiny).ready()
            assertEquals("never a kneeling pick", emptyList<ModelEntry>(), browse.suggested)
            assertEquals("Qwen3.5 0.8B", browse.shortOnMemory?.name)
        }

    @Test fun downloadsAndModelsOnThePhoneLeaveTheSuggestions() =
        runTest {
            // another quant of the repo counts: the repo is on the phone
            testHub.install(HfModel.parse("${swift.repo}:Q8_0"))
            models.get(deep)
            val vm = viewModel()
            val browse = vm.browse.arrives { it != null && it.downloading.isNotEmpty() && it.onPhone.isNotEmpty() }!!
            assertEquals(listOf(deep), browse.downloading.map { it.key })
            assertEquals("Qwen3.5 4B", browse.downloading.single().name)
            assertEquals(listOf("Qwen3.5 0.8B"), browse.onPhone.map { it.name })
            val repos = browse.suggested.map { it.repo }
            assertFalse(swift.repo in repos)
            assertFalse(deep.repo in repos)
        }

    @Test fun anEmptyPhoneHasNoStorageLine() =
        runTest {
            assertNull("no \"0 B used\" for a newcomer (M26)", viewModel().ready().storage)
        }

    @Test fun storageIsTheModelsAndWhatIsFree() =
        runTest {
            testHub.install(swift)
            // the one size: the model file and the catalog's image reader
            val onPhone = hub.cached().sumOf { it.sizeBytes } + Catalog.suggested[0].mmprojBytes
            val storage = viewModel(free = 120_000_000_000L).browse.arrives { it?.storage != null }!!.storage!!
            assertEquals(StorageInfo(onPhone, 120_000_000_000L), storage)
        }

    @Test fun theIntroShowsUntilTheFirstRunIsSeen() =
        runTest {
            assertTrue(viewModel(seen = false).ready().intro)
            assertFalse(viewModel(seen = true).ready().intro)
        }

    @Test fun suggestionsCarryTheHubsFactsOnceStoredAndAreRefreshedOnOpen() =
        runTest {
            val vm = viewModel()
            val before = vm.ready().suggested.first()
            assertEquals("the catalog's own facts until then", Catalog.suggested[0].maker, before.maker)
            assertNotNull(before.size)
            assertNull(before.downloads)
            assertEquals(Catalog.suggested.map { it.key.repo.lowercase() }.toSet(), cache.factsAskedOnce.toSet())
            assertTrue("the page asks through the process-wide guard", cache.factsAsked.isEmpty())
            cache.facts.update {
                it + (
                    before.repo.lowercase() to
                        result(
                            "x",
                            repo = before.repo,
                            downloads = 812_000,
                        )
                )
            }
            runCurrent()
            val after =
                vm.browse.value!!
                    .suggested
                    .first()
            assertEquals(812_000L, after.downloads)
            assertEquals("the catalog's name stays", before.name, after.name)
        }

    // search: mode and local

    @Test fun focusSwitchesToSearchWithNoQueryAndListsEveryLocalModel() =
        runTest {
            testHub.install(swift)
            val vm = viewModel()
            vm.browse.arrives { it != null && it.onPhone.isNotEmpty() }
            runCurrent()
            assertFalse(vm.searching.value)
            vm.setFocused(true)
            runCurrent()
            assertTrue("search mode before any typing", vm.searching.value)
            val view = vm.search.arrives { it.local.isNotEmpty() }
            assertEquals("Qwen3.5 0.8B", view.local.first().name)
            // search's suggestions are every catalog model not taken, whatever its fit here
            assertEquals("the phone's model and the five suggestions left", 6, view.local.size)
            assertEquals(HubAnswer.Idle, view.hub)
            vm.setFocused(false)
            runCurrent()
            assertFalse("leaving the empty field is browse again", vm.searching.value)
        }

    /** Focusing the empty field lists browse's suggestions where browse had them, the rest only after them. */
    @Test fun focusingTheEmptyFieldKeepsBrowsesSuggestionsInTheirOrder() =
        runTest {
            val vm = viewModel()
            val browse = vm.browse.arrives { it != null && it.suggested.isNotEmpty() }!!.suggested
            vm.setFocused(true)
            runCurrent()
            val search = vm.search.arrives { it.suggested.size >= browse.size }.suggested
            assertEquals(browse.map { it.repo }, search.take(browse.size).map { it.repo })
        }

    /** A model on the phone runs as its suggestion and its card say: speed from the model file alone. */
    @Test fun aModelOnThePhoneHasTheFitItsSuggestionHad() =
        runTest {
            val gemma = Catalog.suggested[1]
            // a GPU phone on which Gemma 3 4B's total (3.3 GB) is slow but its model file (2.5 GB) runs well
            val gpu = DeviceFacts(budgetBytes = 16L shl 30, chip = DeviceKind.Gpu)
            val suggestion = viewModel(facts = gpu).fit(ModelEntry.of(gemma), backgroundScope).arrives { it != null }
            val library = LibraryModel(gemma.key, gemma.realName, gemma.modelBytes, null, false, gemma.mmprojBytes)
            val onPhone = viewModel(facts = gpu).fit(ModelEntry.of(library), backgroundScope).arrives { it != null }
            assertEquals(FitLabel.RunsWell, suggestion?.label)
            assertEquals("the row on the phone", suggestion, onPhone)
            assertEquals("the switcher", suggestion, Fits.quick(library, gpu))
        }

    @Test fun typingFiltersTheLocalModelsAtOnceAndTheHubWaitsForTheDebounce() =
        runTest {
            val vm = viewModel()
            vm.ready()
            vm.setFocused(true)
            type(vm, "qw")
            assertEquals(
                listOf("Qwen3.5 0.8B", "Qwen3.5 4B", "Qwen3.5 9B"),
                vm.search.value.local
                    .map { it.name },
            )
            vm.hubRows()
            assertEquals("the stored pages at once", listOf("qw"), cache.asked.map { it.text })
            assertEquals("the first page is fetched 350 ms after the last keystroke", listOf(350L), cache.delays)
        }

    @Test fun oneCharacterNeverAsksTheHub() =
        runTest {
            val vm = viewModel()
            vm.ready()
            type(vm, "q")
            // the page draws no Hub part under two characters (HubAnswersTest); here, no list is even opened
            val pages = backgroundScope.launch { vm.hubPages.collect {} }
            advanceTimeBy(1_000)
            runCurrent()
            assertTrue(cache.asked.isEmpty())
            pages.cancel()
        }

    // search: the Hub

    @Test fun theHubIsAskedWithTheDefaultFiltersFitsThisPhoneMostDownloaded() =
        runTest {
            val vm = viewModel()
            vm.ready()
            type(vm, " Qwen ")
            vm.hubRows()
            assertEquals(SearchFilters().query("qwen", phone), cache.asked.single())
            assertEquals(Guess.fitCap(phone), cache.asked.single().maxParameters)
        }

    @Test fun theHubsRowsKeepTheHubsOrder() =
        runTest {
            cache.stored.value =
                mapOf(
                    "abliterated" to
                        listOf(
                            result("Qwen3.8 27B", downloads = 9_000_000),
                            result("Abliterated Small", downloads = 10_000),
                            result("Llama abliterated", downloads = 50_000),
                        ),
                )
            val vm = viewModel()
            vm.ready()
            type(vm, "abliterated")
            assertEquals(
                "never reordered here: a page arriving would reshuffle the list",
                listOf("Qwen3.8 27B", "Abliterated Small", "Llama abliterated"),
                vm.hubRows(),
            )
        }

    @Test fun typingQwenThenGemmaShowsOnlyGemmasRows() =
        runTest {
            cache.stored.value = mapOf("qwen" to listOf(result("Qwen Late")), "gemma" to listOf(result("Gemma Hub")))
            val vm = viewModel()
            vm.ready()
            type(vm, "qwen")
            type(vm, "gemma")
            assertEquals(listOf("Gemma Hub"), vm.hubRows())
            assertEquals("gemma", vm.search.value.query)
        }

    @Test fun fetchingIsTheQuerysPageFetchAndClearingEndsIt() =
        runTest {
            val vm = viewModel()
            vm.ready()
            cache.fetchingNow.value = setOf(SearchFilters().query("qwen", phone).key)
            type(vm, "qwen")
            assertTrue("the field's line runs while the Hub is asked", vm.fetching.value)
            type(vm, "gemma")
            assertFalse("another query's fetch is not this one's", vm.fetching.value)
            type(vm, "qwen")
            assertTrue(vm.fetching.value)
            type(vm, "")
            assertFalse(vm.fetching.value)
        }

    @Test fun retryAsksTheHubAgainForTheSameQuery() =
        runTest {
            val vm = viewModel()
            vm.ready()
            type(vm, "phi")
            vm.hubRows()
            vm.retry()
            runCurrent()
            vm.hubRows()
            assertEquals(listOf("phi", "phi"), cache.asked.map { it.text })
        }

    @Test fun theFiltersComeFromTheSettingsAndAChangeIsSavedAndAskedWith() =
        runTest {
            val saved = MutableStateFlow(SearchFilters(sort = SearchOrder.Newest))
            val vm = viewModel(filters = saved)
            vm.ready()
            assertEquals(SearchOrder.Newest, vm.filters.value.sort)
            type(vm, "qwen")
            vm.hubRows()
            assertEquals(SearchSort.Newest, cache.asked.last().sort)
            vm.setFilters { SearchFilters(size = SizeFilter.Any, seesImages = true) }
            runCurrent()
            assertEquals(SearchFilters(size = SizeFilter.Any, seesImages = true), saved.value)
            vm.hubRows()
            assertNull("any size: no cap", cache.asked.last().maxParameters)
            assertTrue(cache.asked.last().seesImages)
        }

    /** Rows stored under the defaults never show before the saved filters are read: the Hub's part waits for them. */
    @Test fun theHubWaitsForTheSavedFiltersBeforeAsking() =
        runTest {
            val late = MutableSharedFlow<SearchFilters>(replay = 1)
            val vm = viewModel(savedFilters = late)
            vm.ready()
            type(vm, "qwen")
            val pages = backgroundScope.launch { vm.hubPages.collect {} }
            advanceTimeBy(1_000)
            runCurrent()
            assertTrue("nothing asked under the default filters", cache.asked.isEmpty())
            late.emit(SearchFilters(sort = SearchOrder.Newest))
            runCurrent()
            vm.hubRows()
            assertEquals(listOf(SearchSort.Newest), cache.asked.map { it.sort }.distinct())
            pages.cancel()
        }

    /** "Fits this phone" under the smallest model (F176, D128) matches nothing: the Hub isn't asked, the page says. */
    @Test fun aPhoneWhereNothingFitsIsToldSoAndTheHubIsNotAsked() =
        runTest {
            val tiny = DeviceFacts(budgetBytes = 1L shl 20, chip = DeviceKind.Gpu)
            assertTrue("the case under test: a cap under any model", Guess.fitCap(tiny) < Guess.SMALLEST_MODEL)
            assertTrue(Guess.fitCap(DeviceFacts(0, DeviceKind.Gpu)) < Guess.SMALLEST_MODEL)
            val filters = MutableStateFlow(SearchFilters())
            val vm = viewModel(facts = tiny, filters = filters)
            val nothingFits = vm.nothingFits.keepAlive(backgroundScope)
            vm.ready()
            searchFor(vm, "qwen")
            runCurrent()
            assertTrue(nothingFits.value)
            assertTrue(cache.asked.isEmpty())
            vm.setFilters { it.copy(size = SizeFilter.Any) }
            runCurrent()
            assertFalse("any size has no cap", nothingFits.value)
            vm.hubRows()
            assertNull(cache.asked.single().maxParameters)
        }

    @Test fun localMatchesAreNeverFilteredByTheFilters() =
        runTest {
            val vm = viewModel(filters = MutableStateFlow(SearchFilters(seesImages = true)))
            vm.ready()
            type(vm, "qw")
            assertEquals(
                listOf("Qwen3.5 0.8B", "Qwen3.5 4B", "Qwen3.5 9B"),
                vm.search.value.local
                    .map { it.name },
            )
        }

    @Test fun aHubRowForAModelListedLocallyIsLeftOut() =
        runTest {
            testHub.install(HfModel.parse("someone/Phi-GGUF:Q4_K_M"))
            cache.stored.value =
                mapOf(
                    "phi" to
                        listOf(
                            // the library's own repo, another case
                            result("Phi", repo = "SOMEONE/phi-gguf", groupKey = "other"),
                            // the library's model by group key, another upload
                            result("Phi again", repo = "else/Phi-GGUF", groupKey = "phi"),
                            // a group whose members include the library's repo
                            result(
                                "Phi group",
                                repo = "third/Phi-x-GGUF",
                                groupKey = "x",
                                repos = listOf("someone/Phi-GGUF"),
                            ),
                            result("Phi mini", repo = "fourth/Phi-mini-GGUF", groupKey = "phi-mini"),
                        ),
                )
            val vm = viewModel()
            vm.browse.arrives { it != null && it.onPhone.isNotEmpty() }
            type(vm, "phi")
            assertEquals(
                listOf("Phi"),
                vm.search.value.local
                    .map { it.name },
            )
            assertEquals("the local row carries its state", listOf("Phi mini"), vm.hubRows())
        }

    @Test fun aSearchRowsGuessBecomesExactOnceTheCardHasCachedItsFiles() =
        runTest {
            val vm = viewModel()
            val row = ModelEntry.of(result("Phi", repo = "someone/Phi-GGUF", parameters = 4_000_000_000))
            val size = vm.size(row, backgroundScope).keepAlive(backgroundScope)
            runCurrent()
            assertEquals(SizeText.About(Guess.recommendedBytes(4_000_000_000)), size.value)
            cache.files.value =
                mapOf(
                    "someone/phi-gguf" to
                        listOf(
                            RepoFile("Phi-Q3_K_M.gguf", "Q3_K_M", 1_900_000_000, 600_000_000),
                            RepoFile("Phi-Q4_K_M.gguf", "Q4_K_M", 2_700_000_000, 600_000_000),
                            RepoFile("Phi-Q8_0.gguf", "Q8_0", 4_500_000_000, 600_000_000),
                        ),
                )
            runCurrent()
            assertEquals("its Recommended and reader", SizeText.Exact(3_300_000_000, 2_700_000_000), size.value)
        }

    @Test fun aKnownSizeStaysWhateverTheCacheHolds() =
        runTest {
            val vm = viewModel()
            val entry = ModelEntry.of(Catalog.suggested[2])
            assertEquals(entry.size, vm.size(entry, backgroundScope).value)
        }

    @Test fun recentSearchesComeFromTheRepositoryAndOnlySubmitsAreKept() =
        runTest {
            cache.recent.value = listOf("gemma", "phi")
            val vm = viewModel()
            vm.ready()
            assertEquals(listOf("gemma", "phi"), vm.search.arrives { it.recent.isNotEmpty() }.recent)
            searchFor(vm, "qwen")
            assertTrue("a debounced query is not a submit", cache.remembered.isEmpty())
            vm.submit()
            runCurrent()
            assertEquals(listOf("qwen"), cache.remembered)
            vm.pickRecent("phi")
            runCurrent()
            assertEquals("phi", vm.query.value)
            assertTrue(vm.searching.value)
            assertEquals(listOf("qwen", "phi"), cache.remembered)
        }

    @Test fun whatWasTypedSurvivesProcessDeath() =
        runTest {
            val saved = SavedStateHandle()
            viewModel(savedState = saved).setQuery("gemma")
            val restored = SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) })
            assertEquals("gemma", viewModel(savedState = restored).query.value)
        }

    // a row's state

    @Test fun aDownloadStartedElsewhereShowsOnItsRowWithItsPanel() =
        runTest {
            val key = HfModel.parse("someone/Qwen-GGUF:Q4_K_M")
            models.get(key)
            testHub.downloading(key)
            val vm = viewModel()
            val state =
                vm
                    .state(
                        ModelEntry.of(result("Qwen")),
                        backgroundScope,
                    ).arrives { it?.badge is RowBadge.Downloading }!!
            assertEquals(DownloadStage.Downloading, state.download?.stage)
            assertEquals("the quant, never a tier", "Q4_K_M", state.download?.quant)
            assertNull("another repo has none", vm.state(ModelEntry.of(result("Other")), backgroundScope).value)
        }

    @Test fun aDownloadWaitingItsTurnNamesTheOneAhead() =
        runTest {
            val running = HfModel.parse("someone/Running-GGUF:Q4_K_M")
            val waiting = HfModel.parse("someone/Waiting-GGUF:Q4_K_M")
            models.get(running)
            models.get(waiting)
            testHub.downloading(running)
            val vm = viewModel()
            val state =
                vm.state(ModelEntry.of(result("Waiting")), backgroundScope).arrives {
                    it?.download?.detail ==
                        "after Running"
                }!!
            assertEquals(RowBadge.Queued, state.badge)
            assertEquals(DownloadStage.Waiting, state.download?.stage)
        }

    @Test fun anInstalledRepoIsOnPhoneAnyQuantAnyCase() =
        runTest {
            testHub.install(HfModel.parse("someone/Qwen-GGUF:Q8_0"))
            val vm = viewModel()
            val row = ModelEntry.of(result("Qwen", repo = "Someone/qwen-gguf"))
            assertEquals(RowState(RowBadge.OnPhone, null), vm.state(row, backgroundScope).arrives { it != null })
        }

    @Test fun aPausedOrFailedDownloadSaysSo() =
        runTest {
            val paused = HfModel.parse("someone/Paused-GGUF:Q4_K_M")
            val failed = HfModel.parse("someone/Failed-GGUF:Q4_K_M")
            models.get(paused)
            models.get(failed)
            testHub.partial(paused)
            testHub.refuse(failed, TestHub.Refusal.Unauthorized)
            val vm = viewModel()
            assertEquals(
                RowBadge.Paused,
                vm
                    .state(ModelEntry.of(result("Paused")), backgroundScope)
                    .arrives {
                        it !=
                            null
                    }!!
                    .badge,
            )
            val stopped =
                vm
                    .state(
                        ModelEntry.of(result("Failed")),
                        backgroundScope,
                    ).arrives { it?.badge is RowBadge.Failed }!!
            assertEquals(RowBadge.Failed("This model needs a Hugging Face token."), stopped.badge)
            assertEquals(DownloadStage.Stopped, stopped.download?.stage)
            assertEquals(
                "the row and the card read one line",
                "This model needs a Hugging Face token.",
                stopped.download?.detail,
            )
        }

    // fit

    @Test fun fitComesWithTheRowsWithNoFurtherCall() =
        runTest {
            cache.stored.value =
                mapOf(
                    "zz" to
                        listOf(result("Qwen", parameters = 4_000_000_000), result("Other", parameters = 1_000_000_000)),
                )
            val vm = viewModel()
            vm.ready()
            type(vm, "zz")
            val rows = vm.hubPages.asSnapshot()
            rows.forEach {
                assertNotNull(
                    "${it.name} has its fit on its first value",
                    vm.fit(it, backgroundScope).value,
                )
            }
            assertEquals("the phone is read once, not per row", 1, factsAsked)
            assertEquals(
                Guess.fit(result("Qwen", parameters = 4_000_000_000), phone),
                vm.fit(rows[0], backgroundScope).value,
            )
        }

    @Test fun aCatalogRowsFitIsItsShippedSizes() =
        runTest {
            val vm = viewModel()
            runCurrent()
            assertEquals(
                Fits.quick(Catalog.suggested[2], phone),
                vm.fit(ModelEntry.of(Catalog.suggested[2]), backgroundScope).value,
            )
        }

    @Test fun aRowWithNoParameterCountHasNoFitRatherThanAWrongOne() =
        runTest {
            val vm = viewModel()
            runCurrent()
            assertNull(vm.fit(ModelEntry.of(result("Qwen")), backgroundScope).value)
        }

    @Test fun anExactFitReplacesTheGuess() =
        runTest {
            val vm = viewModel()
            val row = ModelEntry.of(result("Qwen", parameters = 4_000_000_000))
            val fit = vm.fit(row, backgroundScope).keepAlive(backgroundScope)
            runCurrent()
            val exact = FitView(FitLabel.TooBig, 0)
            assertTrue(fit.value != exact)
            vm.exactFit(row, exact)
            runCurrent()
            assertEquals(exact, fit.value)
        }

    // remove, cancel, pause

    @Test fun askingToRemoveDeletesNothingUntilConfirmed() =
        runTest {
            testHub.install(swift)
            val vm = viewModel()
            vm.browse.arrives { it != null && it.onPhone.isNotEmpty() }
            val model = models.library.first().single()
            vm.askRemove(model)
            assertEquals(model, vm.pendingRemove.value)
            vm.dismissRemove()
            assertNull(vm.pendingRemove.value)
            assertTrue("dismissing keeps the model", testHub.onPhone(swift))
            vm.askRemove(model)
            vm.confirmRemove()
            withContext(Dispatchers.Default) { eventually("the model is deleted") { !testHub.onPhone(swift) } }
            assertNull(vm.pendingRemove.value)
        }

    @Test fun aRemoveThatCantStopTheWorkSaysSo() =
        runTest {
            testHub.install(swift)
            val vm = viewModel()
            vm.browse.arrives { it != null && it.onPhone.isNotEmpty() }
            val model = models.library.first().single()
            scheduler.cancelFailure = IllegalStateException("WorkManager's database is gone")
            vm.askRemove(model)
            vm.confirmRemove()
            assertEquals("Couldn't remove ${model.name}. Try again.", vm.problem.arrives { it != null })
            assertTrue("nothing was deleted", testHub.onPhone(swift))
        }

    @Test fun lastUseReadsInPlainWords() =
        runTest {
            val vm = viewModel()

            fun model(ago: Long?) = ModelEntry.of(LibraryModel(swift, "Qwen3.5 0.8B", 1, ago?.let { now - it }, false))
            assertEquals("Used today", vm.lastUsed(model(1_000)))
            assertEquals("Yesterday", vm.lastUsed(model(day + 60_000)))
            assertEquals("3 days ago", vm.lastUsed(model(3 * day)))
            assertEquals("Not used yet", vm.lastUsed(model(null)))
        }

    @Test fun usedAgoNeverGoesNegative() {
        assertEquals("Used today", Format.usedAgo(now + day, now, ZoneOffset.UTC))
    }
}
