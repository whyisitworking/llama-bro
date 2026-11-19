package com.suhel.llamabro.demo.designsystem.samples

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.suhel.llamabro.demo.designsystem.components.CardTint
import com.suhel.llamabro.demo.designsystem.components.ChatRow
import com.suhel.llamabro.demo.designsystem.components.DemoIcons
import com.suhel.llamabro.demo.designsystem.components.DialogCard
import com.suhel.llamabro.demo.designsystem.components.DownloadRow
import com.suhel.llamabro.demo.designsystem.components.EdgeChip
import com.suhel.llamabro.demo.designsystem.components.FitText
import com.suhel.llamabro.demo.designsystem.components.Gap
import com.suhel.llamabro.demo.designsystem.components.GroupedLazyList
import com.suhel.llamabro.demo.designsystem.components.GroupedList
import com.suhel.llamabro.demo.designsystem.components.Inline
import com.suhel.llamabro.demo.designsystem.components.LibraryRow
import com.suhel.llamabro.demo.designsystem.components.ListEntry
import com.suhel.llamabro.demo.designsystem.components.ListGroup
import com.suhel.llamabro.demo.designsystem.components.ListRow
import com.suhel.llamabro.demo.designsystem.components.QuantHeader
import com.suhel.llamabro.demo.designsystem.components.QuantRow
import com.suhel.llamabro.demo.designsystem.components.RowCard
import com.suhel.llamabro.demo.designsystem.components.Section
import com.suhel.llamabro.demo.designsystem.components.SectionLabel
import com.suhel.llamabro.demo.designsystem.components.SheetFrame
import com.suhel.llamabro.demo.designsystem.components.SwipeToRemove
import com.suhel.llamabro.demo.designsystem.theme.DemoTheme

/**
 * Section labels, the drawer's rows, the switcher sheet, the chat list, the quant table, the library and a download.
 */
@Composable
internal fun ListsSamples() {
    SampleFrame {
        SectionLabel("Picked for your phone", action = "What's a model?", onAction = {})
        GroupedList {
            row { ListRow("Models", leading = DemoIcons.Models, trailing = "3 on this phone", onClick = {}) }
        }
        GroupedLazyList(
            listOf(
                ListGroup(
                    "Today",
                    listOf(ListEntry(1, "Portfolio bio", true), ListEntry(2, "Monstera brown edges", false)),
                ),
            ),
            onClick = {},
            onLongClick = {},
            modifier = Modifier.height(180.dp),
        )
        Column {
            ChatRow(
                "W",
                "Portfolio bio",
                "9:38",
                "Gemma 3 4B: Here's a tighter version that leads with…",
                fromUser = false,
            )
            ChatRow("S", "Monstera brown edges", "8:02", "You: what about the soil?", fromUser = true)
            ChatRow("D", "CV for the design role", "Tue", "Deep: The 2021 gap reads fine if you…", fromUser = false)
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            LibraryRow("Qwen3.5 0.8B", "Qwen3.5 0.8B · 0.7 GB", "Used today", onClick = {})
            LibraryRow("Gemma 3 4B", "Gemma 3 4B · 2.5 GB", "Used today", defaultLabel = "Default", onClick = {})
            LibraryRow("Deep", "Qwen3.5 4B · 2.8 GB", "2 weeks ago", onClick = {})
        }
        SectionLabel("Downloading")
        DownloadRow(
            title = "Qwen3.5 0.8B",
            fraction = 0.42f,
            detail = "310 of 740 MB · 9.8 MB/s",
            timeLeft = "about 40 s",
            actionLabel = "Pause",
            onAction = {},
            cancelLabel = "Cancel",
            onCancel = {},
        )
    }
}

/** The switcher sheet of everyday.html and the Advanced quant table of models.html. */
@Composable
internal fun ListsSheetSamples() {
    SampleFrame {
        SheetFrame {
            Section("On this phone") {
                RowCard(selected = false, onClick = {}) {
                    ListRow("Qwen3.5 0.8B", subtitle = "Qwen3.5 0.8B", end = { FitText("Runs great", CardTint.Great) })
                }
                RowCard(selected = true, onClick = {}) {
                    ListRow(
                        "Gemma 3 4B",
                        subtitle = "Gemma 3 4B",
                        end = {
                            Inline(Gap.Block) {
                                EdgeChip("Default")
                                FitText("Runs well", CardTint.Plain)
                            }
                        },
                    )
                }
                RowCard(selected = false, onClick = {}) {
                    ListRow("Deep", subtitle = "Qwen3.5 4B", end = { FitText("A bit slow", CardTint.Slow) })
                }
            }
            GroupedList {
                row { ListRow("Get more models", leading = DemoIcons.Plus, chevron = true, onClick = {}) }
            }
        }
        SheetFrame {
            QuantHeader("Quant", "Size", "On this phone")
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                QuantRow("Q3_K_M", "1.9 GB", "Fits", CardTint.TooBig, selected = false)
                QuantRow("IQ4_XS", "2.4 GB", "Runs well", CardTint.Great, selected = false)
                QuantRow("Q4_K_M", "2.8 GB", "Runs well", CardTint.Great, selected = true, edgeLabel = "Recommended")
                QuantRow("Q8_0", "4.5 GB", "Slow", CardTint.Slow, selected = false)
            }
            Section("Image reader") {
                QuantRow("BF16", "0.7 GB", "Included", CardTint.Great, selected = false, included = true)
            }
        }
    }
}

@LightDarkPreviews
@Composable
private fun ListsPreview() = DemoTheme { ListsSamples() }

@LightDarkPreviews
@Composable
private fun ListsSheetPreview() = DemoTheme { ListsSheetSamples() }

/** Removing a model: the row you swipe (here at rest) and the question it asks. */
@Composable
internal fun RemovalSamples() {
    SampleFrame {
        SwipeToRemove(label = "Remove", onRemove = {}) {
            LibraryRow("Deep", "Qwen3.5 4B · 2.8 GB", "2 weeks ago", onClick = {})
        }
        DialogCard(
            title = "Remove Deep?",
            text = "This frees 2.8 GB. You can get it again from Hugging Face.",
            confirmLabel = "Remove",
            onConfirm = {},
            dismissLabel = "Keep",
            onDismiss = {},
        )
    }
}

@LightDarkPreviews
@Composable
private fun RemovalPreview() = DemoTheme { RemovalSamples() }
