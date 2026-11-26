package com.suhel.llamabro.demo.ui.server

import androidx.lifecycle.ViewModel
import com.suhel.llamabro.demo.models.LibraryModel
import com.suhel.llamabro.demo.server.LogLine
import com.suhel.llamabro.demo.server.ServingState
import com.suhel.llamabro.demo.server.Thermal
import com.suhel.llamabro.demo.ui.keepAlive
import com.suhel.llamabro.demo.ui.shutDown
import com.suhel.llamabro.hub.HfModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.Locale

/** The Server page's state (docs/design/server.md §3) over a fake server mode, and what its buttons do. */
@OptIn(ExperimentalCoroutinesApi::class)
class ServerViewModelTest {
    private val main = UnconfinedTestDispatcher()
    private val background = CoroutineScope(SupervisorJob() + main)
    private val viewModels = mutableListOf<ViewModel>()

    @Before fun setUp() = Dispatchers.setMain(main)

    @After fun tearDown() {
        background.cancel()
        shutDown(main, viewModels)
    }

    private val qwen = HfModel.parse("unsloth/Qwen3.5-4B-GGUF:Q4_K_M")
    private val gemma = HfModel.parse("unsloth/gemma-3-4b-it-qat-GGUF:Q4_K_M")
    private val library =
        MutableStateFlow(
            listOf(
                LibraryModel(qwen, "Qwen3.5 4B", 2_740_000_000, lastUsed = null, isDefault = false),
                LibraryModel(gemma, "Gemma 3 4B", 3_341_145_472, lastUsed = null, isDefault = true),
            ),
        )
    private val server = FakeServer()
    private val addresses = MutableStateFlow(listOf("192.168.1.20"))
    private val charging = MutableStateFlow(true)
    private val thermal = MutableStateFlow<Thermal?>(Thermal(status = 1, headroom = 0.62f))

    private class FakeServer : ServerControl {
        override val state = MutableStateFlow<ServingState>(ServingState.Off)
        override val served = MutableStateFlow<HfModel?>(null)
        override val log = MutableStateFlow<List<LogLine>>(emptyList())
        override val key = MutableStateFlow<String?>("k3y_-AbC")
        override val busy = MutableStateFlow(0)
        val started = mutableListOf<Pair<HfModel, Int>>()
        var stops = 0
        var renewals = 0

        override suspend fun start(model: HfModel, slots: Int) {
            started += model to slots
        }

        override suspend fun stop() {
            stops++
        }

        override suspend fun renewKey() {
            renewals++
            key.value = "n3w-k3y"
        }
    }

    private fun viewModel(): ServerViewModel =
        ServerViewModel(
            server,
            library,
            PhoneFacts(addresses, charging, thermal),
            background,
            zone = ZoneOffset.UTC,
            locale = Locale.US,
        ).also(viewModels::add)

    private fun ServerViewModel.page(): ServerPage? = page.keepAlive(background).value

    private fun serving(slots: Int = 2) {
        server.served.value = qwen
        server.state.value = ServingState.Serving(qwen, 8080, slots)
    }

    private val noon = LocalDateTime.of(2026, 11, 26, 12, 4, 51).toInstant(ZoneOffset.UTC).toEpochMilli()

    @Test fun offShowsDownloadedModels() {
        val off = viewModel().page() as ServerPage.Off
        assertEquals(listOf("Qwen3.5 4B", "Gemma 3 4B"), off.models.map { it.name })
        assertEquals(listOf("Q4_K_M · 2.7 GB", "Q4_K_M · 3.3 GB"), off.models.map { it.detail })
        // the model for new chats is chosen until the person picks another
        assertEquals(gemma, off.chosen)
        assertEquals(1, off.slots)
        assertEquals(listOf(1, 2, 4), off.slotChoices)
    }

    @Test fun offStartsTheChosenModelWithTheChosenSlots() {
        val vm = viewModel()
        vm.choose(qwen)
        vm.setSlots(4)
        val off = vm.page() as ServerPage.Off
        assertEquals(qwen, off.chosen)
        assertEquals(4, off.slots)
        vm.start()
        assertEquals(listOf(qwen to 4), server.started)
    }

