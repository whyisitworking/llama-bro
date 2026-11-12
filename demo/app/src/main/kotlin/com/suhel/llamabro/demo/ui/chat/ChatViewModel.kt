package com.suhel.llamabro.demo.ui.chat

import android.net.Uri
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.suhel.llamabro.demo.chat.ChatEngine
import com.suhel.llamabro.demo.chat.ChatModelState
import com.suhel.llamabro.demo.chat.Limit
import com.suhel.llamabro.demo.chat.Limits
import com.suhel.llamabro.demo.chat.Markdown
import com.suhel.llamabro.demo.chat.StreamingReply
import com.suhel.llamabro.demo.data.MessageKind
import com.suhel.llamabro.demo.designsystem.components.ComposerState
import com.suhel.llamabro.demo.designsystem.components.MarkdownBlocks
import com.suhel.llamabro.demo.designsystem.components.ThinkLevel
import com.suhel.llamabro.demo.models.DownloadStatus
import com.suhel.llamabro.demo.ui.shell.ChatListFormat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import java.time.ZoneId
import java.util.Locale
import kotlin.math.roundToInt

/** What the top bar shows: the model's name, and while it downloads the ring's fraction and the label beside it. */
@Immutable
data class ChatHeader(val name: String, val downloadFraction: Float?, val downloadLabel: String?)

/** The reply being written, ready to draw: its text parsed, its thoughts and how long it has thought. */
@Immutable
data class StreamingItem(
    val id: Long,
    val text: String,
    val blocks: MarkdownBlocks,
    val reasoning: String?,
    val thoughtSeconds: Int?,
    val thinking: Boolean,
)

/** The message bar's text and what is attached to it, apart from the list so typing never touches the messages. */
@Immutable
data class ComposerDraft(val text: String = "", val attachments: List<String> = emptyList(), val editing: Int? = null)

/**
 * The chat screen's state over a [ChatEngine.ChatHandle]. The message list, the streaming reply and the composer are
 * three flows, so a token redraws one row and a keystroke redraws only the bar.
 */
