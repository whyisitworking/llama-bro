package com.suhel.llamabro.demo.ui.preview

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.suhel.llamabro.demo.designsystem.theme.DemoTheme
import com.suhel.llamabro.demo.designsystem.theme.LocalDemoColors

/** Light and dark at the phone width Reference L's screenshots use: every preview of a screen or a part carries it. */
@Preview(name = "Light", widthDp = 360, showBackground = false)
@Preview(name = "Dark", widthDp = 360, uiMode = Configuration.UI_MODE_NIGHT_YES, showBackground = false)
annotation class DemoPreviews

/** The same two on a full phone screen, for whole screens. */
@Preview(name = "Light", device = "spec:width=360dp,height=780dp,dpi=420", showBackground = false)
@Preview(
    name = "Dark",
    device = "spec:width=360dp,height=780dp,dpi=420",
    uiMode = Configuration.UI_MODE_NIGHT_YES,
    showBackground = false,
)
annotation class DemoScreenPreviews

/** The theme (following the preview's light or dark setting) over the canvas colour a real screen sits on. */
@Composable
fun PreviewFrame(content: @Composable () -> Unit) {
    DemoTheme {
        Box(Modifier.fillMaxWidth().background(LocalDemoColors.current.canvas)) { content() }
    }
}
