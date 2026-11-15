package com.suhel.llamabro.demo.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.suhel.llamabro.demo.designsystem.R
import com.suhel.llamabro.demo.designsystem.theme.DemoColors
import com.suhel.llamabro.demo.designsystem.theme.DemoShapes
import com.suhel.llamabro.demo.designsystem.theme.DemoSize
import com.suhel.llamabro.demo.designsystem.theme.DemoSpacing
import com.suhel.llamabro.demo.designsystem.theme.DemoType
import com.suhel.llamabro.demo.designsystem.theme.LocalDemoColors
import com.suhel.llamabro.demo.designsystem.theme.subtleFill

/** A run of text with its inline marks, as `:app`'s parser produces it; [link] is an http, https or mailto URL. */
@Immutable
data class MarkdownSpan(
    val text: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val code: Boolean = false,
    val link: String? = null,
    val strike: Boolean = false,
)

/** How a table column's cells line up, from the GFM delimiter row (`:--`, `:-:`, `--:`); none reads as [Start]. */
enum class MarkdownAlignment { Start, Center, End }

/** One block of a reply. The parser keeps finished blocks' instances when text is appended, so they don't redraw. */
@Immutable
sealed interface MarkdownBlock {
    @Immutable data class Paragraph(val spans: List<MarkdownSpan>) : MarkdownBlock

    /** A heading of [level] 1 to 6: 1 and 2 in the screen title role, 3 and deeper in the title role. */
    @Immutable data class Heading(val level: Int, val spans: List<MarkdownSpan>) : MarkdownBlock

    @Immutable data class Code(val language: String?, val text: String) : MarkdownBlock

    /**
     * One list entry: [spans] is its first paragraph, beside the marker ([index] when [ordered]); [blocks] is what the
     * item holds under it, hung on the same indent: a nested list's items, more paragraphs, code. [startsList] marks a
     * list's first item, so a list right after another one keeps a block's gap from it.
     */
    @Immutable data class ListItem(
        val ordered: Boolean,
        val index: Int,
        val spans: List<MarkdownSpan>,
        val blocks: List<MarkdownBlock> = emptyList(),
        val startsList: Boolean = false,
    ) : MarkdownBlock

    /** A block quote and the blocks inside it, a nested quote among them. */
    @Immutable data class Quote(val blocks: List<MarkdownBlock>) : MarkdownBlock

    /** A GFM table: the [header] row's cells, the body [rows] (each as wide as the header), each column's alignment. */
    @Immutable data class Table(
        val alignments: List<MarkdownAlignment>,
        val header: List<List<MarkdownSpan>>,
        val rows: List<List<List<MarkdownSpan>>>,
    ) : MarkdownBlock

    /** A thematic break (`---`, `***`, `* * *`). */
    @Immutable data object Rule : MarkdownBlock
}

/** A parsed reply: its blocks in order. */
@Immutable
data class MarkdownBlocks(val blocks: List<MarkdownBlock>)

/**
 * Renders parsed markdown (parsing stays in `:app`): paragraphs and list items in the body style, headings in the
 * title roles, quotes muted behind a soft bar, tables and code scrolling sideways, a rule as a hairline. Links open in
 * the browser (`:app` keeps links to http, https and mailto only).
 */
@Composable
fun MarkdownText(blocks: MarkdownBlocks, onCopyCode: (String) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier) { MarkdownBlockList(blocks.blocks, onCopyCode, muted = false, depth = 0) }
}

/** Blocks one after another, with the gaps of [gapBefore]; [depth] is how deep in lists they are (for the bullet). */
@Composable
private fun MarkdownBlockList(blocks: List<MarkdownBlock>, onCopyCode: (String) -> Unit, muted: Boolean, depth: Int) {
    blocks.forEachIndexed { index, block ->
        if (index > 0) Spacer(Modifier.height(gapBefore(blocks[index - 1], block)))
        MarkdownBlockView(block, onCopyCode, muted, depth)
    }
}

// list items group tighter than other blocks ([DemoSpacing.inline] against [DemoSpacing.block]), and a heading opens a
// section ([DemoSpacing.item] above it), so the structure reads at a glance
private fun gapBefore(previous: MarkdownBlock, block: MarkdownBlock): Dp =
    when {
        block is MarkdownBlock.Heading -> DemoSpacing.item
        previous is MarkdownBlock.ListItem && block is MarkdownBlock.ListItem && !block.startsList -> DemoSpacing.inline
        else -> DemoSpacing.block
    }

