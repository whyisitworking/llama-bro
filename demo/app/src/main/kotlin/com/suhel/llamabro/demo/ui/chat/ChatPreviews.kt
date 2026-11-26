package com.suhel.llamabro.demo.ui.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import androidx.compose.ui.unit.dp
import com.suhel.llamabro.DeviceKind
import com.suhel.llamabro.demo.chat.Limit
import com.suhel.llamabro.demo.chat.SamplingLayers
import com.suhel.llamabro.demo.data.AppLayout
import com.suhel.llamabro.demo.data.settings.Knob
import com.suhel.llamabro.demo.data.settings.Knobs
import com.suhel.llamabro.demo.designsystem.components.ComposerState
import com.suhel.llamabro.demo.designsystem.components.LimitLine
import com.suhel.llamabro.demo.designsystem.components.SheetFrame
import com.suhel.llamabro.demo.designsystem.components.ThinkLevel
import com.suhel.llamabro.demo.designsystem.components.gutterPadding
import com.suhel.llamabro.demo.models.Catalog
import com.suhel.llamabro.demo.models.DeviceFacts
import com.suhel.llamabro.demo.models.LibraryModel
import com.suhel.llamabro.demo.ui.preview.DemoPreviews
import com.suhel.llamabro.demo.ui.preview.DemoScreenPreviews
import com.suhel.llamabro.demo.ui.preview.PreviewFrame
import com.suhel.llamabro.hub.HfModel

// Previews of the chat screen: the whole screen in each state a chat can be in, then its parts.

internal data class ChatPreview(
    val name: String,
    val layout: AppLayout,
    val state: ChatScreenState,
    val streaming: StreamingItem? = null,
    val draft: ComposerDraft = ComposerDraft(),
    val composer: ComposerState = ChatPreviewData.composerIdle,
    val library: Boolean = false,
) {
    override fun toString() = name
}

private fun screen(
    bar: ChatBar,
    rows: List<ChatItem>,
    busy: Boolean = false,
    bottom: ChatBottom = ChatBottom(readOnly = false, problem = null, limit = null),
) = ChatScreenState(bar, rows, busy, bottom)

internal class ChatScreenStates : PreviewParameterProvider<ChatPreview> {
    private val swift = ChatBar(ChatPreviewData.header("Qwen3.5 0.8B"), "", waking = false)
    private val writer = ChatBar(ChatPreviewData.header("Gemma 3 4B"), "Portfolio bio", waking = false)

    override val values =
        sequenceOf(
            ChatPreview("Empty chat", AppLayout.Sidebar, screen(swift, emptyList())),
            ChatPreview("Conversation, open page", AppLayout.Sidebar, screen(swift, ChatPreviewData.conversation)),
            ChatPreview("Conversation, bubbles", AppLayout.ChatList, screen(writer, ChatPreviewData.bubbles)),
            ChatPreview(
                "Thinking, then answering",
                AppLayout.Sidebar,
                screen(swift, ChatPreviewData.conversation, busy = true),
                streaming = ChatPreviewData.streamingThinking,
                composer = ChatPreviewData.composerBusy,
            ),
            ChatPreview(
                "Streaming the answer",
                AppLayout.Sidebar,
                screen(swift, ChatPreviewData.conversation, busy = true),
                streaming = ChatPreviewData.streamingAnswer,
                composer = ChatPreviewData.composerBusy,
            ),
            ChatPreview(
                "Waking up",
                AppLayout.Sidebar,
                screen(
                    ChatBar(ChatPreviewData.header("Qwen3.5 0.8B"), "", waking = true),
                    ChatPreviewData.conversation,
                ),
            ),
            ChatPreview(
                "Downloading, a message held",
                AppLayout.Sidebar,
                screen(
                    ChatBar(ChatPreviewData.header("Qwen3.5 0.8B", 0.41f, "41% · about 2 min"), "", false),
                    ChatPreviewData.held,
                ),
                draft = ComposerDraft(text = "One more thing"),
                composer = ChatPreviewData.composerTyping,
            ),
            ChatPreview(
                "Model gone, read only",
                AppLayout.Sidebar,
                screen(
                    swift,
                    ChatPreviewData.conversation,
                    bottom = ChatBottom(readOnly = true, problem = null, limit = ChatPreviewData.goneLimit),
                ),
            ),
            ChatPreview(
                "Not enough memory",
                AppLayout.Sidebar,
                screen(swift, ChatPreviewData.failed, bottom = ChatBottom(false, null, ChatPreviewData.memoryLimit)),
                composer = ChatPreviewData.composerThinking,
            ),
            ChatPreview(
                "Editing, a photo problem",
                AppLayout.Sidebar,
                screen(
                    swift,
                    ChatPreviewData.conversation,
                    bottom = ChatBottom(false, "That photo couldn't be opened.", null),
                ),
                draft =
                    ComposerDraft(
                        text = "Can you tighten my bio?",
                        attachments = listOf("a.jpg", "b.jpg"),
                        editing = 0,
                    ),
                composer = ChatPreviewData.composerTyping,
            ),
            ChatPreview(
                "Answering from the library",
                AppLayout.Sidebar,
                screen(swift, ChatPreviewData.fromLibrary),
                library = true,
            ),
        )
}