class ChatViewModel(
    val handle: ChatEngine.ChatHandle,
    initialStyle: MessageStyle,
    private val saveImage: suspend (Uri) -> String,
    private val discardImages: suspend (List<String>) -> Unit,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val locale: Locale = Locale.getDefault(),
    private val clock: () -> Long = System::currentTimeMillis,
    /** The stored title of a chat (the chat list layout's header); empty until the chat exists. */
    titles: (Long) -> Flow<String?> = { emptyFlow() },
) : ViewModel() {
    private val parsed = HashMap<Long, Pair<String, MarkdownBlocks>>()

    private fun itemsFor(style: MessageStyle) =
        ChatItems(style, zone, locale, clock) { id, text ->
            parsed[id]?.takeIf { it.first == text }?.second ?: Markdown.parse(text).also { parsed[id] = text to it }
        }

    private val builders = MessageStyle.entries.associateWith(::itemsFor)

    private val styleFlow = MutableStateFlow(initialStyle)

    /** The layout can change while a chat is open (Settings > Layout): the rows follow it. */
    fun setStyle(style: MessageStyle) {
        styleFlow.value = style
    }

    // eagerly: the engine handle this view model owns runs for as long as the chat is open, with or without a screen
    private val eager = SharingStarted.Eagerly

    /** The rows of the list, without the reply that is still being written. */
    val rows: StateFlow<List<ChatItem>> =
        combine(handle.messages, handle.model, handle.name, styleFlow) { messages, model, name, style ->
            val waiting =
                when (model) {
                    is ChatModelState.Downloading -> name
                    is ChatModelState.Missing -> model.name
                    is ChatModelState.DownloadFailed -> model.name
                    else -> null
                }
            builders.getValue(style).build(messages, waiting?.ifEmpty { null })
        }.stateIn(viewModelScope, eager, emptyList())

    /** The reply being written, or null. Its blocks keep the instances of finished ones between tokens. */
    val streaming: StateFlow<StreamingItem?> =
        handle.streaming
            .scan(null as Pair<StreamingReply, Markdown.Parsed>?) { previous, reply ->
                reply?.let { it to Markdown.parseKeeping(it.text, previous?.second) }
            }.map { current -> current?.let { (reply, parsed) -> streamingItem(reply, parsed) } }
            .stateIn(viewModelScope, eager, null)

    private fun streamingItem(reply: StreamingReply, parsed: Markdown.Parsed) =
        StreamingItem(
            id = reply.messageId,
            text = reply.text,
            blocks = parsed.blocks,
            reasoning = reply.reasoning,
            thoughtSeconds = reply.reasoningMillis?.let { (it / MILLIS_PER_SECOND).roundToInt() },
            thinking = reply.thinking,
        )

    val header: StateFlow<ChatHeader> =
        combine(handle.name, handle.model) { name, model ->
            val downloading = (model as? ChatModelState.Downloading)?.status
            when (downloading) {
                is DownloadStatus.Downloading -> {
                    ChatHeader(
                        name,
                        fraction(downloading.bytesDone, downloading.bytesTotal),
                        downloadLabel(downloading),
                    )
                }

                is DownloadStatus.Partial -> {
                    ChatHeader(name, fraction(downloading.bytesDone, downloading.bytesTotal), null)
                }

                else -> {
                    ChatHeader(name, null, null)
                }
            }
        }.stateIn(viewModelScope, eager, ChatHeader("", null, null))

    /** The chat's title, for the chat list layout's header. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val title: StateFlow<String> =
        handle.chatId
            .flatMapLatest {
                titles(it)
            }.map { it.orEmpty() }
            .stateIn(viewModelScope, eager, "")

    /** True while the model is loading from the phone's storage: the hairline sweeps, nothing else. */
    val waking: StateFlow<Boolean> =
        combine(handle.model, handle.name) { model, name ->
            model is ChatModelState.Waking &&
                name.isNotEmpty()
        }.stateIn(viewModelScope, eager, false)

    /** A chat whose model is gone from the phone can be read but not continued. */
    val readOnly: StateFlow<Boolean> =
        handle.model
            .map {
                it is ChatModelState.Missing
            }.stateIn(viewModelScope, eager, false)

    /** Whether the model can look at pictures (for the attach button's no-vision line). */
    val sees: StateFlow<Boolean> =
        handle.model
            .map {
                (it as? ChatModelState.Ready)?.sees ?: false
            }.stateIn(viewModelScope, eager, false)

    /**
     * The one limit line shown above the bar: the engine's, else the model's own: gone from the phone, or its
     * download stopped (a token, the network, the disk).
     */
    val limit: StateFlow<Limit?> =
        combine(handle.limit, handle.model, handle.name) { limit, model, name ->
            limit
                ?: when (model) {
                    is ChatModelState.Missing -> Limits.modelGone(name.ifEmpty { model.name })
                    is ChatModelState.DownloadFailed -> Limits.forDownload(model.failure)
                    else -> null
                }
        }.stateIn(viewModelScope, eager, null)

    private val _problem = MutableStateFlow<String?>(null)

    /** A plain line above the bar when a picked photo can't be used; the next successful pick or send clears it. */
    val problem: StateFlow<String?> = _problem

    /** Everything the screen draws apart from the reply being written and the message bar, derived in one place. */
    val uiState: StateFlow<ChatScreenState> =
        combine(
            combine(header, title, waking, ::ChatBar),
            rows,
            handle.busy,
            combine(readOnly, problem, limit, ::ChatBottom),
        ) { bar, rows, busy, bottom ->
            ChatScreenState(bar, rows, busy, bottom)
        }.stateIn(
            viewModelScope,
            eager,
            ChatScreenState(
                ChatBar(ChatHeader("", null, null), "", false),
                emptyList(),
                false,
                ChatBottom(false, null, null),
            ),
        )

    private val draft = MutableStateFlow(ComposerDraft())
    val composer: StateFlow<ComposerDraft> = draft

    /** What the design system's message bar draws. The Think bulb is absent when the model can't think. */
    val composerState: StateFlow<ComposerState> =
        combine(draft, handle.think, handle.busy) { d, think, busy ->
            ComposerState(
                text = d.text,
                canAttach = d.editing == null,
                think = think,
                busy = busy,
                hasAttachment = d.attachments.isNotEmpty(),
            )
        }.stateIn(viewModelScope, eager, ComposerState("", canAttach = true, think = null, busy = false))

    val busy: StateFlow<Boolean> get() = handle.busy

    fun onText(text: String) = draft.update { it.copy(text = text) }

    private val sending = Mutex()

    /**
     * Sends the draft (or, while editing, replaces the message with it). The bar is emptied once the engine has taken
     * the message; when it refuses (a picture for a model that can't see, no model) the draft stays as it was.
     */
    fun send() {
        val d = draft.value
        if (d.text.isBlank() && d.attachments.isEmpty()) return
        if (d.editing != null) {
            handle.edit(d.editing, d.text)
            draft.value = ComposerDraft()
            return
        }
        if (!sending.tryLock()) return
        viewModelScope.launch {
            try {
                if (handle.send(d.text, d.attachments)) {
                    // what was typed or picked meanwhile stays; what was sent goes
                    draft.update { if (it.text == d.text && it.attachments == d.attachments) ComposerDraft() else it }
                    _problem.value = null
                }
            } finally {
                sending.unlock()
            }
        }
    }

    /** A starter chip fills the bar and sends. */
    fun sendStarter(text: String) {
        draft.value = ComposerDraft(text = text)
        send()
    }

    fun stop() = handle.stop()

    /** Opens the user message at [position] in the bar; sending replaces it and what followed (`edit`). */
    fun startEdit(position: Int) {
        val m = handle.messages.value.firstOrNull { it.position == position && it.kind == MessageKind.User } ?: return
        draft.value = ComposerDraft(text = m.text, editing = position)
    }

    fun cancelEdit() = draft.update { it.copy(text = "", editing = null) }

    fun regenerate(position: Int) = handle.regenerate(position)

    /** Tries again: the model's download if it stopped, else the last question after a failed load or reply. */
    fun retry() {
        if (handle.model.value is ChatModelState.DownloadFailed) return handle.retryDownload()
        val last = handle.messages.value.lastOrNull() ?: return
        // a failed reply is replaced; a question nobody answered is asked again
        handle.regenerate(if (last.kind == MessageKind.Assistant) last.position else last.position + 1)
    }

    fun setThink(level: ThinkLevel) = handle.setThink(level)

    /** The system has no photo picker to open: one plain line instead of a crash. */
    fun photoPickerMissing() {
        _problem.value = "This phone has no photo picker."
    }

    /** Saves the picked photo (downscaled) and attaches it; at most four. */
    fun attach(uri: Uri) = attachWith { saveImage(uri) }

    internal fun attachWith(save: suspend () -> String) {
        if (draft.value.attachments.size >= MAX_IMAGES || draft.value.editing != null) return
        viewModelScope.launch {
            val name =
                try {
                    save()
                } catch (e: CancellationException) {
                    throw e
                } catch (ignored: Exception) {
                    _problem.value = "That photo couldn't be opened."
                    return@launch
                }
            // two picks can race past the limit: the draft decides, atomically, whether this one fits
            val before =
                draft.getAndUpdate {
                    if (it.canTakePhoto()) {
                        it.copy(
                            attachments = it.attachments + name,
                        )
                    } else {
                        it
                    }
                }
            if (before.canTakePhoto()) _problem.value = null else discardImages(listOf(name))
        }
    }

    private fun ComposerDraft.canTakePhoto() = attachments.size < MAX_IMAGES && editing == null

    fun removeAttachment(name: String) {
        draft.update { it.copy(attachments = it.attachments - name) }
        viewModelScope.launch { discardImages(listOf(name)) }
    }

    /** The first 600 characters of the reply at [position], for the share card. */
    fun shareText(position: Int): String? =
        handle.messages.value
            .firstOrNull {
                it.position == position && it.kind == MessageKind.Assistant
            }?.let { ShareText.cut(it.text) }

    /** The name of the model that wrote the reply at [position], which a mid-chat switch may have changed. */
    fun shareModelName(position: Int): String? =
        handle.messages.value
            .firstOrNull {
                it.position == position
            }?.modelKey
            ?.let(ChatListFormat::modelName)

    fun shareSpeed(position: Int): String? =
        handle.messages.value
            .firstOrNull { it.position == position }
            ?.let { ChatItems.speed(it.wordsPerSecond) }

    private fun fraction(done: Long, total: Long): Float =
        if (total > 0) {
            (done.toFloat() / total).coerceIn(0f, 1f)
        } else {
            0f
        }

    private fun downloadLabel(s: DownloadStatus.Downloading): String {
        val pct = (fraction(s.bytesDone, s.bytesTotal) * 100).roundToInt()
        val eta = s.etaSeconds?.let { " · about ${if (it < 60) "$it s" else "${(it + 30) / 60} min"}" } ?: ""
        return "$pct%$eta"
    }

    override fun onCleared() {
        // the chat is gone: its engine handle ends, and pictures attached to a draft nobody sent are deleted
        val left = draft.value.attachments
        handle.close()
        if (left.isNotEmpty()) CoroutineScope(Dispatchers.IO).launch { discardImages(left) }
    }

    private companion object {
        const val MAX_IMAGES = 4
        const val MILLIS_PER_SECOND = 1000.0
    }
}
