package com.suhel.llamabro.demo.lint

import com.android.tools.lint.checks.infrastructure.TestFiles.kotlin
import com.android.tools.lint.checks.infrastructure.TestLintTask.lint
import org.junit.Test

class DesignTokensDetectorTest {
    // just enough of Compose for calls to resolve
    private val stubs =
        arrayOf(
            kotlin(
                """
                package androidx.compose.runtime
                annotation class Composable
                """,
            ).indented(),
            kotlin(
                """
                package androidx.compose.ui.unit
                @JvmInline value class Dp(val value: Float)
                class TextUnit
                val Int.dp: Dp get() = Dp(toFloat())
                val Int.sp: TextUnit get() = TextUnit()
                """,
            ).indented(),
            kotlin(
                """
                package androidx.compose.ui.graphics
                class Color(val value: Long) { companion object { val Red = Color(1) } }
                """,
            ).indented(),
            kotlin(
                """
                package androidx.compose.ui.text
                import androidx.compose.ui.unit.TextUnit
                class TextStyle { fun copy(fontSize: TextUnit? = null, color: Long = 0): TextStyle = this }
                """,
            ).indented(),
            kotlin(
                """
                package androidx.compose.material3
                import androidx.compose.runtime.Composable
                @Composable fun Scaffold(content: () -> Unit) {}
                """,
            ).indented(),
        )

    // each source declares `package x`, replaced with the package its path names
    private fun check(path: String, source: String) =
        lint()
            .files(*stubs, kotlin(path, source.trimIndent().replace("package x", "package ${packageOf(path)}")))
            .issues(*DesignTokensDetector.ISSUES.toTypedArray())
            .allowMissingSdk()

    @Test fun aScreenWithANumberIsReported() {
        check(
            "src/main/kotlin/com/suhel/llamabro/demo/ui/Screen.kt",
            """
            package x
            import androidx.compose.ui.unit.dp
            fun gap() = 12.dp
            fun none() = 0.dp
            """,
        ).run().expectErrorCount(1).expectContains("DemoRawSize")
    }

    @Test fun aColourOutsideTheThemeIsReportedAndTheThemeIsNot() {
        val source =
            """
            package x
            import androidx.compose.ui.graphics.Color
            val a = Color(0xFF00FF00)
            val b = Color.Red
            """
        check(
            "src/main/kotlin/com/suhel/llamabro/demo/designsystem/components/Chip.kt",
            source,
        ).run().expectErrorCount(2)
        check("src/main/kotlin/com/suhel/llamabro/demo/designsystem/theme/DemoColors.kt", source).run().expectClean()
    }

    @Test fun aRoleChangedInPlaceIsReportedButAColourChangeIsNot() {
        check(
            "src/main/kotlin/com/suhel/llamabro/demo/designsystem/components/Label.kt",
            """
            package x
            import androidx.compose.ui.text.TextStyle
            import androidx.compose.ui.unit.sp
            fun a(s: TextStyle) = s.copy(fontSize = 14.sp)
            fun b(s: TextStyle) = s.copy(color = 1)
            """,
        ).run().expectContains("DemoRoleOverride").expectErrorCount(2) // the role override and its 14.sp
    }

    @Test fun aMaterialWidgetInAScreenIsReportedButNotInTheDesignSystem() {
        val source =
            """
            package x
            import androidx.compose.material3.Scaffold
            fun screen() = Scaffold {}
            """
        check(
            "src/main/kotlin/com/suhel/llamabro/demo/ui/Screen.kt",
            source,
        ).run().expectErrorCount(1).expectContains("DemoRawComponent")
        check("src/main/kotlin/com/suhel/llamabro/demo/designsystem/components/Frame.kt", source).run().expectClean()
    }

    @Test fun previewsAreNotScreens() {
        check(
            "src/main/kotlin/com/suhel/llamabro/demo/ui/ScreenPreviews.kt",
            """
            package x
            import androidx.compose.ui.unit.dp
            fun frame() = 360.dp
            """,
        ).run().expectClean()
    }

    private fun packageOf(path: String) =
        path.removePrefix("src/main/kotlin/").substringBeforeLast('/').replace('/', '.')
}
