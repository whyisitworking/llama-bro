package com.suhel.llamabro.demo.designsystem.samples

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.suhel.llamabro.demo.designsystem.components.WakeHairline
import com.suhel.llamabro.demo.designsystem.theme.LocalAnimatorDurationScale
import com.suhel.llamabro.demo.designsystem.theme.LocalDemoColors

/** The canvas the samples sit on: the screen's background with the 16 dp gutter. */
@Composable
internal fun SampleFrame(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().background(LocalDemoColors.current.canvas).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

/** Light and dark at the 360 dp width of Reference L's screenshots. */
@Preview(name = "Light", widthDp = 360)
@Preview(name = "Dark", widthDp = 360, uiMode = Configuration.UI_MODE_NIGHT_YES)
internal annotation class LightDarkPreviews

/** The wake hairline as it shows with animations off: a sample's fixed frame of it. */
@Composable
internal fun StillHairline() {
    CompositionLocalProvider(LocalAnimatorDurationScale provides 0f) { WakeHairline(active = true) }
}
