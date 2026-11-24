package com.suhel.llamabro.demo.designsystem.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.suhel.llamabro.demo.designsystem.theme.DemoSize
import com.suhel.llamabro.demo.designsystem.theme.DemoSpacing
import com.suhel.llamabro.demo.designsystem.theme.DemoType
import com.suhel.llamabro.demo.designsystem.theme.LocalDemoColors

private val QuantPadding = PaddingValues(horizontal = DemoSpacing.item)

/**
 * The quant table's grid: the size and fit columns are sized in sp so they grow with the font. At large font
 * ([LARGE_TEXT_SCALE]) the three columns no longer fit a phone's width, so each row stacks the quant over size and fit
 * (still one height for every row).
 */
@Immutable
private class QuantGrid(val stacked: Boolean, val sizeWidth: Dp, val fitWidth: Dp, val rowHeight: Dp)

@Composable
private fun rememberQuantGrid(): QuantGrid {
    val density = LocalDensity.current
    return remember(density) {
        with(density) {
            val stacked = fontScale >= LARGE_TEXT_SCALE
            QuantGrid(
                stacked = stacked,
                sizeWidth = DemoSize.quantSizeColumn.toDp(),
                fitWidth = DemoSize.quantFitColumn.toDp(),
                rowHeight =
                    if (stacked) {
                        DemoSize.quantStackedLine.toDp() * 2 + DemoSpacing.item + DemoSpacing.inline
                    } else {
                        DemoSize.target
                    },
            )
        }
    }
}

/** The column heads over [QuantRow]s ("Quant", "Size", "On this phone"), on the same grid. */
@Composable
fun QuantHeader(quant: String, size: String, fit: String, modifier: Modifier = Modifier) {
    val colors = LocalDemoColors.current
    val grid = rememberQuantGrid()
    val style = DemoType.labelSmall
    Row(modifier.fillMaxWidth().padding(QuantPadding).padding(bottom = DemoSpacing.inline)) {
        Text(
            quant.uppercase(),
            Modifier.weight(1f),
            color = colors.muted,
            style = style,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (!grid.stacked) {
            Text(
                size.uppercase(),
                Modifier.width(grid.sizeWidth),
                color = colors.muted,
                style = style,
                maxLines = 1,
            )
        }
        Text(
            fit.uppercase(),
            if (grid.stacked) Modifier else Modifier.width(grid.fitWidth),
            color = colors.muted,
            style = style,
            maxLines = 1,
            textAlign = TextAlign.End,
        )
    }
}

/**
 * One file of a model in the Advanced list: the [quant] name, its [size] and how it runs here, [fit] in the ink of
 * [level], in three aligned columns. Every row has the same height, whatever it holds. [selected] outlines it;
 * [edgeLabel] puts "Recommended" beside the quant's name. An [included] row (the image reader, a file already on the
 * phone) can't be picked and is not outlined. [note] ("Recommended", "On phone") is a word under the quant instead of
 * a chip beside it; [notes] reserves its line on every row of a list, so the rows stay one height.
 */
@Composable
fun QuantRow(
    quant: String,
    size: String,
    fit: String,
    level: FitLevel,
    selected: Boolean,
    modifier: Modifier = Modifier,
    included: Boolean = false,
    edgeLabel: String? = null,
    onClick: () -> Unit = {},
) = QuantRow(quant, size, selected, modifier, included, edgeLabel, onClick, symbol = { FitText(fit, level) })

/**
 * As the [QuantRow] with words, with a [symbol] (such as a [FitSymbol]) in the fit's column instead. The quant is plain
 * label text, not a pill (a pill is a state's), and the quant and size share a baseline. A
 * [flat] row has no card edge: a fact under the list (the image reader, "Included"), not a choice.
 */
@Composable
fun QuantRow(
    quant: String,
    size: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    included: Boolean = false,
    edgeLabel: String? = null,
    onClick: () -> Unit = {},
    note: String? = null,
    notes: Boolean = note != null,
    flat: Boolean = false,
    symbol: @Composable () -> Unit,
) {
    val colors = LocalDemoColors.current
    val grid = rememberQuantGrid()
    val chips: @Composable () -> Unit = {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(DemoSpacing.block),
        ) {
            Text(quant, color = colors.ink, style = DemoType.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (edgeLabel != null) EdgeChip(edgeLabel)
        }
    }
    val twoLines =
        with(LocalDensity.current) { DemoType.label.lineHeight.toDp() + DemoType.bodySmall.lineHeight.toDp() }
    val nameCluster: @Composable (Boolean) -> Unit = { stacked ->
        if (notes && note == null && !stacked) {
            // no note: the quant is centred in the note's reserved height, so the row isn't top-heavy and its symbol
            // and size sit on the quant's line
            Box(Modifier.height(twoLines), contentAlignment = Alignment.CenterStart) { chips() }
        } else if (notes && note != null) {
            Column {
                chips()
                Text(
                    note,
                    color = colors.muted,
                    style = DemoType.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        } else {
            chips()
        }
    }
    val sizeText: @Composable (Modifier) -> Unit = { m ->
        Text(size, m, color = colors.ink, style = DemoType.label.copy(fontFeatureSettings = "tnum"), maxLines = 1)
    }
    val body: @Composable () -> Unit = {
        if (grid.stacked) {
            // stacked, the note is a line of its own on every row of the list
            val noteLine = if (notes) with(LocalDensity.current) { DemoSize.quantStackedLine.toDp() } else 0.dp
            Column(
                Modifier.fillMaxWidth().height(grid.rowHeight + noteLine),
                verticalArrangement = Arrangement.Center,
            ) {
                // a row without a note has no blank line between its quant and its size
                nameCluster(true)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    sizeText(Modifier.weight(1f))
                    symbol()
                }
            }
        } else {
            Row(Modifier.fillMaxWidth().height(grid.rowHeight), verticalAlignment = Alignment.CenterVertically) {
                // the quant's first line and the size share a baseline; the symbol sits centred in its column
                Box(Modifier.weight(1f).alignByBaseline()) { nameCluster(false) }
                sizeText(Modifier.width(grid.sizeWidth).alignByBaseline())
                Box(Modifier.width(grid.fitWidth), contentAlignment = Alignment.CenterEnd) { symbol() }
            }
        }
    }
    if (flat) {
        Box(modifier.fillMaxWidth().padding(QuantPadding)) { body() }
    } else {
        DemoCard(
            // a file that can't be picked (on the phone) looks as Basic's size on the phone does
            modifier = modifier.fillMaxWidth().then(if (included) Modifier.alpha(UNAVAILABLE_ALPHA) else Modifier),
            selected = selected,
            onClick = if (included) null else onClick,
            contentPadding = QuantPadding,
        ) { body() }
    }
}
