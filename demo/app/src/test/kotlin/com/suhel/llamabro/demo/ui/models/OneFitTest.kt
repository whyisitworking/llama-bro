package com.suhel.llamabro.demo.ui.models

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.SavedStateHandle
import com.suhel.llamabro.DeviceKind
import com.suhel.llamabro.demo.data.SettingsRepository
import com.suhel.llamabro.demo.models.Catalog
import com.suhel.llamabro.demo.models.DeviceFacts
import com.suhel.llamabro.demo.models.DownloadRecord
import com.suhel.llamabro.demo.models.FakeScheduler
import com.suhel.llamabro.demo.models.FitView
import com.suhel.llamabro.demo.models.Fits
import com.suhel.llamabro.demo.models.LibraryModel
import com.suhel.llamabro.demo.models.ModelRepository
import com.suhel.llamabro.demo.models.RepoFile
import com.suhel.llamabro.demo.models.SearchModel
import com.suhel.llamabro.demo.models.TestHub
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
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.ZoneOffset
import kotlin.coroutines.EmptyCoroutineContext

/**
 * One file has one fit (D121): the Models page's fits, as [ModelsViewModelTest] builds the
 * page (the real repository over the fake hub), set beside the card's and the switcher's for the same file.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OneFitTest {
    @get:Rule val folder = TemporaryFolder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val testHub = TestHub()
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
    private val viewModels = mutableListOf<ModelsViewModel>()

    private val main = StandardTestDispatcher()

    @Before fun setUpMain() = Dispatchers.setMain(main)

    @After fun stop() {
        shutDown(main, viewModels)
        scope.cancel()
        testHub.close()
    }

    private fun TestScope.viewModel(facts: DeviceFacts): ModelsViewModel {
        val cache = FakeHubSearch()
        return ModelsViewModel(
            hub = cache,
            models = models,
            facts = { facts },
            freeBytes = { 120_000_000_000L },
            firstRunSeen = MutableStateFlow(true),
            initialQuery = "",
            clock = { 0L },
            zone = ZoneOffset.UTC,
            io = EmptyCoroutineContext,
            savedState = SavedStateHandle(),
        ).also { viewModels += it }
    }

    /** Waits in real time for what the repository's threads deliver, while the test dispatcher keeps running. */
    private suspend fun <T> Flow<T>.arrives(predicate: (T) -> Boolean): T =
        withContext(Dispatchers.Default) { withTimeout(5_000) { first(predicate) } }

    /**
     * One file has one fit wherever it shows: as a suggestion, a download row, the landed row, the
     * card's tier and the switcher's row, all asked of one page (a fit is the file's, not its group's). Speed comes
     * from the model file alone, and for a mixture-of-experts model (A3B) from its active share.
     */
    @Test fun oneFileHasOneFitWhereverItShows() =
        runTest {
            val big = DeviceFacts(budgetBytes = 32L shl 30, chip = DeviceKind.Gpu)
            val vm = viewModel(facts = big)
            val gemma = Catalog.suggested[1]
            val suggestion = vm.fit(ModelEntry.of(gemma)).arrives { it != null }
            val card = ModelEntry.of(gemma)
            assertEquals(
                "the catalog's file",
                listOf(suggestion),
                fitsOf(vm, gemma.key, gemma.modelBytes, gemma.mmprojBytes, card, big).values.distinct(),
            )
            val q8 = HfModel.parse("${gemma.key.repo}:Q8_0")
            val q8Fits = fitsOf(vm, q8, 4_130_000_000, gemma.mmprojBytes, card, big)
            assertEquals("Gemma's Q8_0: $q8Fits", 1, q8Fits.values.distinct().size)
            assertEquals(Fits.quick(4_130_000_000 + gemma.mmprojBytes, 4_130_000_000, big), q8Fits.values.first())

            val moe = HfModel.parse("unsloth/Qwen3-30B-A3B-GGUF:Q4_K_M")
            val hubRow =
                ModelEntry.of(
                    SearchModel(
                        groupKey = "qwen3-30b-a3b",
                        name = "Qwen3-30B-A3B",
                        repo = moe.repo,
                        author = "unsloth",
                        downloads = 1,
                        sees = false,
                        catalog = null,
                        parameters = 30_532_122_624,
                        activeParameters = 3_000_000_000,
                    ),
                )
            val moeFits = fitsOf(vm, moe, 18_556_689_568, 0, hubRow, big)
            assertEquals("Qwen3 30B A3B: $moeFits", 1, moeFits.values.distinct().size)
            // a token reads the three billion active weights of thirty
            assertEquals(Fits.quick(18_556_689_568, 1_855_668_956, big), moeFits.values.first())
        }

    /** The fit of the file [key] ([modelBytes] and its reader) as each place that shows it figures it. */
    private suspend fun fitsOf(
        vm: ModelsViewModel,
        key: HfModel,
        modelBytes: Long,
        readerBytes: Long,
        card: ModelEntry,
        facts: DeviceFacts,
    ): Map<String, FitView?> {
        val total = modelBytes + readerBytes
        val download = ModelEntry.downloading(key, DownloadRecord(key, card.name, null, total, modelBytes))
        val library = LibraryModel(key, card.name, modelBytes, null, false, readerBytes)
        val file = RepoFile("${key.tag}.gguf", key.tag!!, modelBytes, readerBytes)
        return mapOf(
            "download row" to vm.fit(download).arrives { it != null },
            "landed row" to vm.fit(ModelEntry.of(library)).arrives { it != null },
            "card's tier" to CardViews(card, null).fit(file, facts),
            "switcher" to Fits.quick(library, facts),
        )
    }
}
