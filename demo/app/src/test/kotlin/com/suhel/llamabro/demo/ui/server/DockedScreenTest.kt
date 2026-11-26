package com.suhel.llamabro.demo.ui.server

import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.suhel.llamabro.demo.designsystem.theme.DemoTheme
import com.suhel.llamabro.demo.designsystem.theme.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The docked view keeps the screen on and dim while it is shown, and goes back when serving ends. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DockedScreenTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val showing =
        Docked.Showing(
            "16.5",
            "1 of 2",
            "Light",
            "POST /v1/messages · 200 · 12:04:51",
            "http://192.168.1.20:8080",
        )

    @Test fun dockedKeepsTheScreenOn() {
        val window = rule.activity.window
        val before = window.attributes.screenBrightness
        var shown by mutableStateOf(true)
        rule.setContent { if (shown) DemoTheme(ThemeMode.Light) { DockedHost(showing, onBack = {}) } }
        rule.waitForIdle()
        assertTrue(window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0)
        assertEquals(0.1f, window.attributes.screenBrightness, 0f)

        shown = false
        rule.waitForIdle()
        assertFalse(window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0)
        assertEquals(before, window.attributes.screenBrightness, 0f)
    }

    @Test fun landscapeUsesTheWideLayout() {
        assertEquals(DockedLayout.Wide, dockedLayoutFor(780.dp, 360.dp))
        assertEquals(DockedLayout.Tall, dockedLayoutFor(360.dp, 780.dp))
        assertEquals(DockedLayout.Tall, dockedLayoutFor(400.dp, 400.dp))
    }

    @Test fun servingEndedLeavesDocked() {
        var docked: Docked by mutableStateOf(showing)
        var left = 0
        rule.setContent { DemoTheme(ThemeMode.Light) { DockedHost(docked, onBack = { left++ }) } }
        rule.waitForIdle()
        assertEquals(0, left)
        docked = Docked.Ended
        rule.waitForIdle()
        assertEquals(1, left)
    }
}
