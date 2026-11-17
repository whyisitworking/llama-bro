package com.suhel.llamabro.download

import android.Manifest
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import androidx.work.WorkManager
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Confirms the app's foreground download notification shows live byte progress. */
@RunWith(AndroidJUnit4::class)
class BackgroundDownloadTest {
    @Test fun notificationProgressIncreases() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val device = UiDevice.getInstance(instrumentation)
        device.executeShellCommand("pm grant ${context.packageName} ${Manifest.permission.POST_NOTIFICATIONS}")
        val launch =
            Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                putExtra("endpoint", "http://127.0.0.1:18190/")
                putExtra("ref", "test/big:Q4_K_M")
                putExtra("autostart", true)
            }
        context.startActivity(launch)
        try {
            checkNotificationProgress(device)
        } finally {
            // Leave nothing downloading on the phone once the fake server is gone.
            WorkManager
                .getInstance(context)
                .cancelUniqueWork("test/big:Q4_K_M")
                .result
                .get()
        }
    }

    private fun checkNotificationProgress(device: UiDevice) {
        device.executeShellCommand("cmd statusbar expand-notifications")
        device.waitForIdle()
        assertTrue("the download's notification never appeared", scrollShadeTo(device, "/1536 MB", 30_000))

        fun megabytes(): Int? =
            device
                .findObjects(By.textContains("/1536 MB"))
                .asSequence()
                .mapNotNull { Regex("(\\d+)/1536 MB").find(it.text.orEmpty()) }
                .map { it.groupValues[1].toInt() }
                .firstOrNull()

        val firstDeadline = System.currentTimeMillis() + 30_000
        var first: Int? = null
        while (first == null && System.currentTimeMillis() < firstDeadline) {
            first = megabytes()
            if (first == null) Thread.sleep(250)
        }
        assertTrue("notification did not show MB progress", first != null)
        Thread.sleep(3_000)
        val secondDeadline = System.currentTimeMillis() + 15_000
        var second: Int? = null
        while (System.currentTimeMillis() < secondDeadline) {
            second = megabytes()
            if (second != null && second > first!!) break
            Thread.sleep(250)
        }
        assertTrue("notification did not advance from $first MB to $second MB", second != null && second > first!!)
        device.pressBack()
    }

    /**
     * Waits up to [timeoutMillis] for [text] in the open shade, scrolling it up when the text isn't in view: the quiet
     * notification sits below the visible part of the shade on a phone with many notifications (F150). The shade is
     * expanded again before each swipe, since a swipe at the end of the list closes it on some phones.
     */
    private fun scrollShadeTo(device: UiDevice, text: String, timeoutMillis: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMillis
        var swipes = 0
        while (System.currentTimeMillis() < deadline) {
            if (device.wait(Until.hasObject(By.textContains(text)), 1_000)) return true
            if (swipes < MAX_SHADE_SWIPES) {
                device.executeShellCommand("cmd statusbar expand-notifications")
                device.waitForIdle()
                val x = device.displayWidth / 2
                device.swipe(x, device.displayHeight * 3 / 4, x, device.displayHeight / 4, SWIPE_STEPS)
                swipes++
            }
        }
        return false
    }

    private companion object {
        const val MAX_SHADE_SWIPES = 6
        const val SWIPE_STEPS = 20
    }
}