/**
 * The bullet of an unordered item [depth] lists deep: a disc, then a ring, then a square, drawn where the body font's
 * own bullet would sit. The ring and square glyphs aren't in the body font, and a fallback font's taller line would
 * push the item's rows apart, so a hidden "•" sets the line and its bounds place the shape.
 */
@Composable
private fun Bullet(depth: Int, color: Color, modifier: Modifier = Modifier) {
    var glyph by remember { mutableStateOf(Rect.Zero) }
    Text(
        "\u2022",
        modifier.drawBehind {
            val radius = DemoSize.bullet.toPx() / 2
            val stroke = DemoSize.hairline.toPx()
            when (depth % 3) {
                0 -> drawCircle(color, radius, glyph.center)
                1 -> drawCircle(color, radius - stroke / 2, glyph.center, style = Stroke(stroke))
                else -> drawRect(color, glyph.center - Offset(radius, radius), Size(radius * 2, radius * 2))
            }
        },
        color = Color.Transparent,
        style = DemoType.body,
        textAlign = TextAlign.End,
        onTextLayout = { glyph = it.getBoundingBox(0) },
    )
}

@Composable
private fun MarkdownBlockView(block: MarkdownBlock, onCopyCode: (String) -> Unit, muted: Boolean, depth: Int) {
    val colors = LocalDemoColors.current
    val ink = if (muted) colors.muted else colors.ink
    when (block) {
        is MarkdownBlock.Paragraph -> {
            Text(rememberSpans(block.spans, colors), color = ink, style = DemoType.body)
        }

        is MarkdownBlock.Heading -> {
            Text(
                rememberSpans(block.spans, colors),
                Modifier.semantics { heading() },
                color = ink,
                style = if (block.level <= 2) DemoType.screenTitle else DemoType.title,
            )
        }

        is MarkdownBlock.Code -> {
            CodeBlock(code = block.text, onCopy = { onCopyCode(block.text) }, language = block.language)
        }

        is MarkdownBlock.ListItem -> {
            MarkdownListItem(block, onCopyCode, muted, depth)
        }

        is MarkdownBlock.Quote -> {
            Row(Modifier.height(IntrinsicSize.Min)) {
                Box(Modifier.width(DemoSize.track).fillMaxHeight().background(colors.hairline, CircleShape))
                Column(Modifier.padding(start = DemoSpacing.compact)) {
                    MarkdownBlockList(block.blocks, onCopyCode, muted = true, depth = depth)
                }
            }
        }

        is MarkdownBlock.Table -> {
            MarkdownTable(block, ink)
        }

        MarkdownBlock.Rule -> {
            Box(Modifier.fillMaxWidth().height(DemoSize.hairline).background(colors.hairline))
        }
    }
}

/**
 * A list item: the marker right-aligned in a column wider than "9.", then the text, so every item's text starts at one
 * edge (the hang of §8.10); what the item holds, a nested list among it, hangs on the same edge, one hang deeper.
 */
@Composable
private fun MarkdownListItem(item: MarkdownBlock.ListItem, onCopyCode: (String) -> Unit, muted: Boolean, depth: Int) {
    val colors = LocalDemoColors.current
    Row {
        // the minimum is inside the gap, not around it, or each number's own width would move the text
        val marker = Modifier.padding(end = DemoSpacing.block).widthIn(min = DemoSpacing.section)
        if (item.ordered) {
            Text("${item.index}.", marker, color = colors.muted, style = DemoType.body, textAlign = TextAlign.End)
        } else {
            Bullet(depth, colors.muted, marker)
        }
        Column {
            if (item.spans.isNotEmpty()) {
                Text(
                    rememberSpans(item.spans, colors),
                    color = if (muted) colors.muted else colors.ink,
                    style = DemoType.body,
                )
            }
            if (item.blocks.isNotEmpty()) {
                if (item.spans.isNotEmpty()) {
                    val first = item.blocks.first()
                    Spacer(
                        Modifier.height(if (first is MarkdownBlock.ListItem) DemoSpacing.inline else DemoSpacing.block),
                    )
                }
                MarkdownBlockList(item.blocks, onCopyCode, muted, depth + 1)
            }
        }
    }
}

