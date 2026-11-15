package com.suhel.llamabro.demo.designsystem.components

import androidx.compose.foundation.background
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
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

/** A run of text with its inline marks, as `:app`'s parser produces it; [link] is the URL of a link. */
@Immutable
data class MarkdownSpan(
    val text: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val code: Boolean = false,
    val link: String? = null,
)

/** One block of a reply. The parser keeps finished blocks' instances when text is appended, so they don't redraw. */
@Immutable
sealed interface MarkdownBlock {
    @Immutable data class Paragraph(val spans: List<MarkdownSpan>) : MarkdownBlock

    @Immutable data class Heading(val level: Int, val spans: List<MarkdownSpan>) : MarkdownBlock

    @Immutable data class Code(val language: String?, val text: String) : MarkdownBlock

    @Immutable data class ListItem(val ordered: Boolean, val index: Int, val spans: List<MarkdownSpan>) : MarkdownBlock

    @Immutable data class Quote(val spans: List<MarkdownSpan>) : MarkdownBlock
}

/** A parsed reply: its blocks in order. */
@Immutable
data class MarkdownBlocks(val blocks: List<MarkdownBlock>)

/**
 * Renders parsed markdown (parsing stays in `:app`): paragraphs and list items in the body style, headings in the
 * title style, quotes muted behind a soft bar, code in a [CodeBlock]. Links open in the browser.
 */
@Composable
fun MarkdownText(blocks: MarkdownBlocks, onCopyCode: (String) -> Unit, modifier: Modifier = Modifier) {
    // list items group tighter than other blocks ([DemoSpacing.inline] against [DemoSpacing.block]), and a heading
    // opens a section ([DemoSpacing.item] above it), so the structure reads at a glance
    Column(modifier) {
        blocks.blocks.forEachIndexed { index, block ->
            if (index > 0) Spacer(Modifier.height(gapBefore(blocks.blocks[index - 1], block)))
            MarkdownBlockView(block, onCopyCode)
        }
    }
}

private fun gapBefore(previous: MarkdownBlock, block: MarkdownBlock): Dp =
    when {
        block is MarkdownBlock.Heading -> DemoSpacing.item
        previous is MarkdownBlock.ListItem && block is MarkdownBlock.ListItem -> DemoSpacing.inline
        else -> DemoSpacing.block
    }

@Composable
private fun MarkdownBlockView(block: MarkdownBlock, onCopyCode: (String) -> Unit) {
    val colors = LocalDemoColors.current
    when (block) {
        is MarkdownBlock.Paragraph -> {
            Text(rememberSpans(block.spans, colors), color = colors.ink, style = DemoType.body)
        }

        is MarkdownBlock.Heading -> {
            Text(
                rememberSpans(block.spans, colors),
                color = colors.ink,
                style = if (block.level <= 2) DemoType.title else DemoType.title,
            )
        }

        is MarkdownBlock.Code -> {
            CodeBlock(code = block.text, onCopy = { onCopyCode(block.text) }, language = block.language)
        }

        is MarkdownBlock.ListItem -> {
            Row {
                Text(
                    if (block.ordered) "${block.index}." else "•",
                    // a column wider than "9." and right-aligned in it, so every item's text starts at one edge; the
                    // minimum is inside the gap, not around it, or each number's own width would move the text
                    Modifier.padding(end = DemoSpacing.block).widthIn(min = DemoSpacing.section),
                    color = colors.muted,
                    style = DemoType.body,
                    textAlign = TextAlign.End,
                )
                Text(rememberSpans(block.spans, colors), color = colors.ink, style = DemoType.body)
            }
        }

        is MarkdownBlock.Quote -> {
            Row(Modifier.height(IntrinsicSize.Min)) {
                Box(Modifier.width(DemoSize.track).fillMaxHeight().background(colors.hairline, CircleShape))
                Text(
                    rememberSpans(block.spans, colors),
                    Modifier.padding(start = DemoSpacing.compact),
                    color = colors.muted,
                    style = DemoType.body,
                )
            }
        }
    }
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
