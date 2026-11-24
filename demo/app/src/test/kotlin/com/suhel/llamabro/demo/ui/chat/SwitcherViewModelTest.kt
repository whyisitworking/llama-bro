package com.suhel.llamabro.demo.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.suhel.llamabro.demo.chat.SamplingLayers
import com.suhel.llamabro.demo.data.settings.Knob
import com.suhel.llamabro.demo.data.settings.Knobs
import com.suhel.llamabro.demo.models.DeviceFacts
import com.suhel.llamabro.demo.ui.keepAlive
import com.suhel.llamabro.demo.ui.shutDown
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * The switcher's and the sampling sheet's view models: their writes run on the app's scope, so a sheet that closes as
 * it writes (its view model cleared) doesn't cancel them.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SwitcherViewModelTest {
    private val main = UnconfinedTestDispatcher()
    private val app = TestScope(StandardTestDispatcher())
    private val viewModels = mutableListOf<ViewModel>()

    @Before fun setUp() = Dispatchers.setMain(main)

    @After fun tearDown() = shutDown(main, viewModels)

    private val chatId = MutableStateFlow(0L)
    private val pinned = mutableListOf<String>()
    private var unloads = 0
    private val gate = CompletableDeferred<Unit>()

    private fun switcher() =
        SwitcherViewModel(
            library = flowOf(emptyList()),
            facts = flowOf(DeviceFacts.Fallback),
            inMemory = flowOf(null),
            replying = MutableStateFlow(false),
            chatId = chatId,
            chatSampling = { flowOf(Knobs(mapOf(Knob.Temperature to 0.5f))) },
            pinDefault = {
                gate.await()
                pinned += it
            },
            unloadModel = {
                gate.await()
                unloads++
            },
            background = app,
        ).also(viewModels::add)

    @Test fun aWriteSurvivesTheSheetThatStartedIt() {
        val vm = switcher()
        vm.pinDefault("a/b/c.gguf")
        vm.unload()
        app.runCurrent()
        shutDownScope(vm) // the sheet closes as it writes
        gate.complete(Unit)
        app.runCurrent()
        assertEquals(listOf("a/b/c.gguf"), pinned)
        assertEquals(1, unloads)
    }

    @Test fun aChatWithNoMessageYetHasNoSamplingRow() {
        val vm = switcher()
        vm.chatSampling.keepAlive(CoroutineScope(main))
        assertNull(vm.chatSampling.value)
        chatId.value = 7L
        assertEquals(
            1,
            vm.chatSampling.value
                ?.values
                ?.size,
        )
    }

    @Test fun theSamplingSheetsWritesSurviveItToo() {
        val written = mutableListOf<Pair<Knob, Float?>>()
        var resets = 0
        val vm =
            ChatSamplingViewModel(
                layers = flowOf(SamplingLayers(null, Knobs.None)),
                setKnob = { knob, value ->
                    gate.await()
                    written += knob to value
                },
                resetAll = {
                    gate.await()
                    resets++
                },
                background = app,
            ).also(viewModels::add)
        vm.change(Knob.Temperature, 0.2f)
        vm.reset()
        app.runCurrent()
        shutDownScope(vm)
        gate.complete(Unit)
        app.runCurrent()
        assertEquals(listOf(Knob.Temperature to 0.2f), written)
        assertEquals(1, resets)
    }

    /** What clearing the view model does to its own work. */
    private fun shutDownScope(vm: ViewModel) {
        vm.viewModelScope.coroutineContext[Job]?.cancel()
    }
}
