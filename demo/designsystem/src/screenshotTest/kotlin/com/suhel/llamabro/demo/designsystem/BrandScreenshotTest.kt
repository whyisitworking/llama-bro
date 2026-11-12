package com.suhel.llamabro.demo.designsystem

import androidx.compose.runtime.Composable
import com.android.tools.screenshot.PreviewTest
import com.suhel.llamabro.demo.designsystem.samples.BrandSamples
import com.suhel.llamabro.demo.designsystem.theme.DemoTheme

/** Reference H's Brand group in Reference L's screenshot matrix. */
class BrandScreenshotTest {
    @PreviewTest
    @ComponentPreviews
    @Composable
    fun brand() = DemoTheme { BrandSamples() }
}
