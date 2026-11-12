package com.suhel.llamabro.demo.designsystem

import androidx.compose.runtime.Composable
import com.android.tools.screenshot.PreviewTest
import com.suhel.llamabro.demo.designsystem.samples.GapSamples
import com.suhel.llamabro.demo.designsystem.samples.ScreenSamples
import com.suhel.llamabro.demo.designsystem.theme.DemoTheme

/** The layout primitives of the design system v2 plan (step 3): the gaps, and a screen built from them. */
class PrimitivesScreenshotTest {
    @PreviewTest
    @ComponentPreviews
    @Composable
    fun gaps() = DemoTheme { GapSamples() }

    @PreviewTest
    @ComponentPreviews
    @Composable
    fun screen() = DemoTheme { ScreenSamples() }
}
