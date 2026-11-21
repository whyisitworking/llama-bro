package com.suhel.llamabro.demo.designsystem.samples

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.suhel.llamabro.demo.designsystem.components.DownloadRing
import com.suhel.llamabro.demo.designsystem.components.LoadingSlot
import com.suhel.llamabro.demo.designsystem.components.StorageBar
import com.suhel.llamabro.demo.designsystem.components.ThinBar
import com.suhel.llamabro.demo.designsystem.components.TopBar
import com.suhel.llamabro.demo.designsystem.components.TopBarTitle
import com.suhel.llamabro.demo.designsystem.components.WakeHairline
import com.suhel.llamabro.demo.designsystem.theme.DemoTheme
import com.suhel.llamabro.demo.designsystem.theme.DemoType
import com.suhel.llamabro.demo.designsystem.theme.LocalDemoColors

/**
 * The download ring beside a model arriving, the wake hairline (sweeping, still, filling to a load's progress,
 * and off), the thin bar and the storage bar.
 */
@Composable
internal fun ProgressSamples() {
    val c = LocalDemoColors.current
    SampleFrame {
        TopBar(
            title = { TopBarTitle("Qwen3.5 0.8B", subtitle = "Arriving · 38% · about 40 s") },
            end = { DownloadRing(0.38f, label = "Arriving · 38% · about 40 s") },
            divider = true,
        )
        WakeHairline(active = true)
        StillHairline()
        WakeHairline(active = true, progress = 0.6f)
        WakeHairline(active = false)
        LoadingSlot(active = true)
        LoadingSlot(active = false)
        ThinBar(0.42f)
        Text("6.1 GB used · 120 GB free", color = c.muted, style = DemoType.bodySmall)
        StorageBar(used = 6_100_000_000, total = 19_700_000_000)
    }
}

@LightDarkPreviews
@Composable
private fun ProgressPreview() = DemoTheme { ProgressSamples() }
