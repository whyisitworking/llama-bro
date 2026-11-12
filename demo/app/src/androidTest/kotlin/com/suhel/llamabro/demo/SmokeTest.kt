package com.suhel.llamabro.demo

import android.content.Context
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.platform.app.InstrumentationRegistry
import com.suhel.llamabro.LlamaBro
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class SmokeTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    @Test
    fun launchesAndShowsTheShell() {
        rule.onNodeWithTag("shell_root").assertExists()
    }

    @Test
    fun theRuntimeReportsAtLeastOneDevice() {
        val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
        val devices = runBlocking { LlamaBro.getInstance(context).devices() }
        assertTrue("no ggml device", devices.isNotEmpty())
    }
}
