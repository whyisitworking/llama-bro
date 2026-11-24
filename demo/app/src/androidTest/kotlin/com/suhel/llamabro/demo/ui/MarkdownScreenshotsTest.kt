package com.suhel.llamabro.demo.ui

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.suhel.llamabro.demo.designsystem.components.MarkdownText
import com.suhel.llamabro.demo.designsystem.theme.DemoTheme
import com.suhel.llamabro.demo.designsystem.theme.LocalDemoColors
import com.suhel.llamabro.demo.designsystem.theme.ThemeMode
import com.suhel.llamabro.demo.testing.TestAppRule
import com.suhel.llamabro.demo.testing.demoRules
import com.suhel.llamabro.demo.ui.chat.Markdown
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.io.File

/**
 * A picture of every markdown construct a reply may use, parsed by [Markdown] and drawn by [MarkdownText], kept as a
 * regression preview of the renderer (D74): `adb pull
 * /sdcard/Android/data/com.suhel.llamabro.demo/files/screens/markdown`. They are looked at, not compared: a pass means
 * each sample parsed and rendered.
 */
@RunWith(Parameterized::class)
class MarkdownScreenshotsTest(private val theme: ThemeMode) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun cases() = listOf(arrayOf(ThemeMode.Light), arrayOf(ThemeMode.Dark))

        /** One sample per construct, named as the picture it makes. */
        val Samples =
            listOf(
                "headings" to "# Heading one\n\nText under one.\n\n## Heading two\n\nText under two.\n\n" +
                    "### Heading three\n\nText under three.\n\n#### Heading four\n\nText under four.",
                "emphasis" to "*star italic* and _underscore italic_\n\n**star bold** and __underscore bold__\n\n" +
                    "***bold italic*** and ~~struck through~~\n\nInline `code()` in a sentence.\n\n" +
                    "Escapes: \\*not italic\\* and 2 \\* 3 and a_b_c",
                "lists" to "- Fruit\n  - Apples\n    - Gala\n    - Fuji\n  - Pears\n- Vegetables\n\n" +
                    "1. First step\n2. Second step\n   - a detail\n   - another\n" +
                    "     1. deeper\n     2. deeper still\n" +
                    "3. Third step\n\n* star item\n+ plus item",
                "table" to "| Left | Centre | Right |\n|:-----|:------:|------:|\n| a | b | 1.00 |\n" +
                    "| longer cell | **bold** | 22.50 |\n| `code` | [link](https://example.com) | 333 |\n\n" +
                    "| Model | Size | Speed | Context | Notes about this model |\n|---|---|---|---|---|\n" +
                    "| Qwen3.5 0.8B | 739 MB | 41 words/s | 32k | quick, good for short answers |\n" +
                    "| Qwen3.5 4B | 3.4 GB | 12 words/s | 128k | slower, deeper, more careful |",
                "code" to "Here is code:\n\n```kotlin\nfun main() {\n    val xs = listOf(1, 2, 3)\n" +
                    "    println(xs.asReversed()) // a very long comment that runs past the edge of the screen\n" +
                    "}\n```\n\n" +
                    "And inline `val x = 1` too.",
                "quotes" to "> A quote that runs\n> over two lines.\n>\n> And a second paragraph.\n\n" +
                    "> Outer quote\n>\n> > Nested quote inside it\n>\n> Back to the outer one.",
                "links" to "See [the Kotlin docs](https://kotlinlang.org/docs/home.html) for more.\n\n" +
                    "A bare URL: https://example.com/path and <https://example.org>.",
                "rules" to "Above dashes\n\n---\n\nAbove stars\n\n***\n\nAbove spaced stars\n\n* * *\n\nBelow.",
                "maths" to "Inline maths: \$x^2 + y^2 = z^2\$.\n\n\$\$\n\\int_0^1 x^2 \\, dx = \\frac{1}{3}\n\$\$\n\n" +
                    "Text after.\n\n![a llama](https://example.com/llama.png) and <b>inline html</b>\n\n<div>\nan html block\n</div>",
            )
    }

    private val app = TestAppRule()
    private val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule val rules: RuleChain = RuleChain.outerRule(demoRules(app)).around(compose)

    @Test fun everyConstruct() {
        val dir =
            File(
                InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir("screens"),
                "markdown",
            ).apply { mkdirs() }
        var sample by mutableStateOf(Samples.first().second)
        compose.setContent {
            DemoTheme(theme) {
                Box(Modifier.fillMaxSize().background(LocalDemoColors.current.canvas).padding(16.dp)) {
                    MarkdownText(Markdown.parse(sample), onCopyCode = {})
                }
            }
        }
        for ((name, text) in Samples) {
            sample = text
            compose.waitForIdle()
            val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
            File(dir, "$name-${theme.name.lowercase()}.png").outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        }
    }
}
