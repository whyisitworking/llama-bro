package com.suhel.llamabro.demo.designsystem.samples

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.suhel.llamabro.demo.designsystem.components.DemoCard
import com.suhel.llamabro.demo.designsystem.components.EdgeChip
import com.suhel.llamabro.demo.designsystem.components.FactCard
import com.suhel.llamabro.demo.designsystem.components.FitLevel
import com.suhel.llamabro.demo.designsystem.components.FitText
import com.suhel.llamabro.demo.designsystem.components.ModelName
import com.suhel.llamabro.demo.designsystem.components.PrimaryButton
import com.suhel.llamabro.demo.designsystem.components.SheetFrame
import com.suhel.llamabro.demo.designsystem.theme.DemoTheme
import com.suhel.llamabro.demo.designsystem.theme.DemoType
import com.suhel.llamabro.demo.designsystem.theme.LocalDemoColors

/** A first-run pick card (first-run.html): name and line on the left, size and fit on the right. */
@Composable
internal fun PickCard(
    name: String,
    line: String,
    size: String,
    fit: String,
    level: FitLevel?,
) {
    val c = LocalDemoColors.current
    DemoCard(modifier = Modifier.fillMaxWidth(), onClick = {}) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(name, color = c.ink, style = DemoType.title)
                Text(line, color = c.muted, style = DemoType.bodyMedium)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(size, color = c.muted, style = DemoType.bodyMedium)
                if (level != null) FitText(fit, level)
            }
        }
    }
}

/** The first run's three picks, the remaining tints, an outlined card with its edge chip, and the pick sheet. */
@Composable
internal fun SurfacesSamples() {
    val c = LocalDemoColors.current
    SampleFrame {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PickCard("Qwen3.5 0.8B", "Instant replies, everyday questions", "0.7 GB", "Runs great", FitLevel.Great)
            PickCard("Gemma 3 4B", "Thoughtful writing and ideas", "2.5 GB", "Runs well", FitLevel.Well)
            PickCard("Deep", "Thinks first, reads images", "2.8 GB", "A bit slow", FitLevel.Slow)
            PickCard("Qwen3.5 35B", "Needs a computer, not a phone", "21 GB", "Too big", FitLevel.TooBig)
            PickCard("Get more models", "Search Hugging Face", "", "", null)
        }
        DemoCard(modifier = Modifier.fillMaxWidth(), selected = true, edgeLabel = "Default") {
            Text("Gemma 3 4B", color = c.ink, style = DemoType.title)
            Text("Gemma 3 4B · 2.5 GB", color = c.muted, style = DemoType.bodyMedium)
        }
        EdgeChip("Recommended")
    }
}

/** The pick sheet of first-run.html: names, about, the tinted fit line, four facts, Get and the source. */
@Composable
internal fun SheetSamples() {
    val c = LocalDemoColors.current
    SampleFrame {
        SheetFrame {
            ModelName("Qwen3.5 0.8B", realName = "by Alibaba · by Alibaba Qwen")
            Text(
                "Small and quick. Good for short questions, quick rewrites and casual chat. For long documents or " +
                    "tricky reasoning, Gemma 3 4B or Deep do better.",
                Modifier.padding(vertical = 10.dp),
                color = c.muted,
                style = DemoType.body,
            )
            DemoCard(modifier = Modifier.fillMaxWidth()) {
                FitText("Runs great on this phone", FitLevel.Great)
            }
            Column(Modifier.padding(vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FactCard("Thinking", "Can think first, or not", Modifier.weight(1f))
                    FactCard("Images", "Yes, it can see them", Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FactCard("Memory", "long chats", Modifier.weight(1f))
                    FactCard("Download", "740 MB · about 1 min", Modifier.weight(1f))
                }
            }
            PrimaryButton("Get Qwen3.5 0.8B", onClick = {}, modifier = Modifier.fillMaxWidth())
            Text(
                "From Hugging Face",
                Modifier.fillMaxWidth().padding(top = 10.dp),
                color = c.muted,
                style = DemoType.bodySmall,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@LightDarkPreviews
@Composable
private fun SurfacesPreview() = DemoTheme { SurfacesSamples() }

@LightDarkPreviews
@Composable
private fun SheetPreview() = DemoTheme { SheetSamples() }