@DemoScreenPreviews
@Composable
internal fun ChatContentPreview(
    @PreviewParameter(ChatScreenStates::class) preview: ChatPreview,
) {
    PreviewFrame {
        ChatContent(
            state = preview.state,
            live =
                ChatLive(
                    streaming = { preview.streaming },
                    draft = { preview.draft },
                    composer = { preview.composer },
                    thumbnail = { null },
                    library = { preview.library },
                ),
            actions = ChatScreenActions(),
            layout = preview.layout,
            style = preview.layout.messageStyle(),
            modifier = Modifier.height(780.dp),
        )
    }
}

internal data class RowPreview(
    val name: String,
    val item: ChatItem,
    val style: MessageStyle,
    val showActions: Boolean = false,
) {
    override fun toString() = name
}

internal class MessageRowStates : PreviewParameterProvider<RowPreview> {
    private val data = ChatPreviewData

    override val values =
        sequenceOf(
            RowPreview("Question", data.conversation[0], MessageStyle.Open),
            RowPreview("Question, held", data.held[0], MessageStyle.Open),
            RowPreview("Reply with actions", data.conversation[1], MessageStyle.Open, showActions = true),
            RowPreview("Reply with a thought and code", data.conversation[3], MessageStyle.Open),
            RowPreview("Reply stopped", data.stopped[1], MessageStyle.Open),
            RowPreview("Reply cut, can continue", data.cut[3], MessageStyle.Open),
            RowPreview("Reply cut earlier", data.cut[1], MessageStyle.Open),
            RowPreview("Reply failed", data.failed[1], MessageStyle.Open),
            RowPreview("Switched model", data.withSwitch.last(), MessageStyle.Open),
            RowPreview(
                "Reply from the library, with sources",
                data.fromLibrary[1],
                MessageStyle.Open,
                showActions = true,
            ),
            RowPreview("Question, bubbles", data.bubbles[1], MessageStyle.Bubbles),
            RowPreview("Reply, bubbles", data.bubbles[2], MessageStyle.Bubbles),
            RowPreview("Day label", data.bubbles[0], MessageStyle.Bubbles),
        )
}

@DemoPreviews
@Composable
internal fun MessageRowPreview(
    @PreviewParameter(MessageRowStates::class) preview: RowPreview,
) {
    PreviewFrame {
        Column(Modifier.fillMaxWidth().gutterPadding()) {
            MessageRow(preview.item, preview.style, ChatScreenActions().message, preview.showActions)
        }
    }
}

internal class StreamingStates : PreviewParameterProvider<StreamingItem> {
    override val values = sequenceOf(ChatPreviewData.streamingThinking, ChatPreviewData.streamingAnswer)
}

@DemoPreviews
@Composable
internal fun StreamingRowPreview(
    @PreviewParameter(StreamingStates::class) item: StreamingItem,
) {
    PreviewFrame {
        Column(Modifier.gutterPadding()) { StreamingRow(item, MessageStyle.Open, ChatScreenActions().message) }
    }
}

@DemoPreviews
@Composable
internal fun EmptyChatPreview() {
    PreviewFrame { EmptyChat(onStarter = {}, Modifier.height(480.dp)) }
}

internal class LimitStates : PreviewParameterProvider<Limit> {
    override val values =
        sequenceOf(
            ChatPreviewData.memoryLimit,
            ChatPreviewData.goneLimit,
            ChatPreviewData.contextLimit,
            ChatPreviewData.visionLimit,
        )
}

