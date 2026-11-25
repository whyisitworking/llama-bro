package com.suhel.llamabro.demo.server

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.suhel.llamabro.demo.ServerService
import com.suhel.llamabro.demo.ServingFeed
import com.suhel.llamabro.demo.keepServing
import com.suhel.llamabro.demo.models.Names
import com.suhel.llamabro.hub.HfModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowPowerManager
import java.time.Duration

/** The service that keeps serving alive (docs/design/server.md §3): its notification, its locks, and its life. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class ServerServiceTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val model = HfModel.parse("unsloth/Qwen3.5-0.8B-GGUF:Q4_K_M")
    private val state = MutableStateFlow<ServingState>(ServingState.Serving(model, 8080, 2))
    private val served = MutableStateFlow<HfModel?>(model)
    private val network = NetworkState().apply { setAddresses(listOf("192.168.1.20")) }
    private var stops = 0
    private lateinit var controller: ServiceController<ServerService>

    @Before fun feed() {
        network.setCharging(true)
        ServerService.feedOf = {
            ServingFeed(state, served, network) {
                stops++
                served.value = null
                state.value = ServingState.Off
            }
        }
    }

    @After fun reset() {
        ServerService.feedOf = null
    }

    private fun running(): ServerService {
        controller = Robolectric.buildService(ServerService::class.java, Intent(context, ServerService::class.java))
        controller.create().startCommand(0, 1)
        return controller.get()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    /** Past the time Off must last to end the service. */
    private fun settle() = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ServerService.SETTLE_MILLIS + 1))

    private fun Notification.title() = extras.getCharSequence(Notification.EXTRA_TITLE).toString()

    private fun Notification.text() = extras.getCharSequence(Notification.EXTRA_TEXT).toString()

    /** What the shade shows: the foreground one, and each update posted over it under the same id. */
    private fun shown(): Notification =
        shadowOf(context.getSystemService(NotificationManager::class.java))
            .allNotifications
            .last()
            .also { assertEquals(1, shadowOf(context.getSystemService(NotificationManager::class.java)).size()) }

    @Test fun runsForAsLongAsServing() =
        runTest(StandardTestDispatcher()) {
            // the Server page is in front when serving starts: that is when the service is asked for
            state.value = ServingState.Off
            val gate = MutableStateFlow<ServingState>(ServingState.Off)
            val watcher = launch { keepServing(context, gate) }
            testScheduler.advanceUntilIdle()
            assertNull(shadowOf(context as android.app.Application).nextStartedService)

            gate.value = ServingState.Starting()
            testScheduler.advanceUntilIdle()
            val started = shadowOf(context as android.app.Application).nextStartedService
            assertEquals(ServerService::class.java.name, started.component?.className)
            watcher.cancel()

            state.value = ServingState.Serving(model, 8080, 2)
            val service = running()
            idle()
            assertNotNull(shown())
            assertFalse(shadowOf(service).isStoppedBySelf)

            // going to the background changes nothing: the service is already running
            idle()
            assertFalse(shadowOf(service).isStoppedBySelf)

            state.value = ServingState.Off
            settle()
            assertTrue(shadowOf(service).isStoppedBySelf)
        }

    @Test fun restartKeepsTheServiceRunning() {
        val service = running()
        // a restart: Off, then Starting a moment later, as ServerMode.start does after a stop
        state.value = ServingState.Off
        idle()
        state.value = ServingState.Starting()
        settle()
        assertFalse(shadowOf(service).isStoppedBySelf)
        state.value = ServingState.Serving(model, 8080, 2)
        settle()
        assertFalse(shadowOf(service).isStoppedBySelf)
        // and an Off that stays ends it
        state.value = ServingState.Off
        settle()
        assertTrue(shadowOf(service).isStoppedBySelf)
    }

    @Test fun failedEndsIt() {
        val service = running()
        state.value = ServingState.Failed("no port")
        settle()
        assertTrue(shadowOf(service).isStoppedBySelf)
    }

    @Test fun stopActionStopsServing() {
        val service = running()
        val stop = shown().actions.single()
        assertEquals("Stop", stop.title.toString())
        // the action's intent, delivered to the service as Android would
        controller.get().onStartCommand(shadowOf(stop.actionIntent).savedIntent, 0, 3)
        settle()
        assertEquals(1, stops)
        assertEquals(ServingState.Off, state.value)
        assertTrue(shadowOf(service).isStoppedBySelf)
    }

    @Test fun locksAreHeldWhileServingAndReleasedOnStop() {
        val service = running()
        val wake = ShadowPowerManager.getLatestWakeLock()
        assertNotNull(wake)
        assertTrue(wake.isHeld)
        val wifiLock = service.wifiLock!!
        assertTrue(wifiLock.isHeld)

        state.value = ServingState.Off
        idle()
        controller.destroy()
        assertFalse(wake.isHeld)
        assertFalse(wifiLock.isHeld)
    }

    @Test fun locksAreReleasedWhenTheServiceIsDestroyed() {
        running()
        val wake = ShadowPowerManager.getLatestWakeLock()
        assertTrue(wake.isHeld)
        controller.destroy()
        assertFalse(wake.isHeld)
    }

    @Test fun notificationNamesModelAndUrl() {
        val service = running()
        val note = shown()
        assertEquals("Serving ${Names.of(model)} on http://192.168.1.20:8080", note.title())
        assertTrue(note.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertTrue(note.flags and Notification.FLAG_NO_CLEAR != 0)
        assertEquals("Charging", note.text())
    }

    @Test fun notChargingIsShown() {
        val service = running()
        network.setCharging(false)
        idle()
        assertEquals("Not charging", shown().text())
        network.setCharging(true)
        idle()
        assertEquals("Charging", shown().text())
    }

    @Test fun wifiLostShowsOfflineAndRecovers() {
        val service = running()
        network.setAddresses(emptyList())
        idle()
        assertEquals("Serving ${Names.of(model)}, not on Wi-Fi", shown().title())
        // the server keeps running: nothing stopped it
        assertFalse(shadowOf(service).isStoppedBySelf)
        assertEquals(0, stops)
        network.setAddresses(listOf("10.0.0.7"))
        idle()
        assertEquals("Serving ${Names.of(model)} on http://10.0.0.7:8080", shown().title())
    }

    @Test fun loadingShowsTheModelBeforeItServes() {
        state.value = ServingState.Starting(0.4f)
        val service = running()
        assertEquals("Loading ${Names.of(model)}", shown().title())
        state.value = ServingState.Serving(model, 8080, 1)
        idle()
        assertEquals("Serving ${Names.of(model)} on http://192.168.1.20:8080", shown().title())
    }
}
