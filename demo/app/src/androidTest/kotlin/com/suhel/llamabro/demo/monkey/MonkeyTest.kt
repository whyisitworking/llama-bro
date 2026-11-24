package com.suhel.llamabro.demo.monkey

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.test.platform.app.InstrumentationRegistry
import com.suhel.llamabro.demo.journey.Journey
import com.suhel.llamabro.demo.journey.Robot
import com.suhel.llamabro.demo.models.Catalog
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Random taps, swipes and keys at the whole app (the system's `monkey`, restricted to this app), over a fake engine and
 * hub with models on the phone. A crash ends the instrumentation, so passing means no crash, no ANR (monkey reports
 * one) and an app still answering at the end. The seeds are fixed, so a failure can be replayed.
 */
@OptIn(ExperimentalTestApi::class)
class MonkeyTest : Journey() {
    private val swift = Catalog.picks[0].key
    private val deep = Catalog.picks[2].key

    private val robot = Robot()

    @After fun putThePhoneBack() = robot.restoreSystem()

    private fun monkey(seed: Int) {
        onPhone(swift, deep)
        repeat(REPLIES) { world.engine.reply { content("A short reply, number $it.") } }
        see("What's on your mind?")
        send("hello")
        see("A short reply", substring = true)

        val packageName = InstrumentationRegistry.getInstrumentation().targetContext.packageName
        val command =
            "monkey -p $packageName -s $seed --throttle $THROTTLE_MILLIS --pct-syskeys 0 --pct-appswitch 0 " +
                // a keyboard-lid flip is meaningless on a phone, and the emulator refuses to inject it: monkey then
                // aborts the run
                "--pct-anyevent 0 --pct-nav 0 --pct-majornav 0 --pct-trackball 0 --pct-flip 0 " +
                "--ignore-security-exceptions $EVENTS"
        val output =
            android.os.ParcelFileDescriptor
                .AutoCloseInputStream(
                    InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command),
                ).use { String(it.readBytes()) }

        assertFalse("monkey saw a crash:\n${output.takeLast(TAIL)}", output.contains("// CRASH"))
        assertFalse("monkey saw an ANR:\n${output.takeLast(TAIL)}", output.contains("// NOT RESPONDING"))
        assertTrue("monkey stopped early:\n${output.takeLast(TAIL)}", output.contains("Events injected: $EVENTS"))
    }

    @Test fun seedOne() = monkey(1)

    @Test fun seedTwo() = monkey(2)

    @Test fun seedThree() = monkey(3)

    private companion object {
        const val EVENTS = 10_000
        const val THROTTLE_MILLIS = 25
        const val REPLIES = 200
        const val TAIL = 1_500
    }
}
