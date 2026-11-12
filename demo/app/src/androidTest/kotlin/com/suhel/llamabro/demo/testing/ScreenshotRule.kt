package com.suhel.llamabro.demo.testing

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onRoot
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import java.io.File

/**
 * Captures the screen to `<app external files>/screens/<test>/<theme>.png`, kept as evidence for T13's review
 * (pull with `adb pull /sdcard/Android/data/com.suhel.llamabro.demo/files/screens`).
 */
class ScreenshotRule(private val compose: ComposeTestRule) : TestWatcher() {
    private var test = "unnamed"

    override fun starting(description: Description) {
        test = "${description.className.substringAfterLast('.')}.${description.methodName}"
    }

    /** A failed test leaves the screen it failed on, for whoever reads the failure. */
    override fun failed(e: Throwable?, description: Description) {
        runCatching { capture("FAILED") }
    }

    fun capture(theme: String): File {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(context.getExternalFilesDir("screens"), test).apply { mkdirs() }
        val file = File(dir, "$theme.png")
        // a sheet or dialog is a second window, so there is more than one root: take the whole screen then
        val roots =
            compose
                .onAllNodes(
                    androidx.compose.ui.test
                        .isRoot(),
                ).fetchSemanticsNodes()
                .size
        val bitmap =
            if (roots == 1) {
                compose.onRoot().captureToImage().asAndroidBitmap()
            } else {
                InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return file
    }
}
