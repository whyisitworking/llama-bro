package com.suhel.llamabro.demo.designsystem

import androidx.compose.runtime.Composable
import com.android.tools.screenshot.PreviewTest
import com.suhel.llamabro.demo.designsystem.samples.FitSamples
import com.suhel.llamabro.demo.designsystem.theme.DemoTheme

/** Reference H's Fit group in Reference L's screenshot matrix. */
class FitScreenshotTest {
    @PreviewTest
    @ComponentPreviews
    @Composable
    fun fit() = DemoTheme { FitSamples() }
}
