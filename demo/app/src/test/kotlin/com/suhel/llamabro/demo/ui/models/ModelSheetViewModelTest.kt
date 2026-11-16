package com.suhel.llamabro.demo.ui.models

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.viewModelScope
import com.suhel.llamabro.DeviceKind
import com.suhel.llamabro.demo.data.SettingsRepository
import com.suhel.llamabro.demo.models.Catalog
import com.suhel.llamabro.demo.models.DeviceFacts
import com.suhel.llamabro.demo.models.FakeScheduler
import com.suhel.llamabro.demo.models.FitLabel
import com.suhel.llamabro.demo.models.HubEstimate
import com.suhel.llamabro.demo.models.ModelRepository
import com.suhel.llamabro.demo.models.RepoFile
import com.suhel.llamabro.demo.models.ScriptedEstimates
import com.suhel.llamabro.demo.models.SizeTier
import com.suhel.llamabro.demo.models.TestHub
import com.suhel.llamabro.demo.ui.keepAlive
import com.suhel.llamabro.hub.HfModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
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

@OptIn(ExperimentalCoroutinesApi::class)
class ModelSheetViewModelTest {
    @get:Rule val folder = TemporaryFolder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val testHub = TestHub()

    /** The SDK's projection, as the test sets it. */
    private val hub = ScriptedEstimates()
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
    private val viewModels = mutableListOf<ModelSheetViewModel>()
    private val phone = DeviceFacts(budgetBytes = 8L shl 30, chip = DeviceKind.Npu)

    private val q3 = RepoFile("Qwen3.5-4B-Q3_K_M.gguf", "Q3_K_M", 1_900_000_000)
    private val q4 = RepoFile("Qwen3.5-4B-Q4_K_M.gguf", "Q4_K_M", 2_740_937_888)
    private val q8 = RepoFile("Qwen3.5-4B-Q8_0.gguf", "Q8_0", 4_500_000_000)
    private val bf16 = RepoFile("Qwen3.5-4B-BF16.gguf", "BF16", 8_400_000_000)
    private val repoFiles = listOf(bf16, q8, q4, q3)
    private val deep = ModelRow.of(Catalog.picks.last())

    @Before fun main() = Dispatchers.setMain(StandardTestDispatcher())

    @After fun stop() {
        viewModels.forEach { it.viewModelScope.cancel() }
        Dispatchers.resetMain()
        scope.cancel()
        testHub.close()
    }

    private fun estimateFor(file: RepoFile, fits: Boolean? = true, context: Int? = 32_768) =
        HubEstimate(file.bytes, 675_569_344, fits, context, file.bytes + 300_000_000, DeviceKind.Npu)

    private fun key(file: RepoFile) = HfModel.parse("unsloth/Qwen3.5-4B-GGUF:${file.quant}")

    /**
     * The sheet's screen collects the file list, the selection, the button and the three sizes' fits; so does the test.
     */
    private fun TestScope.viewModel(
        files: suspend (String) -> List<RepoFile> = { repoFiles },
        startTier: SizeTier? = null,
    ): ModelSheetViewModel {
        for (f in repoFiles) hub.estimates[key(f)] = estimateFor(f)
        return ModelSheetViewModel(deep, files, { hub.estimate(it) }, { phone }, models, startTier = startTier).also {
            viewModels += it
            it.state.keepAlive(backgroundScope)
            it.selected.keepAlive(backgroundScope)
            it.button.keepAlive(backgroundScope)
            it.imageReaderBytes.keepAlive(backgroundScope)
        }
    }

    private fun TestScope.showBasic(vm: ModelSheetViewModel) {
        runCurrent()
        (vm.state.value as SheetState.Ready).tiers.forEach { vm.fit(it.second).keepAlive(backgroundScope) }
        runCurrent()
    }

    private fun TestScope.showAdvanced(vm: ModelSheetViewModel) {
        vm.setMode(SheetMode.Advanced)
        runCurrent()
        (vm.state.value as SheetState.Ready).files.forEach { vm.fit(it).keepAlive(backgroundScope) }
        runCurrent()
    }

    @Test fun recommendedIsPreselected() =
        runTest {
            val vm = viewModel()
            showBasic(vm)
            val ready = vm.state.value as SheetState.Ready
            assertEquals(q4, ready.recommended)
            assertEquals(q4, vm.selected.value)
            assertEquals(listOf(SizeTier.Smaller to q3, SizeTier.Recommended to q4, SizeTier.Best to q8), ready.tiers)
        }

    @Test fun aSheetOpenedOnSmallerStartsOnTheSmallerSize() =
        runTest {
            val vm = viewModel(startTier = SizeTier.Smaller)
            showBasic(vm)
            assertEquals(q3, vm.selected.value)
            vm.select(q8) // the user's own pick still wins over where the sheet started
            runCurrent()
            assertEquals(q8, vm.selected.value)
        }

    @Test fun eachTierGetsARealEstimate() =
        runTest {
            val vm = viewModel()
            showBasic(vm)
            for (f in listOf(q3, q4, q8)) {
                assertTrue(
                    "${f.quant} should have a real estimate",
                    vm
                        .fit(f)
                        .value!!
                        .fit.real,
                )
            }
            assertNull("a file nobody shows is never estimated", vm.fit(bf16).value)
        }

