package com.suhel.llamabro.sample

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The sample's UI end to end, found by resource ID (res/values/ids.xml), not coordinates: load the first GGUF in
 * filesDir, type a message with the keyboard up, tap Send, and see a streamed reply with its timings line.
 */
@RunWith(AndroidJUnit4::class)
class SampleUiTest {
    private val pkg = "com.suhel.llamabro.sample"
    private val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    @Test
    fun sendsAMessageAndStreamsTheReply() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.startActivity(context.packageManager.getLaunchIntentForPackage(pkg)!!.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK))

        val send = device.wait(Until.findObject(By.res(pkg, "send").enabled(true)), 60_000)
        assertNotNull("model didn't load (Send stays disabled)", send)

        device.findObject(By.res(pkg, "input")).apply { click(); text = "Say hello in three words." }
        // with the keyboard up, Send must still be on screen above it (edge-to-edge insets)
        val sendNow = device.wait(Until.findObject(By.res(pkg, "send")), 5_000)
        assertTrue("Send is hidden under the keyboard", sendNow.visibleBounds.bottom <= device.displayHeight)
        sendNow.click()

        val output = device.findObject(By.res(pkg, "output"))
        assertTrue("no reply with timings", output.wait(Until.textContains("t/s decode"), 120_000))
        assertTrue(output.text.contains("> Say hello in three words."))
    }
}
