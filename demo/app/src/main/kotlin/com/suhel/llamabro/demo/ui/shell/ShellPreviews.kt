package com.suhel.llamabro.demo.ui.shell

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import androidx.compose.ui.unit.dp
import com.suhel.llamabro.demo.data.ChatSummary
import com.suhel.llamabro.demo.designsystem.components.SheetFrame
import com.suhel.llamabro.demo.designsystem.components.gutterPadding
import com.suhel.llamabro.demo.ui.preview.DemoPreviews
import com.suhel.llamabro.demo.ui.preview.DemoScreenPreviews
import com.suhel.llamabro.demo.ui.preview.PreviewFrame
import java.time.ZoneOffset

// Previews of the shell: the Sidebar layout's drawer, the Chat list layout's home, and the long-press sheets.

private const val NOW = 1_800_000_000_000L
private const val HOUR = 3_600_000L
private const val DAY = 24 * HOUR
private const val SWIFT = "unsloth/Qwen3.5-0.8B-GGUF:Q4_K_M"
private const val WRITER = "unsloth/gemma-3-4b-it-qat-GGUF:Q4_K_M"
private const val DEEP = "unsloth/Qwen3.5-4B-GGUF:Q4_K_M"

private val previewChats =
    listOf(
        ChatSummary(
            3,
            "Portfolio bio",
            NOW - HOUR,
            WRITER,
            "Here's a tighter version that leads with…",
            lastFromUser = false,
        ),
        ChatSummary(2, "Monstera brown edges", NOW - 5 * HOUR, SWIFT, "what about the soil?", lastFromUser = true),
        ChatSummary(
            1,
            "CV for the design role",
            NOW - 3 * DAY,
            DEEP,
            "The 2021 gap reads fine if you…",
            lastFromUser = false,
        ),
        ChatSummary(4, "Trip plan", NOW - 20 * DAY, SWIFT, "three days in Lisbon", lastFromUser = true),
    )

internal data class DrawerPreview(
    val name: String,
    val chats: List<ChatSummary>,
    val modelCount: Int?,
    val open: Long?,
    val search: String = "",
    val serving: Boolean = false,
) {
    override fun toString() = name
}

internal class DrawerStates : PreviewParameterProvider<DrawerPreview> {
    override val values =
        sequenceOf(
            DrawerPreview("Chats, one open", previewChats, 3, open = 3),
            DrawerPreview("Serving", previewChats, 3, open = 3, serving = true),
            DrawerPreview("No chats yet", emptyList(), 0, open = null),
            DrawerPreview("Nothing matches", emptyList(), 3, open = null, search = "zzzz"),
            DrawerPreview("Count not read yet", previewChats, null, open = null),
        )
}

@DemoScreenPreviews
@Composable
internal fun DrawerContentPreview(
    @PreviewParameter(DrawerStates::class) p: DrawerPreview,
) {
    PreviewFrame {
        DrawerContent(
            groups = ChatListFormat.group(p.chats, NOW, ZoneOffset.UTC),
            searchText = p.search,
            modelCount = p.modelCount,
            currentChatId = p.open,
            now = NOW,
            zone = ZoneOffset.UTC,
            onSearch = {},
            onNewChat = {},
            onOpenChat = {},
            onModels = {},
            onServer = {},
            onSettings = {},
            onLongPress = {},
            modifier = Modifier.height(780.dp),
            serving = p.serving,
        )
    }
}

internal data class ChatListPreview(val name: String, val chats: List<ChatSummary>, val search: String) {
    override fun toString() = name
}

internal class ChatListStates : PreviewParameterProvider<ChatListPreview> {
    override val values =
        sequenceOf(
            ChatListPreview("Chats", previewChats, ""),
            ChatListPreview("No chats yet", emptyList(), ""),
            ChatListPreview("Nothing matches", emptyList(), "zzzz"),
        )
}

@DemoScreenPreviews
@Composable
internal fun ChatListContentPreview(
    @PreviewParameter(ChatListStates::class) p: ChatListPreview,
) {
    PreviewFrame {
        ChatListContent(
            p.chats,
            p.search,
            NOW,
            ZoneOffset.UTC,
            onSearch = {},
            onOpenChat = {},
            onNewChat = {},
            onLongPress = {},
            modifier = Modifier.height(780.dp),
        )
    }
}

@DemoPreviews
@Composable
internal fun ChatActionsPreview() {
    PreviewFrame {
        Column(
            Modifier.gutterPadding(),
        ) { SheetFrame { ActionsContent(onRename = {}, onDelete = {}) } }
    }
}

internal class RenameStates : PreviewParameterProvider<String> {
    override val values = sequenceOf("Portfolio bio", "")
}

@DemoPreviews
@Composable
internal fun RenameContentPreview(
    @PreviewParameter(RenameStates::class) title: String,
) {
    PreviewFrame {
        Column(Modifier.gutterPadding()) { SheetFrame { RenameContent(title, onTitle = {}, onSave = {}) } }
    }
}