    @Test fun anEstimateThatSaysItDoesNotFitTintsTooBig() =
        runTest {
            val vm = viewModel()
            hub.estimates[key(q8)] = estimateFor(q8, fits = false, context = null)
            showBasic(vm)
            assertEquals(
                FitLabel.TooBig,
                vm
                    .fit(q8)
                    .value!!
                    .fit.label,
            )
            assertEquals(
                FitLabel.RunsGreat,
                vm
                    .fit(q3)
                    .value!!
                    .fit.label,
            )
        }

    @Test fun advancedListsEveryQuantAndTheProjector() =
        runTest {
            val vm = viewModel()
            showBasic(vm)
            showAdvanced(vm)
            val ready = vm.state.value as SheetState.Ready
            assertEquals("smallest first", listOf(q3, q4, q8, bf16), ready.files)
            assertTrue(
                "Advanced runs every shown quant's estimate",
                ready.files.all {
                    vm
                        .fit(it)
                        .value!!
                        .fit.real
                },
            )
            assertEquals(675_569_344L, vm.imageReaderBytes.value)
        }

    @Test fun aModelWithoutAnImageReaderHasNoProjectorRow() =
        runTest {
            val vm = viewModel()
            for (f in repoFiles) hub.estimates[key(f)] = estimateFor(f).copy(mmprojBytes = 0)
            showBasic(vm)
            // the catalog knows Qwen3.5 4B's reader, so use a repo it does not know
            val other =
                ModelSheetViewModel(
                    ModelRow.of(Catalog.picks.first()).copy(catalog = null),
                    { repoFiles },
                    { hub.estimate(it) },
                    { phone },
                    models,
                ).also { viewModels += it }
            other.imageReaderBytes.keepAlive(backgroundScope)
            other.state.keepAlive(backgroundScope)
            runCurrent()
            assertNull(other.imageReaderBytes.value)
        }

    @Test fun closingTheSheetCancelsEstimates() =
        runTest {
            var started = 0
            var cancelled = 0
            hub.onEstimate = {
                started++
                try {
                    awaitCancellation()
                } finally {
                    cancelled++
                }
            }
            val vm = viewModel()
            showBasic(vm)
            assertEquals("the three sizes estimate at once", 3, started)
            vm.viewModelScope.cancel() // what clearing the sheet's view model does
            runCurrent()
            assertEquals("every started estimate was cancelled", 3, cancelled)
            assertEquals("nothing queued behind them ran", 3, started)
        }

    @Test fun estimatesRunAFewAtATime() =
        runTest {
            var running = 0
            var peak = 0
            val gate = CompletableDeferred<Unit>()
            hub.onEstimate = {
                running++
                peak = maxOf(peak, running)
                try {
                    gate.await()
                } finally {
                    running--
                }
            }
            val vm = viewModel()
            showBasic(vm)
            showAdvanced(vm)
            assertEquals("three at a time, however many quants", 3, peak)
            gate.complete(Unit)
            runCurrent()
            assertTrue(
                (vm.state.value as SheetState.Ready).files.all {
                    vm
                        .fit(it)
                        .value!!
                        .fit.real
                },
            )
        }

    @Test fun theButtonNamesTheModelInBasicAndTheQuantInAdvanced() =
        runTest {
            val vm = viewModel()
            showBasic(vm)
            assertEquals("Get Qwen3.5 4B · about 8 min", vm.button.value)
            showAdvanced(vm)
            vm.select(q8)
            runCurrent()
            assertEquals("Get Q8_0 · 5.2 GB in all", vm.button.value)
        }

    @Test fun aQuantPickedInAdvancedGivesWayToRecommendedInBasic() =
        runTest {
            val vm = viewModel()
            showBasic(vm)
            vm.setMode(SheetMode.Advanced)
            vm.select(bf16)
            runCurrent()
            assertEquals(bf16, vm.selected.value)
            vm.setMode(SheetMode.Basic)
            runCurrent()
            assertEquals("Basic only offers three sizes", q4, vm.selected.value)
            vm.select(q3)
            vm.setMode(SheetMode.Advanced)
            runCurrent()
            assertEquals("a tier pick carries over", q3, vm.selected.value)
        }

    @Test fun getStartsTheSelectedFileAndReturnsItsKey() =
        runTest {
            val vm = viewModel()
            showBasic(vm)
            vm.select(q3)
            runCurrent()
            val key = vm.get()
            runCurrent()
            assertEquals(HfModel.parse("unsloth/Qwen3.5-4B-GGUF:Q3_K_M"), key)
            scheduler.awaitEnqueued(checkNotNull(key))
        }

    @Test fun aRepoThatCantBeListedFailsAndRetryTriesAgain() =
        runTest {
            var fail = true
            val vm = viewModel(files = { if (fail) throw IOException("offline") else repoFiles })
            runCurrent()
            assertEquals(SheetState.Failed(retryable = true), vm.state.value)
            fail = false
            vm.retry()
            runCurrent()
            assertNotNull((vm.state.value as? SheetState.Ready)?.recommended)
            assertEquals(q4, vm.selected.value)
        }

    @Test fun anEmptyRepoHasNothingToGet() =
        runTest {
            val vm = viewModel(files = { emptyList() })
            runCurrent()
            assertTrue((vm.state.value as SheetState.Ready).tiers.isEmpty())
            assertNull(vm.get())
            assertEquals("", vm.button.value)
        }

    @Test fun theSheetNeverShowsAQuickFitAsReal() =
        runTest {
            val vm = viewModel()
            hub.estimates.clear() // offline: every projection fails
            showBasic(vm)
            assertFalse(
                vm
                    .fit(q4)
                    .value!!
                    .fit.real,
            )
        }
}
