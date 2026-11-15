package com.suhel.llamabro.demo.journey

import android.Manifest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.suhel.llamabro.ContextSize
import com.suhel.llamabro.demo.chat.HostState
import com.suhel.llamabro.demo.chat.ReplyKeeper
import com.suhel.llamabro.demo.data.ChipChoice
import com.suhel.llamabro.demo.models.Catalog
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
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

    @Test fun aChipThatCantBeUsedIsNotedAndChoosingAgainReloadsTheModel() {
        // the GPU chosen earlier; the test app's engine has a CPU only, so the model falls back to it (D72)
        runBlocking { app.container.settings.setChip(ChipChoice.Gpu) }
        onPhone(swift)
        world.engine.reply { content("First answer") }
        world.engine.reply { content("Second answer") }
        see("What's on your mind?")
        send("hi")
        see("First answer")
        see("Qwen3.5 0.8B is running on the CPU: this phone has no GPU.")
        shot("1-chip-note")

        // its Settings opens the choice, which says the same under it
        compose.onNode(hasText("Settings") and hasAnyAncestor(hasTestTag("limit_line"))).performClick()
        seeTag("runs_on")
        see("Running on the CPU: this phone has no GPU.")
        see("Not on this phone", substring = true)
        shot("2-runs-on")
        // the chip's Automatic, not the context length's
        compose.onNode(hasText("Automatic") and hasAnyAncestor(hasTestTag("runs_on"))).performScrollTo().performClick()
        // no reply holds it: the model loaded for the GPU is freed now
        see("Nothing loaded")
        gone("Running on the CPU", substring = true)
        robot.pressBack()

        send("again")
        see("Second answer")
        gone("is running on the CPU", substring = true)
    }

    @Test fun aContextLengthChosenReloadsTheModelAtThatLength() {
        onPhone(swift)
        world.engine.reply { content("First answer") }
        world.engine.reply { content("Second answer") }
        see("What's on your mind?")
        send("hi")
        see("First answer")

        tapDescription("Chats")
        tapDescription("Settings")
        tap("Memory")
        seeTag("context_length")
        // the size the SDK picked, beside the chip: the test app's engine picks 8 192 tokens
        see("CPU · 8k context", substring = true)
        compose.onNodeWithText("8k").performScrollTo().performClick()
        // no reply holds it: the model loaded at Automatic is freed now, as for a chip change (D72)
        see("Nothing loaded")
        shot("1-context-length")
        robot.pressBack()
        robot.pressBack()

        send("again")
        see("Second answer")
        assertEquals(ContextSize.UpTo(8_192), (app.container.host.state.value as HostState.Ready).config.context)
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
