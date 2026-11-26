package com.suhel.llamabro.demo.designsystem.samples

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.suhel.llamabro.demo.designsystem.theme.DemoShapes
import com.suhel.llamabro.demo.designsystem.theme.DemoTheme
import com.suhel.llamabro.demo.designsystem.theme.DemoType
import com.suhel.llamabro.demo.designsystem.theme.LocalDemoColors

/** The tokens and the type scale. */
@Composable
internal fun ThemeSamples() {
    val c = LocalDemoColors.current
    SampleFrame {
        val swatches =
            listOf(
                "canvas" to c.canvas,
                "surface" to c.surface,
                "ink" to c.ink,
                "muted" to c.muted,
                "hairline" to c.hairline,
                "accent" to c.accent,
                "onAccent" to c.onAccent,
                "accentSoft" to c.accentSoft,
                "greatInk" to c.greatInk,
                "slowInk" to c.slowInk,
                "tooBigInk" to c.tooBigInk,
            )
        swatches.chunked(4).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { (name, color) -> Swatch(name, color) }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Good evening", style = DemoType.displayHero, color = c.ink)
            Text("Qwen3.5 0.8B", style = DemoType.sheetTitle, color = c.ink)
            Text("What's on your mind?", style = DemoType.displayHero, color = c.ink)
            Text("Portfolio bio", style = DemoType.title, color = c.ink)
            Text(
                "Pick a mind to talk to. It lives on this phone: private, and it works offline.",
                style = DemoType.body,
                color = c.ink,
            )
            Text("Instant replies, everyday questions", style = DemoType.bodyMedium, color = c.muted)
            Text("38 words/s", style = DemoType.bodySmall, color = c.muted)
            Text("18.4", style = DemoType.glance, color = c.ink)
            Text("val reversed = list.asReversed()", style = DemoType.code, color = c.ink)
        }
    }
}

@Composable
private fun Swatch(name: String, color: Color) {
    val c = LocalDemoColors.current
    Column {
        Box(
            Modifier
                .size(
                    width = 72.dp,
                    height = 36.dp,
                ).background(color, DemoShapes.chip)
                .border(1.dp, c.hairline, DemoShapes.chip),
        )
        Text(name, style = DemoType.bodySmall, color = c.muted)
    }
}

@LightDarkPreviews
@Composable
private fun ThemePreview() = DemoTheme { ThemeSamples() }
