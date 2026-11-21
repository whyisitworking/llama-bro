package com.suhel.llamabro.demo.journey

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasContentDescription
import androidx.work.WorkManager
import com.suhel.llamabro.demo.models.Catalog
import com.suhel.llamabro.demo.ui.models.MODELS_INTRO
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The downloads that need the system: the real WorkManager worker and notification over the fake Hugging Face
 * server, slowed so there is time to leave the app. (Only the engine is a fake.)
 */
@OptIn(ExperimentalTestApi::class)
class RealDownloadJourneys : Journey(seenFirstRun = false, asksForNotifications = true, realDownloads = true) {
    private val swift = Catalog.picks[0].key
    private val robot = Robot()

    @Before fun slowServer() {
        FakeHubServer.reset()
        FakeHubServer.slow(mibPerSecond = SLOW_MIB_PER_SECOND)
    }

    @After fun cleanUp() =
        runBlocking<Unit>(Dispatchers.IO) {
            robot.collapseNotificationShade() // an open shade keeps the focus, and the next test reads no clipboard
            WorkManager.getInstance(app.app).cancelAllWork()
            FakeHubServer.reset()
            app.container.hub.delete(swift)
        }

    private fun getSwift() {
        see(MODELS_INTRO) // a fresh install starts on Models
        getFromModels("Qwen3.5 0.8B")
        robot.allowNotificationsIfAsked()
        seeTag("chat_screen")
        seeDescription("Arriving")
    }

    @Test fun downloadContinuesInBackgroundWithNotification() {
        getSwift()
        shot("1-downloading")

        robot.pressHome()
        robot.openNotificationShade()
        assertTrue("the shade shows the download", robot.shadeShows("Downloading Qwen3.5 0.8B"))
        robot.tapInShade("Downloading Qwen3.5 0.8B")

        // the notification brings the app back to the chat, still downloading
        seeTag("chat_screen")
        seeDescription("Arriving")
        shot("2-back-from-the-notification")
    }

    /**
     * The system stops the worker mid-transfer, as it does when it kills the app: the partial file stays, the app
     * starts again, and resuming asks the server only for what is missing (the server's log shows a `range`). A real
     * process kill ends the instrumentation too, so that half is `sdk/scripts/check.sh --device` (hub-resume).
     */
    @Test fun downloadSurvivesProcessDeath() {
        world.engine.reply { content("Hello from Qwen3.5 0.8B") }
        getSwift()
        send("hi")
        see("Qwen3.5 0.8B will answer the moment it's here")
        // bytes have arrived, so there is a partial file for the stop to leave behind
        compose.waitUntil(WAIT) {
            compose
                .onAllNodes(hasContentDescription("Arriving", substring = true))
                .fetchSemanticsNodes()
                .any { node ->
                    val text = node.config[SemanticsProperties.ContentDescription].joinToString()
                    !text.contains("· 0%")
                }
        }

        WorkManager.getInstance(app.app).cancelAllWork() // the system stops the worker
        compose.activityRule.scenario.recreate() // and the app comes back
        seeTag("chat_screen")

        FakeHubServer.slow(mibPerSecond = FAST_MIB_PER_SECOND)
        tapDescription("Chats")
        tapDrawerModels()
        see("DOWNLOADING")
        tap("Resume")
        gone("DOWNLOADING", timeoutMillis = DOWNLOAD_WAIT) // the model is on the phone
        tapDescription("Back")
        see("Hello from Qwen3.5 0.8B") // and the question that waited for it is answered
        // the file that was arriving is asked for again from where it stopped (the next file, the image reader, starts at 0)
        val ranges = FakeHubServer.cdnGets().map { it.optString("range") }
        assertTrue("a resumed GET carries a range, the GETs had: $ranges", ranges.any { it.startsWith("bytes=") })
        shot("1-answered-after-resuming")
    }

    private companion object {
        const val SLOW_MIB_PER_SECOND = 1
        const val FAST_MIB_PER_SECOND = 64
        const val DOWNLOAD_WAIT = 60_000L
    }
}
