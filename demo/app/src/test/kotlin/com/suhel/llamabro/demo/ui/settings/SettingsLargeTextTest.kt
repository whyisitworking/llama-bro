package com.suhel.llamabro.demo.ui.settings

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.suhel.llamabro.demo.designsystem.theme.DemoTheme
import com.suhel.llamabro.demo.designsystem.theme.ThemeMode
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Settings at twice the font on a 360 dp phone: every row's subtitle is read whole, none cut short. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w360dp-h2400dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsLargeTextTest {
    @get:Rule val rule = createComposeRule()

    @Test fun atTwiceTheFontNoSubtitleOnTheSettingsPageIsCut() {
        rule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                DemoTheme(ThemeMode.Light) {
                    SettingsHome(
                        onBack = {},
                        onOpen = {},
                        routes = SettingsRoutes("a", "b", "c", "d", "e", "f", "g", "h", "i"),
                    )
                }
            }
        }
        for (subtitle in SUBTITLES) {
            val results = mutableListOf<TextLayoutResult>()
            rule
                .onNodeWithText(subtitle, useUnmergedTree = true)
                .fetchSemanticsNode()
                .config
                .getOrNull(SemanticsActions.GetTextLayoutResult)
                ?.action
                ?.invoke(results)
            val layout = results.single()
            assertFalse("\"$subtitle\" is cut", (0 until layout.lineCount).any { layout.isLineEllipsized(it) })
        }
    }

    private companion object {
        val SUBTITLES =
            listOf(
                "Layout and theme",
                "Tell every chat about you",
                "Which model new chats start with",
                "For models that need one",
                "How each model picks its words",
                "How a model that talks sounds",
                "The model in memory, and when it's freed",
                "The models on this phone",
                "Versions and licences",
            )
    }
}
