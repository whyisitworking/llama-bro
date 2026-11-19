package com.suhel.llamabro.demo.designsystem.samples

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.suhel.llamabro.demo.designsystem.components.CardTint
import com.suhel.llamabro.demo.designsystem.components.DemoCard
import com.suhel.llamabro.demo.designsystem.components.FitText
import com.suhel.llamabro.demo.designsystem.theme.DemoTheme
import com.suhel.llamabro.demo.designsystem.theme.DemoType
import com.suhel.llamabro.demo.designsystem.theme.LocalDemoColors

/** Suggested for this phone (models.html): every fit tint with its label in the matching ink. */
@Composable
internal fun FitSamples() {
    val c = LocalDemoColors.current
    SampleFrame {
        listOf(
            Triple("Qwen3.5 0.8B", "Tiny and quick, everyday questions" to "Runs great", CardTint.Great),
            Triple("Llama 3.2 3B", "Friendly all-rounder" to "Runs great", CardTint.Great),
            Triple("Gemma 3 4B", "Writes beautifully, reads images" to "Runs well", CardTint.Plain),
            Triple("Qwen3.5 9B", "Smarter, but heavy for a phone" to "Slow here", CardTint.Slow),
            Triple("Qwen3.5 35B", "Needs a computer, not a phone" to "Too big", CardTint.TooBig),
        ).forEach { (name, rest, tint) ->
            DemoCard(modifier = Modifier.fillMaxWidth()) {
                Row {
                    Text(
                        name,
                        Modifier.weight(1f),
                        color =
                            if (tint == CardTint.TooBig) {
                                c.tooBigInk
                            } else {
                                c.ink
                            },
                        style = DemoType.title,
                    )
                    FitText(rest.second, tint)
                }
                Text(rest.first, color = c.muted, style = DemoType.bodyMedium)
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            CardTint.entries.forEach { FitText("$it", it) }
        }
    }
}

@LightDarkPreviews
@Composable
private fun FitPreview() = DemoTheme { FitSamples() }
