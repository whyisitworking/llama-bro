package com.suhel.llamabro.demo.ui.models

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.suhel.llamabro.DeviceKind
import com.suhel.llamabro.demo.data.SettingsRepository
import com.suhel.llamabro.demo.models.DeviceFacts
import com.suhel.llamabro.demo.models.FakeScheduler
import com.suhel.llamabro.demo.models.ModelRepository
import com.suhel.llamabro.demo.models.SearchModel
import com.suhel.llamabro.demo.models.TestHub
import com.suhel.llamabro.demo.ui.keepAlive
import com.suhel.llamabro.hub.HfModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
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
 * A model on the phone takes its maker from the Hub's cached facts when the catalog doesn't know it and its record
 * left when it landed, so its row and its card name the maker from the first frame.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LibraryMakersTest {
    @get:Rule val folder = TemporaryFolder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val testHub = TestHub()
    private val cache = FakeHubSearch()
    private var viewModel: ModelsViewModel? = null

    @Before fun main() = Dispatchers.setMain(StandardTestDispatcher())

    @After fun stop() {
        viewModel?.viewModelScope?.cancel()
        Dispatchers.resetMain()
        scope.cancel()
        testHub.close()
    }

    @Test fun aRowFromThePhoneTakesItsMakerFromTheCachedFacts() =
        runTest {
            testHub.install(HfModel.parse("someone/Phi-GGUF:Q4_K_M"))
            val facts = SearchModel("phi", "Phi", "someone/Phi-GGUF", "someone", 1, false, null, maker = "microsoft")
            cache.facts.value = mapOf("someone/phi-gguf" to facts)
            val settings =
                SettingsRepository(
                    PreferenceDataStoreFactory.create(scope = scope) { File(folder.root, "s.preferences_pb") },
                )
            val models = ModelRepository(testHub.hub, settings, FakeScheduler(), CoroutineScope(Dispatchers.Unconfined))
            val vm =
                ModelsViewModel(
                    hub = cache,
                    models = models,
                    facts = { DeviceFacts(budgetBytes = 8L shl 30, chip = DeviceKind.Gpu) },
                    freeBytes = { 120_000_000_000L },
                    firstRunSeen = MutableStateFlow(true),
                    initialQuery = "",
                    clock = { 0L },
                    zone = ZoneOffset.UTC,
                    io = EmptyCoroutineContext,
                    savedState = SavedStateHandle(),
                ).also { viewModel = it }
            vm.browse.keepAlive(backgroundScope)
            // the repository's threads are real: wait in real time while the test dispatcher runs
            val browse =
                withContext(Dispatchers.Default) {
                    withTimeout(5_000) { vm.browse.filterNotNull().first { it.onPhone.isNotEmpty() } }
                }
            assertEquals(listOf("Microsoft"), browse.onPhone.map { it.maker })
        }
}
