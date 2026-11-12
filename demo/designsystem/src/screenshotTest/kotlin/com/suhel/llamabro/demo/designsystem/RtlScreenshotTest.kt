package com.suhel.llamabro.demo.designsystem

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.LayoutDirection
import com.android.tools.screenshot.PreviewTest
import com.suhel.llamabro.demo.designsystem.samples.ChatBubbleSamples
import com.suhel.llamabro.demo.designsystem.samples.ChatOpenSamples
import com.suhel.llamabro.demo.designsystem.samples.DrawerSample
import com.suhel.llamabro.demo.designsystem.samples.InputSamples
import com.suhel.llamabro.demo.designsystem.samples.RowsSamples
import com.suhel.llamabro.demo.designsystem.samples.ScreenSamples
import com.suhel.llamabro.demo.designsystem.theme.DemoTheme

@Preview(name = "rtl-light-360", widthDp = 360, heightDp = 1400)
@Preview(name = "rtl-dark-360", widthDp = 360, heightDp = 1400, uiMode = Configuration.UI_MODE_NIGHT_YES)
annotation class RtlPreviews

@Composable
private fun Rtl(content: @Composable () -> Unit) =
    DemoTheme { CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl, content = content) }

/**
 * The layouts the design system owns, mirrored: the gutter, the rows' chevrons and checks, the bubbles' sides, the composer's tools.
 * Nothing is placed with a fixed left or right.
 */
class RtlScreenshotTest {
    @PreviewTest
    @RtlPreviews
    @Composable
    fun screen() = Rtl { ScreenSamples() }

    @PreviewTest
    @RtlPreviews
    @Composable
    fun rows() = Rtl { RowsSamples() }

    @PreviewTest
    @RtlPreviews
    @Composable
    fun chatOpen() = Rtl { ChatOpenSamples() }

    @PreviewTest
    @RtlPreviews
    @Composable
    fun chatBubbles() = Rtl { ChatBubbleSamples() }

    @PreviewTest
    @RtlPreviews
    @Composable
    fun input() = Rtl { InputSamples() }

    @PreviewTest
    @RtlPreviews
    @Composable
    fun drawer() = Rtl { DrawerSample() }
}
