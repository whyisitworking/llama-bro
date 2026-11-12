package com.suhel.llamabro.demo.designsystem

import androidx.compose.runtime.Composable
import com.android.tools.screenshot.PreviewTest
import com.suhel.llamabro.demo.designsystem.samples.ListsSamples
import com.suhel.llamabro.demo.designsystem.samples.ListsSheetSamples
import com.suhel.llamabro.demo.designsystem.samples.RemovalSamples
import com.suhel.llamabro.demo.designsystem.theme.DemoTheme

/** Reference H's Lists group in Reference L's screenshot matrix. */
class ListsScreenshotTest {
    @PreviewTest
    @ComponentPreviews
    @Composable
    fun lists() = DemoTheme { ListsSamples() }

    @PreviewTest
    @ComponentPreviews
    @Composable
    fun listsSheet() = DemoTheme { ListsSheetSamples() }

    @PreviewTest
    @ComponentPreviews
    @Composable
    fun removal() = DemoTheme { RemovalSamples() }
}
