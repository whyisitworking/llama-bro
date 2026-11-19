package com.suhel.llamabro.demo.ui.models

import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.suhel.llamabro.demo.chat.Limits
import com.suhel.llamabro.demo.designsystem.components.Gap
import com.suhel.llamabro.demo.designsystem.components.ModelCard
import com.suhel.llamabro.demo.designsystem.components.Stack
import com.suhel.llamabro.demo.designsystem.components.ThinBar
import com.suhel.llamabro.demo.designsystem.testing.CountRecompositions
import com.suhel.llamabro.demo.designsystem.theme.DemoType
import com.suhel.llamabro.demo.designsystem.theme.LocalDemoColors
import com.suhel.llamabro.demo.models.DownloadStatus
import com.suhel.llamabro.demo.models.FitView
import com.suhel.llamabro.demo.models.Format

/**
 * A model as a row of suggestions or results: the name and how it runs here, one plain line, what it can do, and, when
 * a
 * download started from this row, its progress. [fit] is null until the estimate arrives (the card stays plain).
 */
@Composable
fun ModelRowItem(
    row: ModelRow,
    fit: FitView?,
    download: DownloadStatus?,
    onPhone: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    CountRecompositions("model-${row.groupKey}")
    ModelCard(
        name = row.name,
        line = row.line,
        modifier = modifier.testTag("model_row"),
        fit = fit?.let { it.label.text() to it.label.tint() },
        facts = facts(row),
        onPhone = onPhone,
        onClick = onClick,
        footer = download?.let { { DownloadLine(it) } },
    )
}

/** "Thinks", "Talks", "Sees images" and the download count, quietly, on one line; null when none. */
private fun facts(row: ModelRow): String? =
    listOfNotNull(
        "Thinks".takeIf { row.thinks },
        "Talks".takeIf { row.catalog?.talks == true },
        "Sees images".takeIf { row.sees },
        row.downloads?.let { "↓ ${Format.downloads(it)}" },
    ).takeIf { it.isNotEmpty() }?.joinToString("  ·  ")

/** What a download started from this row is doing: a thin bar and "Downloading · 42%", or why it stopped. */
@Composable
private fun DownloadLine(status: DownloadStatus?, modifier: Modifier = Modifier) {
    when (status) {
        is DownloadStatus.Downloading -> {
            DownloadProgressLine(
                status.fraction(),
                "Downloading · ${status.percent()}%",
                status.etaSeconds?.let(Format::timeLeft),
                modifier,
            )
        }

        is DownloadStatus.Partial -> {
            DownloadProgressLine(
                status.bytesDone.toFloat() / status.bytesTotal.coerceAtLeast(1),
                "Paused",
                null,
                modifier,
            )
        }

        is DownloadStatus.Failed -> {
            Text(
                Limits.forDownload(status).line,
                modifier,
                color = LocalDemoColors.current.slowInk,
                style = DemoType.bodySmall,
            )
        }

        DownloadStatus.Complete, DownloadStatus.Absent, null -> {
            Unit
        }
    }
}

@Composable
private fun DownloadProgressLine(fraction: Float, label: String, timeLeft: String?, modifier: Modifier = Modifier) {
    val colors = LocalDemoColors.current
    Stack(Gap.Block, modifier) {
        ThinBar(fraction)
        Row {
            Text(label, Modifier.weight(1f), color = colors.muted, style = DemoType.bodySmall)
            if (timeLeft != null) Text(timeLeft, color = colors.muted, style = DemoType.bodySmall)
        }
    }
}

internal fun DownloadStatus.Downloading.fraction(): Float = bytesDone.toFloat() / bytesTotal.coerceAtLeast(1)

internal fun DownloadStatus.Downloading.percent(): Int = (fraction() * 100).toInt().coerceIn(0, 100)