@DemoPreviews
@Composable
internal fun LimitLinePreview(
    @PreviewParameter(LimitStates::class) limit: Limit,
) {
    PreviewFrame {
        Column(Modifier.gutterPadding()) {
            ChatContentLimit(limit)
        }
    }
}

@Composable
private fun ChatContentLimit(limit: Limit) {
    com.suhel.llamabro.demo.designsystem.components
        .LimitLine(limit.line, limit.actionLabel, onAction = {})
}

@DemoPreviews
@Composable
internal fun ThinkMenuPreview() {
    PreviewFrame {
        Column(
            Modifier.gutterPadding(),
        ) { SheetFrame { ThinkMenuContent(ThinkLevel.Balanced, onPick = {}, onDone = {}) } }
    }
}

@DemoPreviews
@Composable
internal fun AttachMenuPreview() {
    PreviewFrame {
        Column(Modifier.gutterPadding()) {
            SheetFrame { AttachMenuContent(useLibrary = true, onPhoto = {}, onUseLibrary = {}, onDone = {}) }
        }
    }
}

internal class PassageStates : PreviewParameterProvider<PassageView> {
    override val values = sequenceOf(ChatPreviewData.passage, ChatPreviewData.passageAlone)
}

@DemoPreviews
@Composable
internal fun PassageSheetPreview(
    @PreviewParameter(PassageStates::class) view: PassageView,
) {
    PreviewFrame { Column(Modifier.gutterPadding()) { SheetFrame { PassageSheetContent(view) } } }
}

// last used relative to now, so the rows read "Used today" and "2 weeks ago" as on the phone
private val previewNow = System.currentTimeMillis()
private const val PREVIEW_DAY = 24L * 3600 * 1000

private val previewLibrary =
    listOf(
        LibraryModel(
            HfModel.parse("unsloth/Qwen3.5-0.8B-GGUF:Q4_K_M"),
            "Qwen3.5 0.8B",
            739_863_648,
            previewNow,
            isDefault = false,
        ),
        LibraryModel(
            HfModel.parse("unsloth/gemma-3-4b-it-qat-GGUF:Q4_K_M"),
            "Gemma 3 4B",
            3_341_145_472,
            previewNow - 14 * PREVIEW_DAY,
            isDefault = true,
        ),
        LibraryModel(
            HfModel.parse("unsloth/Qwen3.5-4B-GGUF:Q4_K_M"),
            "Qwen3.5 4B",
            3_416_507_232,
            null,
            isDefault = false,
        ),
    )

@DemoPreviews
@Composable
internal fun ModelSwitcherPreview() {
    PreviewFrame {
        Column(Modifier.gutterPadding()) {
            SheetFrame {
                ModelSwitcherContent(
                    previewLibrary,
                    current = previewLibrary[0].key,
                    facts = DeviceFacts(budgetBytes = 5L shl 30, chip = DeviceKind.Npu),
                    onSwitch = {},
                    onUseForNewChats = {},
                    onInfo = {},
                    onGetMore = {},
                    onDone = {},
                    sampling = SheetSampling(changed = 2, onOpen = {}),
                )
            }
        }
    }
}

@DemoPreviews
@Composable
internal fun ChatSamplingPreview() {
    val card = Catalog.byRepo("unsloth/Qwen3.5-0.8B-GGUF")?.sampling
    val layers = SamplingLayers(card, Knobs.of(Knob.TopK to 30f), Knobs.of(Knob.Temperature to 0.6f))
    PreviewFrame {
        Column(Modifier.gutterPadding()) {
            SheetFrame { ChatSamplingContent(layers, thinks = true, onChange = { _, _ -> }, onReset = {}) }
        }
    }
}

internal class MenuStates : PreviewParameterProvider<Boolean> {
    override val values = sequenceOf(true, false)
}

@DemoPreviews
@Composable
internal fun MessageMenuPreview(
    @PreviewParameter(MenuStates::class) isReply: Boolean,
) {
    PreviewFrame {
        Column(Modifier.gutterPadding()) {
            SheetFrame { MessageMenuContent("The text", 1, isReply, ChatScreenActions().message, onDone = {}) }
        }
    }
}

@DemoPreviews
@Composable
internal fun ShareCardPreview() {
    PreviewFrame {
        Column(Modifier.gutterPadding()) {
            ShareCard(
                ShareCardContent("Here's a tighter version that leads with what you do.", "Gemma 3 4B", "38 words/s"),
            )
        }
    }
}