/**
 * A table: the header row on a fill one step off the canvas, hairline rules between rows inside a hairline card, each
 * column as wide as its widest cell and aligned as the delimiter row says. Cells don't wrap; a wide table scrolls
 * sideways, like code.
 */
@Composable
private fun MarkdownTable(table: MarkdownBlock.Table, ink: Color) {
    val colors = LocalDemoColors.current
    Box(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
        Row(
            Modifier
                .border(DemoSize.hairline, colors.hairline, DemoShapes.card)
                .clip(DemoShapes.card),
        ) {
            table.alignments.forEachIndexed { column, alignment ->
                // every cell is one line of one style, so the columns' rows line up; each cell fills its column's width
                Column(Modifier.width(IntrinsicSize.Max)) {
                    MarkdownCell(
                        table.header.getOrElse(column) {
                            emptyList()
                        },
                        alignment,
                        ink,
                        header = true,
                        ruled = table.rows.isNotEmpty(),
                    )
                    table.rows.forEachIndexed { row, cells ->
                        MarkdownCell(
                            cells.getOrElse(column) { emptyList() },
                            alignment,
                            ink,
                            header = false,
                            ruled =
                                row < table.rows.lastIndex,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MarkdownCell(
    spans: List<MarkdownSpan>,
    alignment: MarkdownAlignment,
    ink: Color,
    header: Boolean,
    ruled: Boolean,
) {
    val colors = LocalDemoColors.current
    val rule = colors.hairline
    Text(
        rememberSpans(spans, colors),
        Modifier
            .fillMaxWidth()
            .then(if (header) Modifier.background(colors.subtleFill) else Modifier)
            .drawBehind {
                if (ruled) {
                    val y = size.height - DemoSize.hairline.toPx() / 2
                    drawLine(rule, Offset(0f, y), Offset(size.width, y), DemoSize.hairline.toPx())
                }
            }.padding(horizontal = DemoSpacing.compact, vertical = DemoSpacing.block),
        color = ink,
        style = if (header) DemoType.title else DemoType.body,
        textAlign =
            when (alignment) {
                MarkdownAlignment.Start -> TextAlign.Start
                MarkdownAlignment.Center -> TextAlign.Center
                MarkdownAlignment.End -> TextAlign.End
            },
        softWrap = false,
        maxLines = 1,
    )
}

@Composable
private fun rememberSpans(spans: List<MarkdownSpan>, colors: DemoColors): AnnotatedString =
    remember(spans, colors) {
        buildAnnotatedString {
            for (span in spans) {
                val style =
                    SpanStyle(
                        fontWeight = if (span.bold) FontWeight.SemiBold else null,
                        fontStyle = if (span.italic) FontStyle.Italic else null,
                        fontFamily = if (span.code) FontFamily.Monospace else null,
                        background = if (span.code) colors.subtleFill else Color.Unspecified,
                        textDecoration = if (span.strike) TextDecoration.LineThrough else null,
                    )
                if (span.link != null) {
                    val linkStyle =
                        TextLinkStyles(SpanStyle(color = colors.accent, textDecoration = TextDecoration.Underline))
                    withLink(LinkAnnotation.Url(span.link, linkStyle)) { withStyle(style) { append(span.text) } }
                } else {
                    withStyle(style) { append(span.text) }
                }
            }
        }
    }

/**
 * A code block: the [language] (or "Code") and a copy action over the code in monospace, on a fill one step off the
 * canvas, scrolling sideways instead of wrapping.
 */
@Composable
fun CodeBlock(code: String, onCopy: () -> Unit, modifier: Modifier = Modifier, language: String? = null) {
    val colors = LocalDemoColors.current
    Column(
        modifier
            .fillMaxWidth()
            .background(colors.subtleFill, DemoShapes.card)
            .padding(start = DemoSpacing.item, bottom = DemoSpacing.item),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                language ?: stringResource(R.string.ds_code),
                Modifier.weight(1f),
                color = colors.muted,
                style = DemoType.labelSmall,
            )
            IconAction(icon = DemoIcons.Copy, label = stringResource(R.string.ds_copy), onClick = onCopy, muted = true)
        }
        // code reads left to right whatever the page's direction: in RTL it would start scrolled to its far end
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Text(
                code,
                Modifier.horizontalScroll(rememberScrollState()).padding(end = DemoSpacing.item),
                color = colors.ink,
                style = DemoType.code,
                softWrap = false,
            )
        }
    }
}
