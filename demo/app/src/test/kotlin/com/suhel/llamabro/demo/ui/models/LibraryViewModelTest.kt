package com.suhel.llamabro.demo.ui.models

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.viewModelScope
import com.suhel.llamabro.demo.data.SettingsRepository
import com.suhel.llamabro.demo.models.FakeScheduler
import com.suhel.llamabro.demo.models.Format
import com.suhel.llamabro.demo.models.LibraryModel
import com.suhel.llamabro.demo.models.ModelRepository
import com.suhel.llamabro.demo.models.TestHub
import com.suhel.llamabro.demo.ui.keepAlive
import com.suhel.llamabro.demo.ui.shutDown
import com.suhel.llamabro.hub.HfModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.ZoneOffset

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModelTest {
    @get:Rule val folder = TemporaryFolder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val testHub = TestHub()
    private val hub get() = testHub.hub
    private val settings by lazy {
        SettingsRepository(PreferenceDataStoreFactory.create(scope = scope) { File(folder.root, "s.preferences_pb") })
    }
    private val scheduler = FakeScheduler()
    private val models by lazy {
        ModelRepository(
            hub,
            settings,
            scheduler,
            CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        )
    }
    private val viewModels = mutableListOf<LibraryViewModel>()
    private val collectors = CoroutineScope(Dispatchers.Unconfined)
    private val swift = HfModel.parse("unsloth/Qwen3.5-0.8B-GGUF:Q4_K_M")
    private val deep = HfModel.parse("unsloth/Qwen3.5-4B-GGUF:Q4_K_M")
    private val day = 24L * 3600 * 1000
    private val now = 1_800_000_000_000L

    @Before fun main() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After fun stop() {
        viewModels.forEach { it.shutDown() }
        Dispatchers.resetMain()
        collectors.cancel()
        scope.cancel()
        testHub.close()
    }

    private fun viewModel(free: Long = 120_000_000_000L) =
        LibraryViewModel(models, { free }, { now }, ZoneOffset.UTC).also {
            viewModels += it
            it.rows.keepAlive(collectors)
            it.storage.keepAlive(collectors)
            it.downloads.keepAlive(collectors)
        }

    @Test fun storageIsTheModelsAndWhatIsFree() {
        testHub.install(swift)
        testHub.install(deep)
        val onPhone = runBlocking { hub.cached().sumOf { it.sizeBytes } }
        val vm = viewModel(free = 120_000_000_000L)
        val storage = withTimeoutBlocking { vm.storage.first { it != null && it.usedBytes > 0 } }!!
        assertEquals("the models' bytes as the hub counts them", onPhone, storage.usedBytes)
        assertEquals(120_000_000_000L, storage.freeBytes)
    }

    @Test fun anEmptyLibraryHasZeroUsed() {
        val vm = viewModel()
        assertEquals(0L, withTimeoutBlocking { vm.storage.first { it != null } }!!.usedBytes)
    }

    @Test fun askingToRemoveDeletesNothingUntilConfirmed() {
        testHub.install(swift)
        val vm = viewModel()
        val row = withTimeoutBlocking { vm.rows.first { !it.isNullOrEmpty() } }!!.first()
        vm.askRemove(row)
        assertEquals(row, vm.pendingRemove.value)
        assertTrue(testHub.onPhone(swift))
        vm.dismissRemove()
        assertNull(vm.pendingRemove.value)
        assertTrue("dismissing keeps the model", testHub.onPhone(swift))
        vm.askRemove(row)
        vm.confirmRemove()
        withTimeoutBlocking { while (testHub.onPhone(swift)) kotlinx.coroutines.delay(10) }
        assertNull(vm.pendingRemove.value)
    }

    @Test fun lastUseReadsInPlainWords() {
        val vm = viewModel()

        fun model(ago: Long?) =
            LibraryModel(
                swift,
                "Qwen3.5 0.8B",
                1,
                ago?.let {
                    now - it
                },
                false,
            )
        assertEquals("Used today", vm.lastUsed(model(1_000)))
        assertEquals("Yesterday", vm.lastUsed(model(day + 60_000)))
        assertEquals("3 days ago", vm.lastUsed(model(3 * day)))
        assertEquals("2 weeks ago", vm.lastUsed(model(14 * day)))
        assertEquals("3 months ago", vm.lastUsed(model(95 * day)))
        assertEquals("Not used yet", vm.lastUsed(model(null)))
    }

    @Test fun usedAgoNeverGoesNegative() {
        assertEquals("Used today", Format.usedAgo(now + day, now, ZoneOffset.UTC))
    }

    @Test fun pauseResumeAndCancelGoToTheRepository() {
        val vm = viewModel()
        vm.pause(deep)
        assertEquals(listOf(deep), scheduler.cancelled)
        vm.resume(deep)
        withTimeoutBlocking { while (scheduler.enqueued.isEmpty()) kotlinx.coroutines.delay(10) }
        assertEquals(listOf(deep), scheduler.enqueued)
    }

    private fun <T> withTimeoutBlocking(block: suspend () -> T): T =
        kotlinx.coroutines.runBlocking { withTimeout(5_000) { block() } }
}
