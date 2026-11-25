package com.suhel.llamabro.demo.ui.models

import com.suhel.llamabro.DeviceKind
import com.suhel.llamabro.demo.models.Catalog
import com.suhel.llamabro.demo.models.DeviceFacts
import com.suhel.llamabro.demo.models.LinkTarget
import com.suhel.llamabro.demo.models.SearchModel
import com.suhel.llamabro.demo.ui.shutDown
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.coroutines.EmptyCoroutineContext

/** A link on a model's page (D156): the latest tap alone opens a card or the browser, with a notice meanwhile. */
@OptIn(ExperimentalCoroutinesApi::class)
class ModelSheetViewModelLinksTest {
    private val viewModels = mutableListOf<ModelSheetViewModel>()
    private val phone = DeviceFacts(budgetBytes = 8L shl 30, chip = DeviceKind.Npu)
    private val main = UnconfinedTestDispatcher()

    private val first = "https://huggingface.co/google/gemma-3-4b-it"
    private val second = "https://huggingface.co/Qwen/Qwen3.5-4B"
    private val gemma =
        SearchModel("gemma 3 4b", "gemma-3-4b-it-GGUF", "unsloth/gemma-3-4b-it-GGUF", "unsloth", 10, false, null)
    private val qwen =
        SearchModel("qwen3.5 4b", "Qwen3.5-4B-GGUF", "unsloth/Qwen3.5-4B-GGUF", "unsloth", 10, false, null)

    @Before fun setUpMain() = Dispatchers.setMain(main)

    @After fun stop() {
        shutDown(main, viewModels)
    }

    private fun viewModel(related: suspend (String) -> LinkTarget) =
        ModelSheetViewModel(
            ModelEntry.of(Catalog.picks.last()),
            FakeHubCard(),
            FakeModelLibrary(),
            { phone },
            work = EmptyCoroutineContext,
            related = related,
        ).also { viewModels += it }

    /** Every target [vm] opens while [block] runs. */
    private fun TestScope.opened(vm: ModelSheetViewModel, block: () -> Unit): List<LinkTarget> {
        val seen = mutableListOf<LinkTarget>()
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.opened.collect { seen += it } }
        block()
        job.cancel()
        return seen
    }

    @Test fun aSecondTapCancelsTheFirstLookup() =
        runTest {
            val slow = CompletableDeferred<LinkTarget>()
            val quick = CompletableDeferred<LinkTarget>()
            var firstCancelled = false
            val vm =
                viewModel { url ->
                    if (url == first) {
                        try {
                            slow.await()
                        } finally {
                            firstCancelled = !slow.isCompleted
                        }
                    } else {
                        quick.await()
                    }
                }
            val seen =
                opened(vm) {
                    vm.openLink(first)
                    assertEquals("google/gemma-3-4b-it", vm.opening.value)
                    vm.openLink(second)
                    assertTrue(firstCancelled)
                    // the first lookup's end leaves the second's notice in place
                    assertEquals("Qwen/Qwen3.5-4B", vm.opening.value)
                    slow.complete(LinkTarget.Card(gemma))
                    quick.complete(LinkTarget.Card(qwen))
                }
            assertEquals(listOf(LinkTarget.Card(qwen)), seen)
        }

    @Test fun aBrowserTargetEmitsTheUrl() =
        runTest {
            val vm = viewModel { url -> LinkTarget.Browser(url) }
            val page = "https://huggingface.co/datasets/HuggingFaceFW/fineweb"
            assertEquals(listOf(LinkTarget.Browser(page)), opened(vm) { vm.openLink(page) })
            assertNull(vm.opening.value)
        }

    @Test fun openingClearsWhenDone() =
        runTest {
            val answer = CompletableDeferred<LinkTarget>()
            val vm = viewModel { answer.await() }
            val seen =
                opened(vm) {
                    vm.openLink(first)
                    assertEquals("google/gemma-3-4b-it", vm.opening.value)
                    answer.complete(LinkTarget.Card(gemma))
                    assertNull(vm.opening.value)
                }
            assertEquals(listOf(LinkTarget.Card(gemma)), seen)
        }
}
