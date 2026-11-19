package com.suhel.llamabro.demo.testing

import androidx.compose.ui.test.junit4.ComposeTestRule

/**
 * Waits for a newly shown window (an activity starting, a sheet opening) before a test acts on it. The instrumented
 * runs have window animations off (`testOptions.animationsDisabled`), so the window is at its final size once Compose
 * is idle: an accessibility check made during an open animation would read every 48 dp target scaled down (F101).
 */
fun ComposeTestRule.settleWindow() = waitForIdle()
