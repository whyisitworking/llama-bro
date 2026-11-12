package com.suhel.llamabro.demo.designsystem

import androidx.compose.runtime.Composable
import com.android.tools.screenshot.PreviewTest
import com.suhel.llamabro.demo.designsystem.samples.RowsSamples
import com.suhel.llamabro.demo.designsystem.samples.RowsSheetSamples
import com.suhel.llamabro.demo.designsystem.theme.DemoTheme

/** The list row and the grouped list of the design system v2 plan (step 4). */
class RowsScreenshotTest {
    @PreviewTest
    @ComponentPreviews
    @Composable
    fun rows() = DemoTheme { RowsSamples() }

    @PreviewTest
    @ComponentPreviews
    @Composable
    fun rowsOnASheet() = DemoTheme { RowsSheetSamples() }
}
