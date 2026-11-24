package com.suhel.llamabro.demo.ui.models

import com.suhel.llamabro.DeviceKind
import com.suhel.llamabro.demo.designsystem.components.DownloadStage
import com.suhel.llamabro.demo.designsystem.components.MarkdownBlock
import com.suhel.llamabro.demo.models.Cached
import com.suhel.llamabro.demo.models.Catalog
import com.suhel.llamabro.demo.models.DeviceFacts
import com.suhel.llamabro.demo.models.DownloadFailure
import com.suhel.llamabro.demo.models.DownloadRecord
import com.suhel.llamabro.demo.models.DownloadStatus
import com.suhel.llamabro.demo.models.FitView
import com.suhel.llamabro.demo.models.Fits
import com.suhel.llamabro.demo.models.LibraryModel
import com.suhel.llamabro.demo.models.PageFacts
import com.suhel.llamabro.demo.models.RepoFile
import com.suhel.llamabro.demo.models.SearchModel
import com.suhel.llamabro.demo.models.SizeTier
import com.suhel.llamabro.demo.ui.shutDown
import com.suhel.llamabro.hub.HfModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
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
import org.junit.Test
import java.io.IOException
import kotlin.coroutines.EmptyCoroutineContext

/**
 * The model card (docs/design/models-page.md §4): one view drawn from local data, final from its first value, with the
 * pinned foot in every state.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ModelSheetViewModelTest {
    private val hub = FakeHubCard()
    private val models = FakeModelLibrary()
    private val viewModels = mutableListOf<ModelSheetViewModel>()
    private val phone = DeviceFacts(budgetBytes = 8L shl 30, chip = DeviceKind.Npu)

    private val q3 = RepoFile("Qwen3.5-4B-Q3_K_M.gguf", "Q3_K_M", 1_900_000_000)
    private val q4 = RepoFile("Qwen3.5-4B-Q4_K_M.gguf", "Q4_K_M", 2_740_937_888)
    private val q8 = RepoFile("Qwen3.5-4B-Q8_0.gguf", "Q8_0", 4_500_000_000)
    private val bf16 = RepoFile("Qwen3.5-4B-BF16.gguf", "BF16", 8_400_000_000)
    private val repoFiles = listOf(bf16, q8, q4, q3)

    /** Qwen3.5 4B, as the catalog ships it: its image reader counts in every size. */
    private val deep = ModelEntry.of(Catalog.picks.last())
    private val reader = 675_569_344L
    private val deepQ4 = HfModel.parse("unsloth/Qwen3.5-4B-GGUF:Q4_K_M")
    private val whole = 2_740_937_888L + reader

    private val main = UnconfinedTestDispatcher()

    @Before fun setUpMain() = Dispatchers.setMain(main)

    @After fun stop() {
        shutDown(main, viewModels)
    }

    private fun viewModel(
        row: ModelEntry = deep,
        startTier: SizeTier? = null,
        onExactFit: (FitView) -> Unit = {},
    ): ModelSheetViewModel =
        ModelSheetViewModel(row, hub, models, { phone }, onExactFit, startTier, EmptyCoroutineContext)
            .also { viewModels += it }

    private fun cached(files: List<RepoFile>) = Cached(files, false, null)

    /** The card as drawn: present once every local read has answered. */
    private val ModelSheetViewModel.now: CardView get() = checkNotNull(view.value) { "the card isn't drawn yet" }

    private val offline = IOException("offline")

    /** Every value [vm]'s view takes from its first, while [block] runs. */
    private fun TestScope.values(vm: ModelSheetViewModel, block: () -> Unit = {}): List<CardView> {
        val seen = mutableListOf<CardView>()
        val job =
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                vm.view.filterNotNull().collect {
                    seen +=
                        it
                }
            }
        block()
        job.cancel()
        return seen
    }

    @Test fun aDownloadsFirstValueNamesItsQuantAndTheRecordsTotal() =
        runTest {
            // after a restart: the queue and the hub's state are local at once; the record and the files land later
            models.queue.value = listOf(deepQ4)
            models.status(deepQ4).value = DownloadStatus.Partial(900_000_000, 0)
            val row = ModelEntry.downloading(deepQ4, DownloadRecord(deepQ4, "Qwen3.5 4B", "Qwen", 3_400_000_000))
            val vm = viewModel(row)
            val first = vm.now
            val foot = first.foot as CardFoot.Download
            assertEquals("Q4_K_M", foot.view.quant)
            assertEquals(DownloadStage.Paused, foot.view.stage)
            assertEquals("0.9 of 3.4 GB", foot.view.detail)
            // the ring panel alone describes a download (D119): a state line beside it would say the same twice
            assertNull(first.state.text)
            val all =
                values(vm) {
                    models.recorded.value = mapOf(deepQ4 to DownloadRecord(deepQ4, "Qwen3.5 4B", "Qwen", 3_400_000_000))
                    hub.files.value = cached(repoFiles)
                    models.status(deepQ4).value = DownloadStatus.Downloading(1_000_000_000, 0, null, null)
                }
            for (view in all) {
                assertEquals("Q4_K_M", (view.foot as CardFoot.Download).view.quant)
                assertNull(view.state.text)
            }
        }

    @Test fun withTheFilesAndThePageCachedTheFirstValueIsComplete() =
        runTest {
            hub.files.value = cached(repoFiles)
            hub.page.value = Cached(PageFacts("# Qwen3.5-4B\n\nThe latest generation.", null), false, null)
            val view = viewModel().now
            val choice = checkNotNull(view.choice)
            assertEquals(listOf("Q3_K_M", "Q4_K_M", "Q8_0"), choice.tiers.map { it.quant })
            assertTrue(choice.tiers.all { it.fit != null && it.bytes != null })
            assertEquals("Q4_K_M", choice.selected)
            assertEquals(Fits.quick(whole, q4.bytes, phone), view.header.fit)
            assertEquals(CardFoot.Get("Get Q4_K_M · 3.4 GB", enabled = true, time = "about 8 min"), view.foot)
            assertEquals("Not on this phone", view.state.text)
            // the heading that repeats the name is dropped; the page starts on its text
            val page = view.page as PageView.Markdown
            assertTrue(page.blocks.blocks.first() is MarkdownBlock.Paragraph)
        }

    @Test fun offlineWithNothingCachedTheChoiceKeepsItsShapeThenFailsInPlace() =
        runTest {
            val vm = viewModel(ModelEntry.of(Catalog.picks.last()).copy(catalog = null, size = null))
            val reserved = checkNotNull(vm.now.choice)
            assertEquals(SizeTier.entries, reserved.tiers.map { it.tier })
            assertTrue(reserved.tiers.all { it.reserved })
            assertNull(reserved.notice)
            assertEquals(PageView.Unknown, vm.now.page)
            hub.files.value = Cached(null, false, offline)
            hub.page.value = Cached(null, false, offline)
            hub.facts.value = Cached(null, false, offline)
            // one notice in the choice's place; the page says nothing of its own
            val failed = checkNotNull(vm.now.choice)
            assertEquals(ChoiceNotice.Failed(retryable = true), failed.notice)
            assertEquals(PageView.Failed(retryable = true), vm.now.page)
            assertFalse((vm.now.foot as CardFoot.Get).enabled)
            hub.asked.clear()
            // its one Try again asks again for everything that failed
            vm.retry()
            assertEquals(listOf("facts", "files", "page"), hub.asked.sorted())
        }

    @Test fun tryAgainAsksOnlyForWhatFailed() =
        runTest {
            hub.files.value = cached(repoFiles)
            hub.page.value = Cached(null, false, offline)
            val vm = viewModel()
            hub.asked.clear()
            vm.retry()
            assertEquals(listOf("page"), hub.asked)
        }

    @Test fun theCardAsksForEachRefreshOnceWhenItOpens() =
        runTest {
            viewModel()
            assertEquals(listOf("facts", "files", "page"), hub.asked.sorted())
        }

    @Test fun getNamesTheSelectedTiersQuantAndSize() =
        runTest {
            hub.files.value = cached(repoFiles)
            val vm = viewModel()
            vm.select("Q3_K_M")
            // one formula in both modes: the quant, the whole size, the time
            assertEquals(CardFoot.Get("Get Q3_K_M · 2.6 GB", enabled = true, time = "about 6 min"), vm.now.foot)
            vm.setMode(SheetMode.Advanced)
            assertEquals(CardFoot.Get("Get Q3_K_M · 2.6 GB", enabled = true, time = "about 6 min"), vm.now.foot)
            val key = vm.get()
            assertEquals(HfModel.parse("unsloth/Qwen3.5-4B-GGUF:Q3_K_M"), key)
            assertEquals(
                DownloadRecord(key!!, "Qwen3.5 4B", deep.maker, q3.bytes + reader, q3.bytes),
                models.got.single().second,
            )
        }

    @Test fun aSheetOpenedOnSmallerStartsThere() =
        runTest {
            hub.files.value = cached(repoFiles)
            val vm = viewModel(startTier = SizeTier.Smaller)
            assertEquals(
                "Q3_K_M",
                vm.now.choice
                    ?.selected,
            )
        }

    @Test fun aQuantPickedInAdvancedGivesWayToRecommendedInBasic() =
        runTest {
            hub.files.value = cached(repoFiles)
            val vm = viewModel()
            vm.setMode(SheetMode.Advanced)
            vm.select("BF16")
            assertEquals(
                "BF16",
                vm.now.choice
                    ?.selected,
            )
            vm.setMode(SheetMode.Basic)
            assertEquals(
                "Q4_K_M",
                vm.now.choice
                    ?.selected,
            )
        }

    @Test fun advancedListsEveryQuantWithItsWordsAndTheImageReader() =
        runTest {
            hub.files.value = cached(repoFiles)
            val choice = checkNotNull(viewModel().now.choice)
            assertEquals(listOf("Q3_K_M", "Q4_K_M", "Q8_0", "BF16"), choice.quants.map { it.quant })
            assertEquals(listOf("Q4_K_M"), choice.quants.filter { it.recommended }.map { it.quant })
            assertEquals(q8.bytes + reader, choice.quants[2].bytes)
            assertEquals(reader, choice.readerBytes)
        }

    @Test fun aTierTheUploadLacksStaysAsADisabledRow() =
        runTest {
            hub.files.value = cached(listOf(q4))
            val tiers = checkNotNull(viewModel().now.choice).tiers
            assertEquals(listOf(true, false, true), tiers.map { it.missing })
            assertEquals("Q4_K_M", tiers[1].quant)
        }

    @Test fun aRepoWithNoRunnableFileSaysSo() =
        runTest {
            hub.files.value = cached(emptyList())
            assertEquals(
                ChoiceNotice.NoFile,
                viewModel()
                    .now.choice
                    ?.notice,
            )
        }

    @Test fun aModelThatCantChatHasNoChoiceAndSaysWhy() =
        runTest {
            hub.files.value = cached(repoFiles)
            val view = viewModel(ModelsPreviewData.embedder).now
            assertNull(view.choice)
            assertEquals(CardFoot.CantChat, view.foot)
            assertEquals("Not for chat: this is an embedding model", view.state.text)
            assertNull(viewModels.single().get())
        }

    @Test fun aModelOnThePhoneOffersAChatAndAnotherSize() =
        runTest {
            hub.files.value = cached(repoFiles)
            models.shelf.value = listOf(LibraryModel(deepQ4, "Qwen3.5 4B", whole, null, false))
            val vm = viewModel()
            assertEquals("On this phone · Q4_K_M · 3.4 GB", vm.now.state.text)
            assertEquals(CardFoot.OnPhone(another = true), vm.now.foot)
            assertNull(vm.now.choice)
            vm.getAnotherSize()
            val choice = checkNotNull(vm.now.choice)
            // the size on the phone is marked and can't be got again: another is selected
            assertTrue(choice.tiers.single { it.quant == "Q4_K_M" }.onPhone)
            assertEquals("Q3_K_M", choice.selected)
            assertEquals(CardFoot.Get("Get Q3_K_M · 2.6 GB", enabled = true, time = "about 6 min"), vm.now.foot)
        }

    /** The size on the phone shows the file on the phone's own size, in the state line and in its tier. */
    @Test fun aSizeOnThePhoneShowsTheFileOnThePhonesSizeEverywhere() =
        runTest {
            // the listing's reader differs from what is here; the phone's own file is the one size
            hub.files.value = cached(repoFiles.map { if (it == q4) it.copy(encoderBytes = 460_000_000) else it })
            val here = LibraryModel(deepQ4, "Qwen3.5 4B", 2_740_937_888, null, false, readerBytes = 100_000_000)
            models.shelf.value = listOf(here)
            val vm = viewModel()
            assertEquals("On this phone · Q4_K_M · 2.8 GB", vm.now.state.text)
            vm.getAnotherSize()
            val choice = checkNotNull(vm.now.choice)
            assertEquals(here.totalBytes, choice.tiers.single { it.quant == "Q4_K_M" }.bytes)
            assertEquals(here.totalBytes, choice.quants.single { it.quant == "Q4_K_M" }.bytes)
            // and its fit is the switcher's and the row's, from the same file
            assertEquals(Fits.quick(here, phone), vm.now.header.fit)
        }

    /** A size not yet known is a placeholder, never the selected one. */
    @Test fun placeholdersAreNeverSelected() =
        runTest {
            val vm =
                viewModel(ModelEntry.of(SearchModel("x", "Some Model", "someone/x-GGUF", "someone", 1, false, null)))
            val choice = checkNotNull(vm.now.choice)
            assertTrue(choice.tiers.all { it.reserved })
            assertNull(choice.selected)
        }

    @Test fun anotherSizesChoiceSaysWhatIsHereAndDoneGoesBack() =
        runTest {
            hub.files.value = cached(repoFiles)
            models.shelf.value = listOf(LibraryModel(deepQ4, "Qwen3.5 4B", whole, null, false))
            val vm = viewModel()
            vm.getAnotherSize()
            // why another size is selected, and a quiet way back
            assertEquals("Q4_K_M", vm.now.choice?.alreadyHere)
            assertTrue(
                "the file here can't be picked in Advanced either",
                vm.now.choice!!
                    .quants
                    .single {
                        it.quant ==
                            "Q4_K_M"
                    }.onPhone,
            )
            vm.doneChoosing()
            assertNull(vm.now.choice)
            assertEquals(CardFoot.OnPhone(another = true), vm.now.foot)
        }

    @Test fun theCardWaitsForThePhonesFactsSoItsFitIsInItsFirstFrame() =
        runTest {
            // the fit line landed ~300 ms after the sheet showed
            hub.files.value = cached(repoFiles)
            val facts = CompletableDeferred<DeviceFacts>()
            val vm =
                ModelSheetViewModel(deep, hub, models, { facts.await() }, {}, null, EmptyCoroutineContext)
                    .also { viewModels += it }
            assertNull("no card before the facts", vm.view.value)
            facts.complete(phone)
            assertNotNull(vm.now.header.fit)
        }

    @Test fun theCardIsntDrawnUntilTheLibraryHasBeenRead() =
        runTest {
            // the library is read from disk after the card opens: nothing shows until it answers, then it is final
            models.shelf.value = null
            val vm = viewModel(ModelEntry.of(LibraryModel(deepQ4, "Qwen3.5 4B", whole, null, false)))
            assertNull(vm.view.value)
            models.shelf.value = listOf(LibraryModel(deepQ4, "Qwen3.5 4B", whole, null, false))
            assertEquals(CardFoot.OnPhone(another = true), vm.now.foot)
        }

    @Test fun theFirstDrawnCardIsCompleteWhenTheCacheAnswersLate() =
        runTest {
            // Room answers after composition: each read lags, in a different order
            hub.lag = LAG / 2
            models.lag = LAG / 4
            hub.files.value = cached(repoFiles)
            hub.page.value = Cached(PageFacts("# Qwen3.5-4B\n\nThe latest generation.", null), false, null)
            hub.facts.value = Cached(searchRow(maker = "Qwen"), false, null)
            val vm = viewModel(deep.copy(maker = null))
            assertNull(vm.view.value)
            val drawn =
                values(vm) {
                    advanceTimeBy(LAG)
                    runCurrent()
                }
            val first = drawn.first()
            assertTrue(first.choice!!.tiers.none { it.reserved })
            assertEquals("Q4_K_M", first.choice!!.selected)
            assertTrue(first.page is PageView.Markdown)
            assertEquals("Alibaba Qwen", first.header.maker)
            assertEquals(CardFoot.Get("Get Q4_K_M · 3.4 GB", enabled = true, time = "about 8 min"), first.foot)
            assertEquals(listOf(first), drawn)
        }

    @Test fun theMakersLineIsHeldWhileAMakerMayStillArrive() =
        runTest {
            val vm = viewModel(deep.copy(maker = null))
            assertTrue(vm.now.header.makerReserved)
            hub.facts.value = Cached(searchRow(maker = "Qwen"), false, null)
            assertEquals("Alibaba Qwen", vm.now.header.maker)
        }

    @Test fun aMakerKnownAbsentBeforeTheCardShowsHasNoLine() =
        runTest {
            hub.facts.value = Cached(null, false, offline)
            hub.page.value = Cached(PageFacts("Text", null), false, null)
            val header = viewModel(deep.copy(maker = null)).now.header
            assertNull(header.maker)
            assertFalse(header.makerReserved)
        }

    @Test fun aMakersLineHeldOnceDrawnStaysWhenNoneArrives() =
        runTest {
            val vm = viewModel(deep.copy(maker = null))
            assertTrue(vm.now.header.makerReserved)
            hub.facts.value = Cached(null, false, offline)
            hub.page.value = Cached(PageFacts("Text", null), false, null)
            assertTrue(vm.now.header.makerReserved)
        }

    @Test fun thePanelReadsTheRecordsTotalWithNoStateLine() =
        runTest {
            // the hub's total (every file it plans) differs from the size chosen: the record's wins in both places
            models.queue.value = listOf(deepQ4)
            models.recorded.value = mapOf(deepQ4 to DownloadRecord(deepQ4, "Qwen3.5 4B", "Qwen", 3_400_000_000))
            models.status(deepQ4).value = DownloadStatus.Downloading(1_000_000_000, 3_600_000_000, null, null)
            val view = viewModel().now
            assertNull(view.state.text)
            assertEquals("1.0 of 3.4 GB", (view.foot as CardFoot.Download).view.detail)
        }

    @Test fun aCardOpenedOnSmallerOffersNoGetUntilTheListingNamesItsQuant() =
        runTest {
            val vm = viewModel(startTier = SizeTier.Smaller)
            // the catalog knows only Recommended's file: Get waits rather than name Q4_K_M and then change
            assertEquals(CardFoot.Get("Get", enabled = false), vm.now.foot)
            hub.files.value = cached(repoFiles)
            assertEquals(CardFoot.Get("Get Q3_K_M · 2.6 GB", enabled = true, time = "about 6 min"), vm.now.foot)
        }

    @Test fun aCatalogCardOffersItsOwnFileBeforeTheListing() =
        runTest {
            assertEquals(
                CardFoot.Get("Get Q4_K_M · 3.4 GB", enabled = true, time = "about 8 min"),
                viewModel().now.foot,
            )
        }

    @Test fun aCatalogCardOffersItsOwnFileWhenTheListingFailed() =
        runTest {
            // a newcomer can start offline; the download waits for the network (first run's behaviour)
            val vm = viewModel()
            hub.files.value = Cached(null, false, offline)
            // the catalog's own size is the Recommended tier, shown and selected; only the
            // other sizes need the network
            assertEquals(
                "the choice says the other sizes need the network",
                ChoiceNotice.OtherSizes(retryable = true),
                vm.now.choice?.notice,
            )
            val recommended =
                vm.now.choice!!
                    .tiers
                    .single { it.tier == SizeTier.Recommended }
            assertEquals("Q4_K_M", recommended.quant)
            assertEquals(whole, recommended.bytes)
            assertEquals("Q4_K_M", vm.now.choice!!.selected)
            assertTrue(
                vm.now.choice!!
                    .tiers
                    .filter { it.tier != SizeTier.Recommended }
                    .all { it.quant == null && it.note == "Needs the network" },
            )
            assertEquals(CardFoot.Get("Get Q4_K_M · 3.4 GB", enabled = true, time = "about 8 min"), vm.now.foot)
            assertEquals(deepQ4, vm.get())
            assertEquals(deepQ4, models.got.single().first)
            assertEquals(
                whole,
                models.got
                    .single()
                    .second
                    ?.totalBytes,
            )
        }

    @Test fun withTwoQuantsQueuedTheCardFollowsItsRowsFile() =
        runTest {
            val deepQ8 = HfModel.parse("unsloth/Qwen3.5-4B-GGUF:Q8_0")
            models.queue.value = listOf(deepQ4, deepQ8)
            models.status(deepQ8).value = DownloadStatus.Partial(10_000_000, q8.bytes)
            val vm = viewModel(ModelEntry.downloading(deepQ8, null))
            assertEquals("Q8_0", (vm.now.foot as CardFoot.Download).view.quant)
            vm.cancel()
            assertEquals(listOf(deepQ8), models.cancelled)
        }

    @Test fun theCardFollowsADownloadLandingWhileOpen() =
        runTest {
            models.queue.value = listOf(deepQ4)
            models.status(deepQ4).value = DownloadStatus.Downloading(1_000_000_000, whole, null, 300)
            val vm = viewModel()
            assertTrue(vm.now.foot is CardFoot.Download)
            models.queue.value = emptyList()
            models.shelf.value = listOf(LibraryModel(deepQ4, "Qwen3.5 4B", whole, null, false))
            assertEquals(CardFoot.OnPhone(another = true), vm.now.foot)
        }

    @Test fun aStoppedDownloadSaysWhatStoppedAndTriesAgain() =
        runTest {
            models.queue.value = listOf(deepQ4)
            models.status(deepQ4).value =
                DownloadStatus.Failed(DownloadFailure(DownloadFailure.Kind.Network, retryable = true))
            val vm = viewModel()
            val panel = (vm.now.foot as CardFoot.Download).view
            assertEquals(DownloadStage.Stopped, panel.stage)
            assertEquals("The connection dropped.", panel.detail)
            vm.toggle()
            assertEquals(deepQ4, models.got.single().first)
        }

    @Test fun theRingPausesADownloadAndResumesAPausedOne() =
        runTest {
            models.queue.value = listOf(deepQ4)
            models.status(deepQ4).value = DownloadStatus.Downloading(1_000_000_000, whole, null, 300)
            val vm = viewModel()
            vm.toggle()
            assertEquals(listOf(deepQ4), models.paused)
            models.status(deepQ4).value = DownloadStatus.Partial(1_000_000_000, whole)
            vm.toggle()
            assertEquals(listOf(deepQ4), models.got.map { it.first })
        }

    @Test fun aWaitingDownloadNamesTheOneAhead() =
        runTest {
            val gemma = HfModel.parse("unsloth/gemma-3-4b-it-GGUF:Q4_K_M")
            models.queue.value = listOf(deepQ4, gemma)
            models.recorded.value = mapOf(gemma to DownloadRecord(gemma, "Gemma 3 4B", "Google", 3_000_000_000))
            models.status(gemma).value = DownloadStatus.Downloading(1, 3_000_000_000, null, null)
            models.running.value = gemma
            val panel = (viewModel().now.foot as CardFoot.Download).view
            assertEquals(DownloadStage.Waiting, panel.stage)
            assertEquals("after Gemma 3 4B", panel.detail)
        }

    @Test fun aWaitingRowNamesTheRunningModelWhileItsOwnStatusIsAbsent() =
        runTest {
            val gemma = HfModel.parse("unsloth/gemma-3-4b-it-GGUF:Q4_K_M")
            models.queue.value = listOf(gemma, deepQ4)
            models.recorded.value = mapOf(gemma to DownloadRecord(gemma, "Gemma 3 4B", "Google", 3_000_000_000))
            models.status(deepQ4).value = DownloadStatus.Absent
            models.running.value = gemma
            val panel = (viewModel().now.foot as CardFoot.Download).view
            assertEquals("after Gemma 3 4B", panel.detail)
        }

    @Test fun aDownloadingStatusWithNothingRunningNamesNoOneAhead() =
        runTest {
            models.queue.value = listOf(deepQ4)
            models.status(deepQ4).value = DownloadStatus.Downloading(1, 3_000_000_000, null, null)
            models.running.value = null
            val panel = (viewModel().now.foot as CardFoot.Download).view
            assertFalse(panel.detail.orEmpty().contains("after"))
        }

    @Test fun cancelPastThreeHundredMegabytesAsksFirst() =
        runTest {
            models.queue.value = listOf(deepQ4)
            models.status(deepQ4).value = DownloadStatus.Downloading(310_000_000, whole, null, null)
            val vm = viewModel()
            assertEquals(CancelAsk.Confirm("0.3 of 3.4 GB has arrived. Cancelling deletes it."), vm.cancel())
            assertEquals("0.3 of 3.4 GB has arrived. Cancelling deletes it.", vm.asking.value)
            assertTrue(models.cancelled.isEmpty())
            vm.keepDownload()
            assertNull(vm.asking.value)
            assertTrue(models.cancelled.isEmpty())
            vm.cancel()
            vm.confirmCancel()
            assertEquals(listOf(deepQ4), models.cancelled)
            assertNull(vm.asking.value)
        }

    /** Final review, Minor 6: confirming cancels the download the dialog named, never one that started since. */
    @Test fun confirmingCancelsOnlyTheDownloadTheQuestionNamed() =
        runTest {
            val deepQ8 = HfModel.parse("unsloth/Qwen3.5-4B-GGUF:Q8_0")
            models.queue.value = listOf(deepQ4)
            models.status(deepQ4).value = DownloadStatus.Downloading(310_000_000, whole, null, null)
            val vm = viewModel()
            assertTrue(vm.cancel() is CancelAsk.Confirm)
            // while the dialog is open, Q4_K_M lands and Q8_0 of the same repo starts
            models.status(deepQ4).value = DownloadStatus.Complete
            models.queue.value = listOf(deepQ8)
            models.status(deepQ8).value = DownloadStatus.Downloading(400_000_000, q8.bytes, null, null)
            assertEquals("Q8_0", (vm.now.foot as CardFoot.Download).view.quant)
            vm.confirmCancel()
            assertTrue(models.cancelled.isEmpty())
            assertNull(vm.asking.value)
        }

    @Test fun cancelUnderThreeHundredMegabytesDoesntAsk() =
        runTest {
            models.queue.value = listOf(deepQ4)
            models.status(deepQ4).value = DownloadStatus.Partial(200_000_000, whole)
            val vm = viewModel()
            assertEquals(CancelAsk.Done, vm.cancel())
            assertEquals(listOf(deepQ4), models.cancelled)
        }

    @Test fun aCancelThatCantStopTheWorkSaysSo() =
        runTest {
            models.cancels = false
            models.queue.value = listOf(deepQ4)
            models.status(deepQ4).value = DownloadStatus.Partial(0, whole)
            val vm = viewModel()
            vm.cancel()
            assertEquals("Couldn't cancel the download of Qwen3.5 4B. Try again.", vm.problem.value)
            vm.dismissProblem()
            assertNull(vm.problem.value)
        }

    @Test fun theMakerComesFromTheRowElseTheOriginalsOwner() =
        runTest {
            val row = deep.copy(maker = null)
            val vm = viewModel(row)
            assertNull(vm.now.header.maker)
            hub.facts.value = Cached(null, false, offline)
            hub.page.value = Cached(PageFacts(null, "Qwen/Qwen3.5-4B"), false, null)
            assertEquals("Alibaba Qwen", vm.now.header.maker)
            assertEquals(PageView.None, vm.now.page)
        }

    /** A download's recorded maker goes through the one map, as every other maker does. */
    @Test fun aRecordedMakerReadsAsTheMap() =
        runTest {
            val upload = HfModel.parse("Qwen/Qwen2.5-Coder-7B-Instruct-GGUF:Q4_K_M")
            models.queue.value = listOf(upload)
            models.status(upload).value = DownloadStatus.Partial(100_000_000, 0)
            models.recorded.value = mapOf(upload to DownloadRecord(upload, "Qwen2.5 Coder 7B", "Qwen", 4_700_000_000))
            val row = ModelEntry.ofKey(upload, "Qwen2.5 Coder 7B")
            assertEquals("Alibaba Qwen", viewModel(row).now.header.maker)
        }

    @Test fun theRecommendedFitIsReportedOnce() =
        runTest {
            val heard = mutableListOf<FitView>()
            val vm = viewModel(onExactFit = { heard += it })
            hub.files.value = cached(repoFiles)
            vm.select("Q3_K_M")
            hub.files.value = cached(repoFiles + RepoFile("x-Q5_K_M.gguf", "Q5_K_M", 3_000_000_000))
            assertEquals(listOf(Fits.quick(whole, q4.bytes, phone)), heard)
        }

    @Test fun aMixtureOfExpertsFitCountsTheActiveShareForSpeed() =
        runTest {
            hub.files.value = cached(repoFiles)
            val row = deep.copy(catalog = null, parameters = 30_000_000_000, activeParameters = 3_000_000_000)
            val tier =
                viewModel(row)
                    .now.choice!!
                    .tiers[1]
            assertEquals(Fits.quick(q4.bytes, q4.bytes / 10, phone), tier.fit)
        }

    @Test fun theHeaderNamesTheRowsCapabilities() =
        runTest {
            val header = viewModel().now.header
            assertEquals("Qwen3.5 4B", header.name)
            assertEquals("unsloth/Qwen3.5-4B-GGUF", header.repo)
            assertEquals(deep.capabilities, header.capabilities)
            assertNotNull(deep.maker)
        }

    private fun searchRow(maker: String?) =
        SearchModel("qwen3.5-4b", "Qwen3.5 4B", deep.repo, "unsloth", 10, sees = true, catalog = null, maker = maker)

    private companion object {
        const val LAG = 100L
    }
}
