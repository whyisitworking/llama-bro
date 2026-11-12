package com.suhel.llamabro.demo.designsystem

import androidx.compose.runtime.Composable
import com.android.tools.screenshot.PreviewTest
import com.suhel.llamabro.demo.designsystem.samples.ProgressSamples
import com.suhel.llamabro.demo.designsystem.theme.DemoTheme

/** Reference H's Progress group in Reference L's screenshot matrix. */
class ProgressScreenshotTest {
    @PreviewTest
    @ComponentPreviews
    @Composable
    fun progress() = DemoTheme { ProgressSamples() }
}