    @Test fun offWithNoModelsHasNothingToStart() {
        library.value = emptyList()
        val vm = viewModel()
        val off = vm.page() as ServerPage.Off
        assertTrue(off.models.isEmpty())
        assertNull(off.chosen)
        vm.start()
        assertTrue(server.started.isEmpty())
    }

    @Test fun startingShowsProgress() {
        val vm = viewModel()
        server.served.value = qwen
        server.state.value = ServingState.Starting()
        assertEquals(ServerPage.Starting("Qwen3.5 4B", null), vm.page())
        server.state.value = ServingState.Starting(0.4f)
        assertEquals(ServerPage.Starting("Qwen3.5 4B", 0.4f), vm.page())
    }

    @Test fun servingShowsUrlKeyAndLog() {
        val vm = viewModel()
        serving()
        addresses.value = listOf("10.0.0.7", "192.168.1.20")
        server.log.value =
            listOf(
                LogLine(noon, "192.168.1.12", "POST", "/v1/messages", 200, 14_100, 1_912, 233),
                LogLine(noon - 1_000, "192.168.1.12", null, "/v1/models", 401, null, null, null),
            )
        val page = vm.page() as ServerPage.Serving
        assertEquals("Qwen3.5 4B", page.name)
        assertEquals(listOf("http://10.0.0.7:8080", "http://192.168.1.20:8080"), page.urls)
        assertEquals(ServerFormat.MASK, page.key.shown)
        assertEquals("k3y_-AbC", page.key.copy)
        assertEquals(2, page.slots)
        assertEquals(0, page.busy)
        // the last request's predicted tokens over its time
        assertEquals("16.5 tok/s", page.speed)
        assertEquals(
            listOf(
                LogRow("POST /v1/messages", "12:04:51 · 192.168.1.12 · 1,912 in / 233 out · 14.1 s", "200"),
                LogRow("/v1/models", "12:04:50 · 192.168.1.12 · turned away", "401"),
            ),
            page.log,
        )
        assertEquals("Lightly throttled · 62% of the way to heavy throttling", page.heat)
        assertTrue(page.charging)
        assertEquals(ConnectTool.entries, page.snippets.map { it.tool })
    }

    @Test fun servingWithNoRequestYetHasNoSpeed() {
        val vm = viewModel()
        serving()
        server.log.value = listOf(LogLine(noon, "192.168.1.12", null, "/v1/models", 401, null, null, null))
        assertNull((vm.page() as ServerPage.Serving).speed)
    }

    @Test fun servingWithNoWifiAddressHasNoUrlOrQr() {
        val vm = viewModel()
        serving()
        addresses.value = emptyList()
        val page = vm.page() as ServerPage.Serving
        assertTrue(page.urls.isEmpty())
        assertNull(page.qr)
        assertTrue(page.snippets.isEmpty())
    }

    @Test fun servingSaysWhenThePhoneIsNotCharging() {
        val vm = viewModel()
        serving()
        charging.value = false
        assertFalse((vm.page() as ServerPage.Serving).charging)
    }

    @Test fun heatWithoutHeadroomIsTheStatusAlone() {
        val vm = viewModel()
        serving()
        thermal.value = Thermal(status = 0, headroom = null)
        assertEquals("Not throttled", (vm.page() as ServerPage.Serving).heat)
        thermal.value = null
        assertNull((vm.page() as ServerPage.Serving).heat)
    }

    @Test fun keyIsMaskedUntilRevealed() {
        val vm = viewModel()
        serving()
        val masked = vm.page() as ServerPage.Serving
        assertFalse(masked.key.revealed)
        assertEquals(ServerFormat.MASK, masked.key.shown)
        masked.snippets.forEach { snippet ->
            assertFalse(snippet.shown, "k3y_-AbC" in snippet.shown)
            // copying a snippet copies the key itself
            assertTrue(snippet.copy, "k3y_-AbC" in snippet.copy)
        }

        vm.reveal(true)
        val revealed = vm.page() as ServerPage.Serving
        assertTrue(revealed.key.revealed)
        assertEquals("k3y_-AbC", revealed.key.shown)
        revealed.snippets.forEach { assertTrue(it.shown, "k3y_-AbC" in it.shown) }

        vm.reveal(false)
        assertEquals(ServerFormat.MASK, (vm.page() as ServerPage.Serving).key.shown)
    }

