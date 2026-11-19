package com.suhel.llamabro.demo.designsystem.samples

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.suhel.llamabro.demo.designsystem.components.DemoCard
import com.suhel.llamabro.demo.designsystem.components.DemoIcons
import com.suhel.llamabro.demo.designsystem.components.Gap
import com.suhel.llamabro.demo.designsystem.components.IconAction
import com.suhel.llamabro.demo.designsystem.components.Screen
import com.suhel.llamabro.demo.designsystem.components.Section
import com.suhel.llamabro.demo.designsystem.components.Stack
import com.suhel.llamabro.demo.designsystem.components.TopBar
import com.suhel.llamabro.demo.designsystem.components.TopBarTitle
import com.suhel.llamabro.demo.designsystem.theme.DemoType
import com.suhel.llamabro.demo.designsystem.theme.LocalDemoColors

/**
 * A [Screen] with two [Section]s of cards: the gutter on the left and right, [Gap.Section] between the sections,
 * the label [Gap.Inline] above its cards and the cards [Gap.Block] apart. What every screen is made of.
 */
@Composable
internal fun ScreenSamples() {
    val colors = LocalDemoColors.current
    Screen(
        topBar = {
            TopBar(
                title = { TopBarTitle("Models") },
                start = { IconAction(DemoIcons.Back, "Back", onClick = {}) },
            )
        },
        modifier = Modifier.height(480.dp).background(colors.canvas),
        scrolls = false,
    ) {
        Section("On this phone") {
            for (name in listOf("Qwen3.5 0.8B", "Gemma 3 4B")) {
                DemoCard(Modifier.fillMaxWidth()) {
                    Text(name, color = colors.ink, style = DemoType.title)
                    Text("Qwen3.5 0.8B · 0.7 GB", color = colors.muted, style = DemoType.bodyMedium)
                }
            }
        }
        Section("Downloading") {
            DemoCard(Modifier.fillMaxWidth()) {
                Text("Deep", color = colors.ink, style = DemoType.title)
            }
        }
    }
}

/** Every [Gap] drawn as a bar between two blocks, to see the scale. */
@Composable
internal fun GapSamples() {
    val colors = LocalDemoColors.current
    SampleFrame {
        for (gap in Gap.entries.drop(1)) {
            Text("${gap.name} · ${gap.size.value.toInt()} dp", color = colors.muted, style = DemoType.bodySmall)
            Stack(gap) {
                repeat(2) { Text("A block", Modifier.fillMaxWidth().background(colors.accentSoft), color = colors.ink) }
            }
        }
    }
}
