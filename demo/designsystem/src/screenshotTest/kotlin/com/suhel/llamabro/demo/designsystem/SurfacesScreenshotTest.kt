package com.suhel.llamabro.demo.designsystem

import androidx.compose.runtime.Composable
import com.android.tools.screenshot.PreviewTest
import com.suhel.llamabro.demo.designsystem.samples.SheetSamples
import com.suhel.llamabro.demo.designsystem.samples.SurfacesSamples
import com.suhel.llamabro.demo.designsystem.theme.DemoTheme

/** Reference H's Surfaces group in Reference L's screenshot matrix. */
class SurfacesScreenshotTest {
    @PreviewTest
    @ComponentPreviews
    @Composable
    fun surfaces() = DemoTheme { SurfacesSamples() }

    @PreviewTest
    @ComponentPreviews
    @Composable
    fun sheet() = DemoTheme { SheetSamples() }
}
