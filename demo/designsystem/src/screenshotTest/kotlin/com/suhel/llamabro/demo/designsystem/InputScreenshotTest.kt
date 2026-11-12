package com.suhel.llamabro.demo.designsystem

import androidx.compose.runtime.Composable
import com.android.tools.screenshot.PreviewTest
import com.suhel.llamabro.demo.designsystem.samples.InputSamples
import com.suhel.llamabro.demo.designsystem.theme.DemoTheme

/** Reference H's Input group in Reference L's screenshot matrix. */
class InputScreenshotTest {
    @PreviewTest
    @ComponentPreviews
    @Composable
    fun input() = DemoTheme { InputSamples() }
}
