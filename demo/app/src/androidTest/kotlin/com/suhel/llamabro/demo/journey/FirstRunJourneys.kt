package com.suhel.llamabro.demo.journey

import androidx.compose.ui.test.ExperimentalTestApi
import com.suhel.llamabro.demo.models.Catalog
import org.junit.Assert.assertEquals
import org.junit.Test

/** The first minutes: from the three picks to the first answer. */
@OptIn(ExperimentalTestApi::class)
class FirstRunJourneys : Journey(seenFirstRun = false, asksForNotifications = true) {
    private val swift = Catalog.picks[0].key
    private val robot = Robot()

    @Test fun firstRunToFirstAnswer() {
        world.engine.reply {
            content("Hello from Qwen3.5 0.8B")
            timings(promptTokens = 5, predictedTokens = 40)
        }
        seeTag("pick_card")
        shot("1-picks")
        tap("Qwen3.5 0.8B")
        see("Get Qwen3.5 0.8B")
        shot("2-sheet")
        tap("Get Qwen3.5 0.8B")
        robot.allowNotificationsIfAsked()

        seeTag("chat_screen")
        seeTag("download_ring")
        assertEquals(listOf(swift), world.downloads.enqueued)
        send("hello there")
        see("Qwen3.5 0.8B will answer the moment it's here")
        shot("3-held")

        world.downloads.advance(swift)
        seeArrivingUnderWay()
        world.downloads.finish(swift)

        see("Hello from Qwen3.5 0.8B")
        goneTag("download_ring")
        seeDescription("Reply details") // the reply's actions, with its details behind the info icon
        shot("4-answered")
    }
}
