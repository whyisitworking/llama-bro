package com.suhel.llamabro.demo.designsystem

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.suhel.llamabro.demo.designsystem.samples.ActionsSamples
import com.suhel.llamabro.demo.designsystem.samples.BrandSamples
import com.suhel.llamabro.demo.designsystem.samples.ChatBubbleSamples
import com.suhel.llamabro.demo.designsystem.samples.ChatOpenSamples
import com.suhel.llamabro.demo.designsystem.samples.DownloadPanelSamples
import com.suhel.llamabro.demo.designsystem.samples.FitSamples
import com.suhel.llamabro.demo.designsystem.samples.FitSymbolSamples
import com.suhel.llamabro.demo.designsystem.samples.GapSamples
import com.suhel.llamabro.demo.designsystem.samples.InputSamples
import com.suhel.llamabro.demo.designsystem.samples.LayoutSamples
import com.suhel.llamabro.demo.designsystem.samples.ListsSamples
import com.suhel.llamabro.demo.designsystem.samples.ListsSheetSamples
import com.suhel.llamabro.demo.designsystem.samples.ModelRowSamples
import com.suhel.llamabro.demo.designsystem.samples.PhotoViewerSamples
import com.suhel.llamabro.demo.designsystem.samples.ProgressSamples
import com.suhel.llamabro.demo.designsystem.samples.RemovalSamples
import com.suhel.llamabro.demo.designsystem.samples.RowsSamples
import com.suhel.llamabro.demo.designsystem.samples.RowsSheetSamples
import com.suhel.llamabro.demo.designsystem.samples.ScreenSamples
import com.suhel.llamabro.demo.designsystem.samples.SheetSamples
import com.suhel.llamabro.demo.designsystem.samples.SurfacesSamples
import com.suhel.llamabro.demo.designsystem.samples.ThemeSamples
import com.suhel.llamabro.demo.designsystem.samples.VoiceModeSamples
import com.suhel.llamabro.demo.designsystem.theme.DemoTheme
import com.suhel.llamabro.demo.designsystem.theme.DemoType
import com.suhel.llamabro.demo.designsystem.theme.LocalDemoColors
import com.suhel.llamabro.demo.designsystem.theme.ThemeMode

/** Every component, light then dark, with the samples the screenshot tests record. Debug builds only. */
class GalleryActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { Gallery() }
    }
}

private val Groups: List<Pair<String, @Composable () -> Unit>> =
    listOf(
        "Theme" to { ThemeSamples() },
        "Brand" to { BrandSamples() },
        "Surfaces" to { SurfacesSamples() },
        "Sheet" to { SheetSamples() },
        "Fit" to { FitSamples() },
        "Fit symbols" to { FitSymbolSamples() },
        "Model rows" to { ModelRowSamples() },
        "Download panel" to { DownloadPanelSamples() },
        "Actions" to { ActionsSamples() },
        "Input" to { InputSamples() },
        "Lists" to { ListsSamples() },
        "Lists on a sheet" to { ListsSheetSamples() },
        "Removal" to { RemovalSamples() },
        "Chat, open page" to { ChatOpenSamples() },
        "Chat, bubbles" to { ChatBubbleSamples() },
        "Photo viewer" to { PhotoViewerSamples() },
        "Progress" to { ProgressSamples() },
        "Layout" to { LayoutSamples() },
        "Gaps" to { GapSamples() },
        "Rows" to { RowsSamples() },
        "Rows on a sheet" to { RowsSheetSamples() },
        "Screen" to { ScreenSamples() },
        "Voice mode" to { VoiceModeSamples() },
    )

@Composable
private fun Gallery() {
    DemoTheme(ThemeMode.Light) {
        LazyColumn(Modifier.fillMaxSize().background(LocalDemoColors.current.canvas).safeDrawingPadding()) {
            for (mode in listOf(ThemeMode.Light, ThemeMode.Dark)) {
                for ((name, samples) in Groups) {
                    item(key = "$mode/$name") {
                        DemoTheme(mode) {
                            Column(Modifier.fillMaxWidth().background(LocalDemoColors.current.canvas)) {
                                Text(
                                    "$name · $mode",
                                    Modifier.padding(start = 16.dp, top = 24.dp),
                                    color = LocalDemoColors.current.muted,
                                    style = DemoType.bodySmall,
                                )
                                samples()
                            }
                        }
                    }
                }
            }
        }
    }
}
