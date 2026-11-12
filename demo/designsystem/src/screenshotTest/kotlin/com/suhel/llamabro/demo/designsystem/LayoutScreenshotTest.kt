package com.suhel.llamabro.demo.designsystem

import androidx.compose.runtime.Composable
import com.android.tools.screenshot.PreviewTest
import com.suhel.llamabro.demo.designsystem.samples.DrawerSample
import com.suhel.llamabro.demo.designsystem.samples.LayoutSamples
import com.suhel.llamabro.demo.designsystem.theme.DemoTheme

/** Reference H's Layout group in Reference L's screenshot matrix. */
class LayoutScreenshotTest {
    @PreviewTest
    @ComponentPreviews
    @Composable
    fun layout() = DemoTheme { LayoutSamples() }

    @PreviewTest
    @ComponentPreviews
    @Composable
    fun drawer() = DemoTheme { DrawerSample() }
}
