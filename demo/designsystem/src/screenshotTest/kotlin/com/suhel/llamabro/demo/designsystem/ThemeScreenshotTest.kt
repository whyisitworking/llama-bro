package com.suhel.llamabro.demo.designsystem

import androidx.compose.runtime.Composable
import com.android.tools.screenshot.PreviewTest
import com.suhel.llamabro.demo.designsystem.samples.ThemeSamples
import com.suhel.llamabro.demo.designsystem.theme.DemoTheme

/** Reference H's Theme group in Reference L's screenshot matrix. */
class ThemeScreenshotTest {
    @PreviewTest
    @ComponentPreviews
    @Composable
    fun theme() = DemoTheme { ThemeSamples() }
}
