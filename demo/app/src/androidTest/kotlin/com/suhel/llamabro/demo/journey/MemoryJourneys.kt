package com.suhel.llamabro.demo.journey

import android.Manifest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.suhel.llamabro.demo.chat.ReplyKeeper
import com.suhel.llamabro.demo.models.Catalog
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test

/** The model's memory as the person controls it (D59): unloading it, and a reply that goes on in the background. */
@OptIn(ExperimentalTestApi::class)
class MemoryJourneys : Journey() {
    private val swift = Catalog.picks[0].key
    private val robot = Robot()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private var keeper: ReplyKeeper? = null

    @After fun unregister() {
        keeper?.let { k -> instrumentation.runOnMainSync { ProcessLifecycleOwner.get().lifecycle.removeObserver(k) } }
        world.holdReplies.value = false
        robot.collapseNotificationShade()
    }

    @Test fun unloadFromTheModelSheetAndTheNextMessageLoadsItAgain() {
        onPhone(swift)
        world.engine.reply { content("First answer") }
        world.engine.reply { content("Second answer") }
        see("What's on your mind?")
        send("hi")
        see("First answer")

        compose.onNodeWithTag("model_title").performClick()
        see("IN MEMORY")
        see("Qwen3.5 0.8B")
        compose.waitForIdle()
        Thread.sleep(SHEET_SETTLE)
        shot("1-in-memory")
        tap("Unload")
        gone("IN MEMORY")
        shot("2-unloaded")
        robot.pressBack()

        // the chat's conversation was made on the model that was freed: the next message loads it and is answered
        send("again")
        see("Second answer")
    }

    @Test fun aReplyGoesOnInTheBackgroundUnderANotification() {
        // as a person who allowed notifications when the first download asked
        instrumentation.uiAutomation.grantRuntimePermission(
            instrumentation.targetContext.packageName,
            Manifest.permission.POST_NOTIFICATIONS,
        )
        // the test app skips the app's own start-up, so the journey registers the observer the app registers
        keeper = ReplyKeeper(instrumentation.targetContext) { app.container.engine.replying.value }
        instrumentation.runOnMainSync { ProcessLifecycleOwner.get().lifecycle.addObserver(keeper!!) }
        onPhone(swift)
        world.engine.reply { content("Written while you were away") }
        world.holdReplies.value = true
        see("What's on your mind?")
        send("hi")
        seeTag("typing")

        robot.pressHome()
        robot.openNotificationShade()
        assertTrue("the shade shows the reply being written", robot.shadeShows("Writing a reply"))
        // the reply ends: the service goes with it
        world.holdReplies.value = false
        val device = UiDevice.getInstance(instrumentation)
        assertTrue(
            "the notification leaves when the reply ends",
            device.wait(Until.gone(By.textContains("Writing a reply")), WAIT),
        )
        robot.collapseNotificationShade()
        robot.relaunch()
        see("Written while you were away")
    }

    private companion object {
        const val SHEET_SETTLE = 700L
    }
}
