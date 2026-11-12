package com.suhel.llamabro.demo.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performTextInput
import androidx.core.view.WindowCompat
import com.suhel.llamabro.demo.MainActivity
import com.suhel.llamabro.demo.designsystem.theme.ThemeMode
import com.suhel.llamabro.demo.testing.TestAppRule
import com.suhel.llamabro.demo.testing.demoRules
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain

/** The chat screen inside the real shell and activity: what only a real activity can show. */
@OptIn(ExperimentalTestApi::class)
class ChatShellTest {
    private val app = TestAppRule()
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule val rules: RuleChain = RuleChain.outerRule(demoRules(app)).around(compose)

    @Test fun composerTextSurvivesRotation() {
        compose.waitUntilAtLeastOneExists(hasTestTag("composer"), 10_000)
        compose.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag("composer"))).performTextInput("half written")
        compose.waitUntilAtLeastOneExists(hasText("half written"), 5_000)
        compose.activityRule.scenario.recreate() // what a rotation does to the activity
        compose.waitUntilAtLeastOneExists(hasText("half written"), 10_000)
    }

    @Test fun systemBarIconsFollowTheAppTheme() {
        compose.waitUntilAtLeastOneExists(hasTestTag("composer"), 10_000)

        fun lightIcons(): Boolean {
            var light = false
            compose.runOnUiThread {
                light =
                    WindowCompat
                        .getInsetsController(
                            compose.activity.window,
                            compose.activity.window.decorView,
                        ).isAppearanceLightStatusBars
            }
            return light
        }
        runBlocking { app.container.settings.setTheme(ThemeMode.Light) }
        compose.waitUntil(5_000) { lightIcons() } // a light app: dark icons on it, whatever the phone's own setting
        runBlocking { app.container.settings.setTheme(ThemeMode.Dark) }
        compose.waitUntil(5_000) { !lightIcons() }
    }
}