    @Test fun renewAndStopGoToServerMode() {
        val vm = viewModel()
        serving()
        vm.renewKey()
        assertEquals(1, server.renewals)
        assertEquals("n3w-k3y", (vm.page() as ServerPage.Serving).key.copy)
        vm.stop()
        assertEquals(1, server.stops)
    }

    @Test fun failedOffersRetry() {
        val vm = viewModel()
        vm.choose(qwen)
        vm.setSlots(2)
        server.state.value = ServingState.Failed("Address already in use")
        assertEquals(ServerPage.Failed("Address already in use"), vm.page())
        vm.retry()
        assertEquals(listOf(qwen to 2), server.started)
    }

    @Test fun theOffPageComesBackAfterAStop() {
        val vm = viewModel()
        serving()
        assertTrue(vm.page() is ServerPage.Serving)
        server.served.value = null
        server.state.value = ServingState.Off
        assertTrue(vm.page() is ServerPage.Off)
    }

    @Test fun busyFollowsTheRequestsUnderWay() {
        val vm = viewModel()
        serving()
        server.busy.value = 1
        val page = vm.page() as ServerPage.Serving
        assertEquals(1, page.busy)
        assertEquals("1 of 2 busy", ServerFormat.busy(page.busy, page.slots))
    }

    @Test fun qrHiddenWhileKeyMasked() {
        val vm = viewModel()
        serving()
        val page = vm.page() as ServerPage.Serving
        // the code holds the key itself: a camera or a screenshot would read what the mask hides
        assertNull(page.qr)
        assertTrue(page.canShowQr)
    }

    @Test fun revealResetsWhenServingStops() {
        val vm = viewModel()
        serving()
        vm.reveal(true)
        assertTrue((vm.page() as ServerPage.Serving).key.revealed)
        server.served.value = null
        server.state.value = ServingState.Off
        serving()
        val next = vm.page() as ServerPage.Serving
        assertFalse("the next session opens with the key masked", next.key.revealed)
        assertNull(next.qr)
        // and a restart (Starting again) masks it too
        vm.reveal(true)
        server.state.value = ServingState.Starting()
        serving()
        assertFalse((vm.page() as ServerPage.Serving).key.revealed)
    }

    @Test fun revealShowsKeyAndQr() {
        val vm = viewModel()
        serving()
        vm.reveal(true)
        val shown = vm.page() as ServerPage.Serving
        assertEquals("k3y_-AbC", shown.key.shown)
        assertEquals("{\"url\":\"http://192.168.1.20:8080\",\"key\":\"k3y_-AbC\"}", shown.qr)
        // hiding the key hides the code with it
        vm.reveal(false)
        val hidden = vm.page() as ServerPage.Serving
        assertEquals(ServerFormat.MASK, hidden.key.shown)
        assertNull(hidden.qr)
    }

    @Test fun dockedShowsTokensPerSecondTemperatureSlotsAndLastRequest() {
        val vm = viewModel()
        serving()
        server.busy.value = 1
        server.log.value =
            listOf(
                LogLine(noon, "192.168.1.12", "POST", "/v1/messages", 200, 14_100, 1_912, 233),
                LogLine(noon - 1_000, "192.168.1.12", null, "/v1/models", 401, null, null, null),
            )
        val docked = vm.docked.keepAlive(background).value as Docked.Showing
        assertEquals("16.5", docked.speed)
        assertEquals("1 of 2", docked.busy)
        assertEquals("Light", docked.temperature)
        assertEquals("POST /v1/messages · 200 · 12:04:51", docked.last)
        assertEquals("http://192.168.1.20:8080", docked.url)
    }

    @Test fun dockedBeforeAnyRequestHasDashesAndNoLastRequest() {
        val vm = viewModel()
        serving()
        thermal.value = null
        addresses.value = emptyList()
        val docked = vm.docked.keepAlive(background).value as Docked.Showing
        assertEquals("—", docked.speed)
        assertEquals("0 of 2", docked.busy)
        assertNull(docked.temperature)
        assertNull(docked.last)
        assertNull(docked.url)
    }

    @Test fun servingEndedLeavesDockedInTheViewModel() {
        val vm = viewModel()
        serving()
        val docked = vm.docked.keepAlive(background)
        assertTrue(docked.value is Docked.Showing)
        server.state.value = ServingState.Off
        assertEquals(Docked.Ended, docked.value)
    }
}
