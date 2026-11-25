package com.suhel.llamabro.demo.designsystem.samples

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.suhel.llamabro.demo.designsystem.components.DemoIcons
import com.suhel.llamabro.demo.designsystem.components.DownloadPanel
import com.suhel.llamabro.demo.designsystem.components.DownloadStage
import com.suhel.llamabro.demo.designsystem.components.DownloadView
import com.suhel.llamabro.demo.designsystem.components.FitLevel
import com.suhel.llamabro.demo.designsystem.components.MarkdownBlock
import com.suhel.llamabro.demo.designsystem.components.MarkdownBlocks
import com.suhel.llamabro.demo.designsystem.components.MarkdownSpan
import com.suhel.llamabro.demo.designsystem.components.MarkdownText
import com.suhel.llamabro.demo.designsystem.components.ModelRow
import com.suhel.llamabro.demo.designsystem.theme.DemoTheme

private val stages =
    listOf(
        DownloadView(DownloadStage.Downloading, "Q4_K_M", 0.27f, "0.9 of 3.4 GB · 4 min left"),
        DownloadView(DownloadStage.Paused, "Q4_K_M", 0.27f, "0.9 of 3.4 GB"),
        DownloadView(DownloadStage.Waiting, "Q4_K_M", 0f, "after Gemma 3 4B"),
        DownloadView(DownloadStage.Stopped, "UD-Q4_K_XL", 0.27f, "The connection dropped · 0.9 of 3.4 GB kept"),
    )

/** The ring download panel in each stage, at the card foot's 64 dp, then compact as a row's footer. */
@Composable
internal fun DownloadPanelSamples() {
    SampleFrame {
        for (view in stages) DownloadPanel(view, onToggle = {}, onCancel = {})
        for (view in stages) DownloadPanel(view, onToggle = {}, onCancel = {}, compact = true)
    }
}

/**
 * The one model row (D123): a Hugging Face result, a suggestion before its fit is known, the default model on the
 * phone, a model that can't chat, a row in each download stage with its compact panel, and a model's page whose link
 * the app takes (a linked model opens in the card's sheet, D156).
 */
@Composable
internal fun ModelRowSamples() {
    val capabilities = listOf(DemoIcons.CapabilityThinks to "Thinks", DemoIcons.CapabilityImages to "Sees images")
    SampleFrame {
        ModelRow(
            "Gemma 3 4B",
            FitLevel.Well,
            "Runs well",
            maker = "Google",
            size = "3.4 GB",
            state = null,
            capabilities = capabilities,
            // a Hugging Face result: its download count is line 2's quiet last item
            fact = "1.2M downloads",
            onClick = {},
        )
        ModelRow(
            "Qwen3.5 4B",
            null,
            "",
            maker = "Qwen",
            size = "about 2.7 GB",
            state = null,
            capabilities = capabilities,
            fact = null,
        )
        ModelRow(
            "Qwen3.5 0.8B",
            FitLevel.Great,
            "Runs great",
            maker = "Qwen",
            size = "0.6 GB",
            state = "Default",
            capabilities = listOf(DemoIcons.CapabilityThinks to "Thinks"),
            fact = null,
        )
        ModelRow(
            "Qwen3-Embedding 0.6B",
            FitLevel.CantChat,
            "Can't chat",
            maker = "Qwen",
            size = "0.6 GB",
            state = null,
            // a model that can't chat: its symbol says so; the name stays in the ink and no embeddings glyph repeats it
            capabilities = emptyList(),
            fact = null,
        )
        for (view in stages) {
            ModelRow(
                "Llama 3.2 3B",
                FitLevel.Slow,
                "Slow here",
                maker = "Meta",
                size = "3.4 GB",
                // the footer says where the download stands: no mark on line 2
                state = null,
                capabilities = emptyList(),
                fact = null,
                download = { DownloadPanel(view, onToggle = {}, onCancel = {}, compact = true) },
            )
        }
        MarkdownText(LinkedPage, onCopyCode = {}, page = true, onLink = {})
    }
}

/** A line of a model's page that links its original, as a quantized upload's page does. */
private val LinkedPage =
    MarkdownBlocks(
        listOf(
            MarkdownBlock.Paragraph(
                listOf(
                    MarkdownSpan("Quantized from "),
                    MarkdownSpan("Qwen/Qwen3.5-4B", link = "https://huggingface.co/Qwen/Qwen3.5-4B"),
                    MarkdownSpan(" with llama.cpp."),
                ),
            ),
        ),
    )

@LightDarkPreviews
@Composable
private fun DownloadPanelPreview() = DemoTheme { DownloadPanelSamples() }

@LightDarkPreviews
@Composable
private fun ModelRowPreview() = DemoTheme { ModelRowSamples() }

@Preview(name = "Light, 2x", widthDp = 360, heightDp = 2400, fontScale = 2f)
@Preview(name = "Dark, 2x", widthDp = 360, heightDp = 2400, fontScale = 2f, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun ModelRowLargePreview() = DemoTheme { ModelRowSamples() }

@Preview(name = "Light, 2x", widthDp = 360, fontScale = 2f)
@Preview(name = "Dark, 2x", widthDp = 360, fontScale = 2f, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun DownloadPanelLargePreview() = DemoTheme { DownloadPanelSamples() }
