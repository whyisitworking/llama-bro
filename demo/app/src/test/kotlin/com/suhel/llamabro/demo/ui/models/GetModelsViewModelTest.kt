package com.suhel.llamabro.demo.ui.models

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.suhel.llamabro.demo.data.SettingsRepository
import com.suhel.llamabro.demo.models.DownloadStatus
import com.suhel.llamabro.demo.models.FakeHubGateway
import com.suhel.llamabro.demo.models.FakeScheduler
import com.suhel.llamabro.demo.models.FitLabel
import com.suhel.llamabro.demo.models.FitView
import com.suhel.llamabro.demo.models.ModelRepository
import com.suhel.llamabro.demo.models.SearchFailed
import com.suhel.llamabro.demo.models.SearchModel
import com.suhel.llamabro.demo.ui.keepAlive
import com.suhel.llamabro.hub.HfModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
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
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class GetModelsViewModelTest {
    @get:Rule val folder = TemporaryFolder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val hub = FakeHubGateway()
    private val settings by lazy {
        SettingsRepository(PreferenceDataStoreFactory.create(scope = scope) { File(folder.root, "s.preferences_pb") })
    }
    private val models by lazy {
        ModelRepository(
            hub,
            settings,
            FakeScheduler(),
            CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        )
    }
    private val viewModels = mutableListOf<GetModelsViewModel>()

    private val searches = mutableListOf<String>()
    private var answer: suspend (String) -> List<SearchModel> = { emptyList() }

    @Before fun main() = Dispatchers.setMain(StandardTestDispatcher())

    @After fun stop() {
        viewModels.forEach { it.viewModelScope.cancel() }
        Dispatchers.resetMain()
        scope.cancel()
    }

    /** The screen collects `state`, so the test does too, for as long as the test runs. */
    private fun TestScope.viewModel(rowFit: suspend (ModelRow) -> FitView? = { null }, initialQuery: String = "") =
        GetModelsViewModel(
            search = { q ->
                searches += q
                answer(q)
            },
            rowFit = rowFit,
            models = models,
            initialQuery = initialQuery,
            savedState = SavedStateHandle(),
        ).also {
            viewModels += it
            it.state.keepAlive(backgroundScope)
        }

    private fun result(name: String, repo: String = "someone/$name-GGUF") =
        SearchModel(
            groupKey = name.lowercase(),
            displayName = name,
            repo = repo,
            author = "someone",
            downloads = 1_200_000,
            sees = false,
            line = "Balanced, a good all-rounder",
            catalog = null,
        )

    @Test fun getModelsShowsTheFiveSuggestionsFirst() =
        runTest {
            val vm = viewModel()
            runCurrent()
            val state = vm.state.value as GetModelsState.Suggestions
            assertEquals(
                // a model is called by its own name everywhere
                listOf("Qwen3.5 0.8B", "Gemma 3 4B", "Qwen3.5 4B", "Llama 3.2 3B", "Qwen3.5 9B"),
                state.rows.map { it.name },
            )
            assertNull(state.offlineLine)
        }

    @Test fun shortQueryShowsSuggestions() =
        runTest {
            val vm = viewModel()
            vm.setQuery("q")
            advanceTimeBy(1_000)
            runCurrent()
            assertTrue(vm.state.value is GetModelsState.Suggestions)
            assertTrue("one character must not search", searches.isEmpty())
        }

    @Test fun queryIsDebounced() =
        runTest {
            answer = { listOf(result("Qwen")) }
            val vm = viewModel()
            vm.setQuery("qw")
            advanceTimeBy(100)
            vm.setQuery("qwe")
            advanceTimeBy(100)
            vm.setQuery("qwen")
            advanceTimeBy(349)
            assertTrue("still inside the 350 ms of the last keystroke", searches.isEmpty())
            advanceTimeBy(2)
            runCurrent()
            assertEquals("three keystrokes, one search, for the last text", listOf("qwen"), searches)
            assertEquals(listOf("Qwen"), (vm.state.value as GetModelsState.Results).rows.map { it.name })
        }

    @Test fun aQueryFromFirstRunSearchesOnArrival() =
        runTest {
            answer = { listOf(result("Qwen")) }
            val vm = viewModel(initialQuery = "qwen")
            assertEquals("qwen", vm.query.value)
            advanceTimeBy(351)
            runCurrent()
            assertEquals(listOf("qwen"), searches)
        }

    @Test fun offlineKeepsSuggestionsAndSaysSo() =
        runTest {
            answer =
                { throw SearchFailed("Couldn't reach Hugging Face", retryable = true, cause = IOException("no route")) }
            val vm = viewModel()
            vm.setQuery("qwen")
            advanceTimeBy(351)
            runCurrent()
            val state = vm.state.value as GetModelsState.Suggestions
            assertEquals(5, state.rows.size)
            assertEquals(GetModelsViewModel.OFFLINE_LINE, state.offlineLine)
        }

    @Test fun anErrorShowsRetryAndRetryAsksAgain() =
        runTest {
            var fail = true
            answer =
                {
                    if (fail) {
                        throw SearchFailed(
                            "Hugging Face answered 503",
                            retryable = true,
                        )
                    } else {
                        listOf(result("Qwen"))
                    }
                }
            val vm = viewModel()
            vm.setQuery("qwen")
            advanceTimeBy(351)
            runCurrent()
            assertEquals(GetModelsState.Failed(retryable = true), vm.state.value)
            fail = false
            vm.retry()
            runCurrent()
            assertTrue(vm.state.value is GetModelsState.Results)
            assertEquals(listOf("qwen", "qwen"), searches)
        }

    @Test fun noMatchesIsItsOwnState() =
        runTest {
            val vm = viewModel()
            vm.setQuery("zzzz")
            advanceTimeBy(351)
            runCurrent()
            assertEquals(GetModelsState.NoResults, vm.state.value)
        }

    @Test fun aNewQueryCancelsTheSearchInFlight() =
        runTest {
            val slow = CompletableDeferred<List<SearchModel>>()
            answer = { q -> if (q == "slow") slow.await() else listOf(result("Fast")) }
            val vm = viewModel()
            vm.setQuery("slow")
            advanceTimeBy(351)
            runCurrent()
            assertEquals(GetModelsState.Searching, vm.state.value)
            vm.setQuery("fast")
            advanceTimeBy(351)
            runCurrent()
            slow.complete(listOf(result("Slow")))
            runCurrent()
            assertEquals(listOf("Fast"), (vm.state.value as GetModelsState.Results).rows.map { it.name })
        }

    @Test fun aLateFitArrivesOnItsOwnFlow() =
        runTest {
            val gate = CompletableDeferred<FitView>()
            val vm = viewModel(rowFit = { gate.await() })
            val first = ModelRow.of(result("Qwen"))
            val second = ModelRow.of(result("Other"))
            val firstFit = vm.fit(first).keepAlive(backgroundScope)
            val secondFit = vm.fit(second).keepAlive(backgroundScope)
            runCurrent()
            assertNull(firstFit.value)
            gate.complete(FitView(FitLabel.RunsWell, 12, real = false))
            runCurrent()
            assertEquals(FitLabel.RunsWell, firstFit.value?.label)
            assertEquals("each row has its own flow", false, firstFit === secondFit)
            assertTrue("asking again gives the same flow", vm.fit(first) === firstFit)
        }

    @Test fun aRowWhoseFitFailsHasNoTintRatherThanAWrongOne() =
        runTest {
            val vm = viewModel(rowFit = { throw IOException("tree failed") })
            val fit = vm.fit(ModelRow.of(result("Qwen"))).keepAlive(backgroundScope)
            runCurrent()
            assertNull(fit.value)
        }

    @Test fun downloadProgressShowsOnTheRowItStartedFrom() =
        runTest {
            val vm = viewModel()
            val row = ModelRow.of(result("Qwen"))
            val other = ModelRow.of(result("Other"))
            val key = HfModel.parse("someone/Qwen-GGUF:Q4_K_M")
            assertNull(vm.progress(row).first())
            vm.started(row, key)
            hub.status(key).value = DownloadStatus.Downloading(300, 740, 9_800_000, 40)
            assertEquals(DownloadStatus.Downloading(300, 740, 9_800_000, 40), vm.progress(row).first { it != null })
            assertNull("another row shows nothing", vm.progress(other).first())
        }

    @Test fun whatWasTypedSurvivesProcessDeath() {
        val saved = SavedStateHandle()
        val vm =
            GetModelsViewModel({ emptyList() }, { null }, models, initialQuery = "", savedState = saved)
                .also { viewModels += it }
        vm.setQuery("gemma")
        val restored = SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) })
        val again =
            GetModelsViewModel({ emptyList() }, { null }, models, initialQuery = "", savedState = restored)
                .also { viewModels += it }
        assertEquals("gemma", again.query.value)
    }
}
