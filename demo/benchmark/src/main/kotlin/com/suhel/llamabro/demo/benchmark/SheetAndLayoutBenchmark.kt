package com.suhel.llamabro.demo.benchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The model sheet switching between Basic and Advanced, and opened and closed; the layout switching between Sidebar
 * and Chat list.
 */
@RunWith(AndroidJUnit4::class)
class SheetAndLayoutBenchmark {
    @get:Rule val rule = MacrobenchmarkRule()

    @Test fun switchTheModelSheetBetweenBasicAndAdvanced() =
        rule.measureRepeated(
            packageName = PACKAGE,
            metrics = listOf(FrameTimingMetric()),
            iterations = StartupBenchmark.ITERATIONS,
            startupMode = StartupMode.WARM,
            compilationMode = CompilationMode.Partial(),
            setupBlock = {
                startActivityAndWait()
                waitForHome()
                openAModelSheet()
            },
        ) {
            repeat(SWITCHES) {
                device.findObject(By.text("Advanced")).click()
                device.wait(Until.hasObject(By.text("Basic")), WAIT)
                device.waitForIdle()
                device.findObject(By.text("Basic")).click()
                device.waitForIdle()
            }
        }

    /** The model sheet opened and slid away: with glass sheets (D176), the page under it is drawn again each frame. */
    @Test fun openAndCloseTheModelSheet() =
        rule.measureRepeated(
            packageName = PACKAGE,
            metrics = listOf(FrameTimingMetric()),
            iterations = StartupBenchmark.ITERATIONS,
            startupMode = StartupMode.WARM,
            compilationMode = CompilationMode.Partial(),
            setupBlock = {
                startActivityAndWait()
                waitForHome()
                device.findObject(By.text("Models")).click()
            },
        ) {
            repeat(OPENINGS) {
                openTheSuggestedModel()
                device.pressBack()
                check(device.wait(Until.gone(By.text("Advanced")), WAIT)) { "the model sheet never closed" }
                device.waitForIdle()
            }
        }

    @Test fun switchTheLayoutBackAndForth() =
        rule.measureRepeated(
            packageName = PACKAGE,
            metrics = listOf(FrameTimingMetric()),
            iterations = StartupBenchmark.ITERATIONS,
            startupMode = StartupMode.WARM,
            compilationMode = CompilationMode.Partial(),
            setupBlock = {
                startActivityAndWait()
                waitForHome()
                device.findObject(By.text("Settings")).click()
                device.wait(Until.hasObject(By.text("Appearance")), WAIT)
                device.findObject(By.text("Appearance")).click()
                device.wait(Until.hasObject(By.res("layout_sidebar")), WAIT)
            },
        ) {
            repeat(SWITCHES) {
                device.clickWhenReady("layout_sidebar")
                device.waitForIdle()
                device.clickWhenReady("layout_chat_list")
                device.waitForIdle()
            }
        }

    companion object {
        @JvmStatic @BeforeClass
        fun seed() = seedTheApp()

        /** A layout switch rebuilds the screen, so a node found a moment ago can be gone: find it again, then click. */
        private fun UiDevice.clickWhenReady(resourceId: String) {
            repeat(CLICK_ATTEMPTS) {
                try {
                    wait(Until.findObject(By.res(resourceId)), WAIT)?.click() ?: return@repeat
                    return
                } catch (_: StaleObjectException) {
                    waitForIdle()
                }
            }
            error("Could not click $resourceId")
        }

        private const val OPENINGS = 5
        private const val CLICK_ATTEMPTS = 3
        private const val SWITCHES = 10
        private const val WAIT = 10_000L
    }
}
