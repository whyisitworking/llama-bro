package com.suhel.llamabro.demo.journey

import android.graphics.drawable.ColorDrawable
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.accessibility.disableAccessibilityChecks
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.suhel.llamabro.demo.designsystem.components.DsGutterContainerKey
import com.suhel.llamabro.demo.designsystem.components.DsGutterKey
import com.suhel.llamabro.demo.designsystem.theme.Dark
import com.suhel.llamabro.demo.designsystem.theme.Light
import com.suhel.llamabro.demo.designsystem.theme.ThemeMode
import com.suhel.llamabro.demo.models.Catalog
import com.suhel.llamabro.demo.testing.withAccessibilityChecks
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Walks every route and sheet of the shell in both themes: a picture of each, and the accessibility check on each once
 * it has settled (it reads a surface that is still sliding in as clipped, which it is not).
 */
@OptIn(ExperimentalTestApi::class)
class WalkJourneys : Journey() {
    private val swift = Catalog.picks[0].key
    private val deep = Catalog.picks[2].key
    private val robot = Robot()

    /**
     * Every area a layout primitive keeps inside the gutter (D48) starts 16 dp inside the left edge and ends 16 dp
     * inside the right edge of its own screen or sheet (on a tablet the sheet is narrower than the window, so the edge
     * is the container's, not the window's). [required] says the screen has such an area at all.
     */
    private fun assertOnTheGutter(name: String, required: Boolean) {
        val nodes =
            compose
                .onAllNodes(
                    SemanticsMatcher.keyIsDefined(DsGutterKey),
                    useUnmergedTree = true,
                ).fetchSemanticsNodes()
        if (required) assertTrue("$name has no area on the gutter", nodes.isNotEmpty())
        val gutter = with(compose.density) { 16.dp.toPx() }
        for (node in nodes) {
            var container = node.parent
            while (container != null && !container.config.contains(DsGutterContainerKey)) container = container.parent
            assertTrue("$name: an area on the gutter has no container", container != null)
            assertEquals("$name: left gutter", gutter, node.boundsInRoot.left - container!!.boundsInRoot.left, 1f)
            assertEquals("$name: right gutter", gutter, container.boundsInRoot.right - node.boundsInRoot.right, 1f)
        }
    }

    /**
     * What shows behind the status bar and under a sheet's scrim is the window's own background; it must be the app's
     * canvas in the theme the app is in, whatever the phone's day or night setting is (a dark app on a light phone
     * once showed a light strip there).
     */
    private fun assertTheWindowFollowsTheTheme(theme: ThemeMode) {
        val expected = (if (theme == ThemeMode.Dark) Dark.canvas else Light.canvas).toArgb()
        val actual = (compose.activity.window.decorView.background as? ColorDrawable)?.color
        assertEquals("the window's background in ${theme.name}", expected, actual)
    }

    /** Runs [move] unchecked, waits for the screen to settle, then checks it and takes its picture. */
    private fun arrive(name: String, theme: ThemeMode, gutter: Boolean = true, move: () -> Unit) {
        compose.disableAccessibilityChecks()
        move()
        compose.waitForIdle()
        Thread.sleep(SETTLE)
        compose.withAccessibilityChecks()
        compose.onNodeWithTag("shell_root").assertIsDisplayed()
        assertOnTheGutter("${theme.name.lowercase()}-$name", gutter)
        assertTheWindowFollowsTheTheme(theme)
        shot("${theme.name.lowercase()}-$name")
    }

    private fun back() = tapDescription("Back")

    private fun closeSheet() {
        robot.pressBack()
        compose.waitForIdle()
    }

    @Test fun everyScreenInBothThemes() {
        onPhone(swift, deep)
        world.engine.reply { content("Lisbon has **seven hills**, and a tram to climb each one.") }
        see("What's on your mind?")
        send("Plan my trip")
        see("seven hills", substring = true)

        for (theme in listOf(ThemeMode.Light, ThemeMode.Dark)) {
            runBlocking { app.container.settings.setTheme(theme) }
            compose.waitForIdle()
            arrive("chat", theme) {}
            arrive("model-switcher", theme) { compose.onNodeWithTag("model_title").performClick() }
            closeSheet()
            arrive("think-menu", theme) { tapDescription("Think") }
            closeSheet()
            arrive("message-menu", theme) {
                compose
                    .onNode(
                        hasText("Plan my trip") and hasAnyAncestor(hasTestTag("user_message")),
                    ).performTouchInput {
                        longClick()
                    }
            }
            closeSheet()
            arrive("drawer", theme, gutter = false) { tapDescription("Chats") }
            arrive("library", theme) { tap("Models") }
            arrive("get-models", theme) { tap("Get more models") }
            arrive("model-sheet", theme) { tap(Catalog.suggested[3].realName) }
            closeSheet()
            back() // Get models → library
            back() // library → chat
            arrive("drawer-again", theme, gutter = false) { tapDescription("Chats") }
            arrive("settings", theme) { tap("Settings") }
            for ((row, title) in SETTINGS_PAGES) {
                arrive("settings-$title", theme) { tap(row) }
                back()
            }
            back() // Settings → chat
            see("seven hills", substring = true)
        }
    }

    private companion object {
        const val SETTLE = 700L
        val SETTINGS_PAGES =
            listOf(
                "Appearance" to "appearance",
                "Custom instructions" to "instructions",
                "Default model" to "default-model",
                "Hugging Face token" to "token",
                "Sampling" to "sampling",
                "Memory" to "memory",
                "About" to "about",
            )
    }
}
