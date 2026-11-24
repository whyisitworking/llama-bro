package com.suhel.llamabro.demo.designsystem.samples

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.suhel.llamabro.demo.designsystem.components.ChatHistoryEntry
import com.suhel.llamabro.demo.designsystem.components.ChatHistoryGroup
import com.suhel.llamabro.demo.designsystem.components.ChatHistoryList
import com.suhel.llamabro.demo.designsystem.components.ChatRow
import com.suhel.llamabro.demo.designsystem.components.DemoIcons
import com.suhel.llamabro.demo.designsystem.components.DialogCard
import com.suhel.llamabro.demo.designsystem.components.FitLevel
import com.suhel.llamabro.demo.designsystem.components.GroupedList
import com.suhel.llamabro.demo.designsystem.components.ListRow
import com.suhel.llamabro.demo.designsystem.components.ModelRow
import com.suhel.llamabro.demo.designsystem.components.NavRow
import com.suhel.llamabro.demo.designsystem.components.Notice
import com.suhel.llamabro.demo.designsystem.components.QuantHeader
import com.suhel.llamabro.demo.designsystem.components.QuantRow
import com.suhel.llamabro.demo.designsystem.components.Section
import com.suhel.llamabro.demo.designsystem.components.SectionLabel
import com.suhel.llamabro.demo.designsystem.components.SectionNote
import com.suhel.llamabro.demo.designsystem.components.SheetFrame
import com.suhel.llamabro.demo.designsystem.components.SidebarChatRow
import com.suhel.llamabro.demo.designsystem.components.StatusPill
import com.suhel.llamabro.demo.designsystem.components.SwipeToRemove
import com.suhel.llamabro.demo.designsystem.theme.DemoShapes
import com.suhel.llamabro.demo.designsystem.theme.DemoTheme

/**
 * Section labels, notes (one with an action), notices, a status pill, the drawer's places and rows, the chat list and
 * the chat rows; the switcher sheet and the quant table are [ListsSheetSamples].
 */
@Composable
internal fun ListsSamples() {
    SampleFrame {
        SectionLabel("Picked for your phone", action = "What's a model?", onAction = {})
        SectionLabel("On this phone", note = "6.1 GB · 120 GB free")
        // the Models page's quiet label (D123)
        SectionLabel("On this phone", note = "6.1 GB · 120 GB free", quiet = true)
        SectionNote("Changing this reloads the model.")
        // a label's note with its way back: the search's filters, named, and Reset (models-search design §5)
        SectionLabel("From Hugging Face", quiet = true)
        SectionNote("Newest · Any size · Sees images", action = "Reset")
        Notice("Couldn't reach Hugging Face.", action = "Try again")
        Notice("No models match \"llama 70b\".")
        StatusPill("On this phone")
        GroupedList {
            row { ListRow("Models", leading = DemoIcons.Models, trailing = "3 on this phone", onClick = {}) }
        }
        // the sidebar's places and history (D126)
        NavRow(DemoIcons.Models, "Models", onClick = {}, note = "3 on this phone")
        SidebarChatRow("Explain mixture of experts", trailing = "11:02 am")
        ChatHistoryList(
            listOf(
                ChatHistoryGroup(
                    "Today",
                    listOf(
                        ChatHistoryEntry(1, "Portfolio bio", true, "2:14 pm"),
                        ChatHistoryEntry(2, "Monstera brown edges", false, "9:40 am"),
                    ),
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
    }
}

/** The chat's model switcher (models-page design §7) and the Advanced quant table of models.html. */
@Composable
internal fun ListsSheetSamples() {
    SampleFrame {
        SheetFrame {
            Section("On this phone") {
                // the chat's model says "Current", the one new chats use "Default"; the info action opens its card
                ModelRow(
                    "Qwen3.5 0.8B",
                    FitLevel.Great,
                    "Runs great",
                    maker = "Qwen",
                    size = "0.6 GB",
                    state = "Current",
                    capabilities = listOf(DemoIcons.CapabilityThinks to "Thinks"),
                    fact = null,
                    onClick = {},
                    onLongClick = {},
                    onInfo = {},
                )
                ModelRow(
                    "Gemma 3 4B",
                    FitLevel.Well,
                    "Runs well",
                    maker = "Google",
                    size = "3.4 GB",
                    state = "Default",
                    capabilities = listOf(DemoIcons.CapabilityImages to "Sees images"),
                    fact = null,
                    onClick = {},
                    onLongClick = {},
                    onInfo = {},
                )
            }
            GroupedList {
                row { ListRow("Get more models", leading = DemoIcons.Plus, chevron = true, onClick = {}) }
            }
        }
        SheetFrame {
            QuantHeader("Quant", "Size", "On this phone")
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                QuantRow("Q3_K_M", "1.9 GB", "Fits", FitLevel.TooBig, selected = false)
                QuantRow("IQ4_XS", "2.4 GB", "Runs well", FitLevel.Great, selected = false)
                QuantRow("Q4_K_M", "2.8 GB", "Runs well", FitLevel.Great, selected = true, edgeLabel = "Recommended")
                QuantRow("Q8_0", "4.5 GB", "Slow", FitLevel.Slow, selected = false)
            }
            Section("Image reader") {
                QuantRow("BF16", "0.7 GB", "Included", FitLevel.Great, selected = false, included = true)
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
        SwipeToRemove(label = "Remove", onRemove = {}, shape = DemoShapes.row) {
            ModelRow(
                "Qwen3.5 4B",
                FitLevel.Well,
                "Runs well",
                maker = "Qwen",
                size = "2.8 GB",
                state = null,
                capabilities = listOf(DemoIcons.CapabilityThinks to "Thinks"),
                fact = null,
                onClick = {},
            )
        }
        DialogCard(
            title = "Remove Qwen3.5 4B?",
            text = "This frees 2.8 GB. You can get it again from Hugging Face.",
            confirmLabel = "Remove",
            onConfirm = {},
            dismissLabel = "Keep",
            onDismiss = {},
            danger = true,
        )
    }
}

@LightDarkPreviews
@Composable
private fun RemovalPreview() = DemoTheme { RemovalSamples() }
