package com.suhel.llamabro.demo.ui.models

import androidx.paging.testing.asSnapshot
import com.suhel.llamabro.demo.designsystem.components.DownloadStage
import com.suhel.llamabro.demo.models.Catalog
import com.suhel.llamabro.demo.models.FitLabel
import com.suhel.llamabro.demo.models.FitView
import com.suhel.llamabro.demo.models.Fits
import com.suhel.llamabro.demo.models.Guess
import com.suhel.llamabro.demo.models.RepoFile
import com.suhel.llamabro.demo.models.TestHub
import com.suhel.llamabro.demo.ui.keepAlive
import com.suhel.llamabro.hub.HfModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A Models page row's own flows ([RowStates]): its size, its state and its fit. */
@OptIn(ExperimentalCoroutinesApi::class)
internal class ModelRowsTest : ModelsViewModelFixture() {
    // size

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

    @Test fun aRowRecreatedAfterItsExactSizeWasKnownStartsExact() =
        runTest {
            val vm = viewModel()
            val row = ModelEntry.of(result("Phi", repo = "someone/Phi-GGUF", parameters = 4_000_000_000))
            cache.files.value =
                mapOf("someone/phi-gguf" to listOf(RepoFile("Phi-Q4_K_M.gguf", "Q4_K_M", 2_700_000_000, 600_000_000)))
            val shown = CoroutineScope(backgroundScope.coroutineContext + Job(backgroundScope.coroutineContext[Job]))
            val size = vm.size(row, shown)
            runCurrent()
            val exact = SizeText.Exact(3_300_000_000, 2_700_000_000)
            assertEquals(exact, size.value)
            // the row scrolls away and back: its flows end with it, and the new ones start from what was found
            shown.cancel()
            val again = CoroutineScope(backgroundScope.coroutineContext + Job(backgroundScope.coroutineContext[Job]))
            assertEquals("no flicker from the guess", exact, vm.size(row, again).value)
            again.cancel()
        }

    @Test fun aKnownSizeStaysWhateverTheCacheHolds() =
        runTest {
            val vm = viewModel()
            val entry = ModelEntry.of(Catalog.suggested[2])
            assertEquals(entry.size, vm.size(entry, backgroundScope).value)
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
}
