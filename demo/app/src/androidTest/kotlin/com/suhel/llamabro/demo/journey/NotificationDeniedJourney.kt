package com.suhel.llamabro.demo.journey

import androidx.compose.ui.test.ExperimentalTestApi
import com.suhel.llamabro.demo.models.Catalog
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Saying no to the system's notification question. The system asks once per install, so this journey runs first, on a
 * fresh install, in a run of its own (`demo/scripts/check.sh` does that); after any journey that said yes it is
 * skipped, and the requirement map reports it as not run.
 */
@OptIn(ExperimentalTestApi::class)
class NotificationDeniedJourney : Journey(seenFirstRun = false, asksForNotifications = true) {
    private val swift = Catalog.picks[0].key
    private val robot = Robot()

    @Test fun notificationPermissionDeniedStillDownloads() {
        world.engine.reply { content("Hello from Qwen3.5 0.8B") }
        seeTag("pick_card")
        tap("Qwen3.5 0.8B")
        tap("Get Qwen3.5 0.8B")
        assumeTrue("the question was already answered on this install", robot.notificationDialogIsShowing())
        robot.denyNotifications()

        // saying no to notifications only means no progress in the shade: the download goes on while the app is open
        seeTag("chat_screen")
        assertEquals(listOf(swift), world.downloads.enqueued)
        world.downloads.advance(swift)
        seeArrivingUnderWay()
        world.downloads.finish(swift)
        goneTag("download_ring")
        send("hi")
        see("Hello from Qwen3.5 0.8B")
    }
}
