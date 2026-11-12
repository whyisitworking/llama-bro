package com.suhel.llamabro.demo.designsystem

import androidx.compose.runtime.Composable
import com.android.tools.screenshot.PreviewTest
import com.suhel.llamabro.demo.designsystem.samples.ActionsSamples
import com.suhel.llamabro.demo.designsystem.theme.DemoTheme

/** Reference H's Actions group in Reference L's screenshot matrix. */
class ActionsScreenshotTest {
    @PreviewTest
    @ComponentPreviews
    @Composable
    fun actions() = DemoTheme { ActionsSamples() }
}
