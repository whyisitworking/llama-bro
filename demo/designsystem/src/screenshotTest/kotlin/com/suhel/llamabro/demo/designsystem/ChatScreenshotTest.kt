package com.suhel.llamabro.demo.designsystem

import androidx.compose.runtime.Composable
import com.android.tools.screenshot.PreviewTest
import com.suhel.llamabro.demo.designsystem.samples.ChatBubbleSamples
import com.suhel.llamabro.demo.designsystem.samples.ChatOpenSamples
import com.suhel.llamabro.demo.designsystem.theme.DemoTheme

/** Reference H's Chat group in Reference L's screenshot matrix. */
class ChatScreenshotTest {
    @PreviewTest
    @ComponentPreviews
    @Composable
    fun chatOpen() = DemoTheme { ChatOpenSamples() }

    @PreviewTest
    @ComponentPreviews
    @Composable
    fun chatBubble() = DemoTheme { ChatBubbleSamples() }
}
