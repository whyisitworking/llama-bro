package com.suhel.llamabro.demo.designsystem.samples

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.unit.dp
import com.suhel.llamabro.demo.designsystem.components.CodeBlock
import com.suhel.llamabro.demo.designsystem.components.DayLabel
import com.suhel.llamabro.demo.designsystem.components.HeldNote
import com.suhel.llamabro.demo.designsystem.components.JumpToLatest
import com.suhel.llamabro.demo.designsystem.components.LimitLine
import com.suhel.llamabro.demo.designsystem.components.MarkdownAlignment
import com.suhel.llamabro.demo.designsystem.components.MarkdownBlock
import com.suhel.llamabro.demo.designsystem.components.MarkdownBlocks
import com.suhel.llamabro.demo.designsystem.components.MarkdownSpan
import com.suhel.llamabro.demo.designsystem.components.MarkdownText
import com.suhel.llamabro.demo.designsystem.components.ModelBubble
import com.suhel.llamabro.demo.designsystem.components.OpenReply
import com.suhel.llamabro.demo.designsystem.components.StarterChips
import com.suhel.llamabro.demo.designsystem.components.SwitchDivider
import com.suhel.llamabro.demo.designsystem.components.ThoughtFold
import com.suhel.llamabro.demo.designsystem.components.ThoughtState
import com.suhel.llamabro.demo.designsystem.components.TypingDots
import com.suhel.llamabro.demo.designsystem.components.UserBubble
import com.suhel.llamabro.demo.designsystem.theme.DemoTheme

private fun p(vararg spans: MarkdownSpan) = MarkdownBlock.Paragraph(spans.toList())

private fun t(text: String) = MarkdownSpan(text)

internal val BioReply =
    MarkdownBlocks(
        listOf(
            p(t("Here's a warm, concise version you can tweak:")),
            p(
                MarkdownSpan(
                    "\"I'm a designer who turns messy problems into calm, simple products. Lately I've been…\"",
                    italic = true,
                ),
            ),
        ),
    )

internal val KotlinReply =
    MarkdownBlocks(
        listOf(
            MarkdownBlock.Heading(2, listOf(t("Reversing a list in Kotlin"))),
            p(
                t("Use "),
                MarkdownSpan("asReversed()", code = true),
                t(" for a "),
                MarkdownSpan("view", bold = true),
                t(", or "),
                MarkdownSpan("reversed()", code = true),
                t(" for a copy:"),
            ),
            MarkdownBlock.Code(
                "kotlin",
                "val numbers = listOf(1, 2, 3)\nval view = numbers.asReversed()\nval copy = numbers.reversed()",
            ),
            MarkdownBlock.ListItem(
                ordered = true,
                index = 1,
                spans = listOf(t("A view follows later changes to the list.")),
                blocks =
                    listOf(
                        MarkdownBlock.ListItem(ordered = false, index = 0, spans = listOf(t("Writes show through."))),
                    ),
            ),
            MarkdownBlock.ListItem(ordered = true, index = 2, spans = listOf(t("A copy doesn't."))),
            MarkdownBlock.Table(
                alignments = listOf(MarkdownAlignment.Start, MarkdownAlignment.End),
                header = listOf(listOf(t("Call")), listOf(t("Cost"))),
                rows =
                    listOf(
                        listOf(listOf(MarkdownSpan("asReversed()", code = true)), listOf(t("O(1)"))),
                        listOf(listOf(MarkdownSpan("reversed()", code = true)), listOf(t("O(n)"))),
                    ),
            ),
            MarkdownBlock.Quote(listOf(p(t("Prefer the view unless you need a snapshot.")))),
            MarkdownBlock.Rule,
            p(
                t("More in the "),
                MarkdownSpan("Kotlin docs", link = "https://kotlinlang.org/docs/collection-ordering.html"),
                t("."),
            ),
        ),
    )

/**
 * The sidebar layout's open page: a held message, the wake hairline, a folded thought, a reply with its speed, and the
 * thinking opened.
 */
@Composable
internal fun ChatOpenSamples() {
    SampleFrame {
        UserBubble("Help me write a short bio for my portfolio")
        HeldNote("Swift will answer the moment it's here")
        StillHairline()
        Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
            ThoughtFold(ThoughtState(seconds = 4, expanded = false, text = ""), onToggle = {})
            OpenReply(content = { MarkdownText(BioReply, onCopyCode = {}) }, speed = "38 words/s")
        }
        UserBubble("What's this plant? Is it healthy?", images = listOf(rememberSamplePhoto()))
        TypingDots()
        ThoughtFold(
            ThoughtState(
                seconds = null,
                expanded = true,
                text =
                    "Broad, glossy leaves with a split pattern, likely a monstera. Brown patches near the " +
                        "edges could mean too much sun or dry air…",
            ),
            onToggle = {},
        )
        SwitchDivider("Switched to Deep")
        StarterChips(listOf("Plan my week", "Explain like I'm 12", "Draft a reply"), onPick = {})
        LimitLine("Writer can't see images. Deep can.", action = "Switch to Deep", onAction = {})
        LimitLine("This chat outgrew Swift's memory.", action = "New chat", onAction = {})
        LimitLine(
            "Writer needs more memory than this phone has free right now.",
            action = "Try again",
            onAction = {},
            secondAction = "Smaller size",
            onSecondAction = {},
        )
        JumpToLatest(visible = true, onClick = {})
    }
}

/** The chat list layout's bubbles with times and a day label, markdown and code. */
@Composable
internal fun ChatBubbleSamples() {
    SampleFrame {
        DayLabel("Today")
        UserBubble("Make my bio shorter and warmer", time = "9:37")
        ModelBubble(
            content = {
                Column {
                    ThoughtFold(ThoughtState(seconds = 2, expanded = false, text = ""), onToggle = {})
                    MarkdownText(
                        MarkdownBlocks(
                            listOf(
                                p(t("Here's a tighter version that leads with what you make, not your titles:")),
                                p(t("\"I design calm tools for busy people…\"")),
                            ),
                        ),
                        onCopyCode = {},
                    )
                }
            },
            time = "9:38",
            speed = "38 words/s",
        )
        UserBubble("How do I reverse a list in Kotlin?", time = "9:39")
        ModelBubble(content = { MarkdownText(KotlinReply, onCopyCode = {}) }, time = "9:39", speed = "41 words/s")
        CodeBlock("adb shell am start -n com.suhel.llamabro.demo/.MainActivity", onCopy = {})
    }
}

@Composable
internal fun rememberSamplePhoto(): ImageBitmap =
    remember {
        ImageBitmap(336, 252).also { bitmap ->
            val canvas = Canvas(bitmap)
            val paint =
                Paint().apply {
                    shader =
                        Brush
                            .linearGradient(
                                listOf(Color(0xFF9CC7A0), Color(0xFF5F9A68), Color(0xFF3F6E48)),
                                start = Offset(0f, 0f),
                                end = Offset(336f, 252f),
                            ).let {
                                (it as ShaderBrush).createShader(
                                    androidx.compose.ui.geometry
                                        .Size(336f, 252f),
                                )
                            }
                }
            canvas.drawRect(Rect(0f, 0f, 336f, 252f), paint)
        }
    }

@LightDarkPreviews
@Composable
private fun ChatOpenPreview() = DemoTheme { ChatOpenSamples() }

@LightDarkPreviews
@Composable
private fun ChatBubblePreview() = DemoTheme { ChatBubbleSamples() }
