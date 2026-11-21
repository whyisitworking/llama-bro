package com.suhel.llamabro.demo.ui.chat

import android.net.Uri
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.suhel.llamabro.demo.chat.ChatEngine
import com.suhel.llamabro.demo.chat.ChatModelState
import com.suhel.llamabro.demo.chat.ClosedNotes
import com.suhel.llamabro.demo.chat.Limit
import com.suhel.llamabro.demo.chat.Limits
import com.suhel.llamabro.demo.chat.Markdown
import com.suhel.llamabro.demo.chat.StreamingReply
import com.suhel.llamabro.demo.chat.Take
import com.suhel.llamabro.demo.chat.VoiceChat
import com.suhel.llamabro.demo.chat.VoiceConversation
import com.suhel.llamabro.demo.chat.VoicePlayer
import com.suhel.llamabro.demo.chat.VoiceRecorder
import com.suhel.llamabro.demo.data.MessageKind
import com.suhel.llamabro.demo.designsystem.components.ComposerState
import com.suhel.llamabro.demo.designsystem.components.MarkdownBlocks
import com.suhel.llamabro.demo.designsystem.components.RecordingState
import com.suhel.llamabro.demo.designsystem.components.ThinkLevel
import com.suhel.llamabro.demo.models.DownloadStatus
import com.suhel.llamabro.demo.ui.shell.ChatListFormat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.flow.stateIn
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

/** What the message bar takes from the engine and the recorder: Think, a reply coming, hearing, talking, a take. */
private data class BarInputs(
    val think: ThinkLevel? = null,
    val busy: Boolean = false,
    val hears: Boolean = false,
    val talks: Boolean = false,
    val take: Take? = null,
)

/** The message bar's text and what is attached to it, apart from the list so typing never touches the messages. */
@Immutable
data class ComposerDraft(val text: String = "", val attachments: List<String> = emptyList(), val editing: Int? = null)

/**
 * The chat screen's state over a [ChatEngine.ChatHandle]. The message list, the streaming reply and the composer are
 * three separate states (the composer is snapshot state), so a token redraws one row and a keystroke only the bar.
 */
class ChatViewModel(
    val handle: ChatEngine.ChatHandle,
    /** How the rows are laid out, as the layout setting says (Settings › Layout can change it while a chat is open). */
    private val styles: Flow<MessageStyle>,
    private val saveImage: suspend (Uri) -> String,
    private val discardImages: suspend (List<String>) -> Unit,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val locale: Locale = Locale.getDefault(),
    private val clock: () -> Long = System::currentTimeMillis,
    /** The stored title of a chat (the chat list layout's header); empty until the chat exists. */
    titles: (Long) -> Flow<String?> = { emptyFlow() },
    /**
     * What outlives the process: the draft, and the id a new chat gets with its first message, so the screen restored
     * after process death reopens that chat with the draft in the bar ([chatIdToOpen]).
     */
    private val savedState: SavedStateHandle,
    /** The app's scope: work that must finish after this view model is gone (deleting a draft's pictures). */
    private val background: CoroutineScope,
    /** Records voice messages (D76); null where there is no microphone (most tests). */
    private val recorder: VoiceRecorder? = null,
    /** Plays the chat's voice messages (D76). */
    val player: VoicePlayer = VoicePlayer.None,
    /** Starts voice mode in a chat (D100); null where there is no microphone (most tests). */
    voiceModeStart: ((VoiceChat) -> VoiceConversation)? = null,
) : ViewModel() {
    private val parsed = HashMap<Long, Pair<String, MarkdownBlocks>>()

    private fun itemsFor(style: MessageStyle) =
        ChatItems(style, zone, locale, clock) { id, text ->
            parsed[id]?.takeIf { it.first == text }?.second ?: Markdown.parse(text).also { parsed[id] = text to it }
        }

    private val builders = MessageStyle.entries.associateWith(::itemsFor)

    // eagerly: the engine handle this view model owns runs for as long as the chat is open, with or without a screen
    private val eager = SharingStarted.Eagerly

    /** The rows of the list, without the reply that is still being written. */
    val rows: StateFlow<List<ChatItem>> =
        combine(
            handle.messages,
            handle.model,
            handle.name,
            styles.distinctUntilChanged(),
        ) { messages, model, name, style ->
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

    /** True while the model is loading from the phone's storage: the hairline under the top bar shows it. */
    val waking: StateFlow<Boolean> =
        combine(handle.model, handle.name) { model, name ->
            model is ChatModelState.Waking &&
                name.isNotEmpty()
        }.stateIn(viewModelScope, eager, false)

    /** How far the load has come, 0 to 1, while waking; null before the SDK's first report. */
    val wakeProgress: StateFlow<Float?> =
        handle.model
            .map { (it as? ChatModelState.Waking)?.progress }
            .stateIn(viewModelScope, eager, null)

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

    /** Whether the model takes voice messages (D76): the mic shows only then. */
    val hears: StateFlow<Boolean> =
        handle.model
            .map {
                (it as? ChatModelState.Ready)?.hears ?: false
            }.stateIn(viewModelScope, eager, false)

    /** Whether the model holds a spoken conversation (D100): the voice-mode button shows only then. */
    val talks: StateFlow<Boolean> =
        handle.model
            .map {
                (it as? ChatModelState.Ready)?.talks ?: false
            }.stateIn(viewModelScope, eager, false)

    /** Voice mode (D100): the full-screen view over the chat while it is open. */
    val voiceMode =
        VoiceModeHolder(viewModelScope, voiceModeStart, handle::voiceChat) { handle.messages.value.isNotEmpty() }

    /** The note the person closed; the line leaves it out (the engine's own limit is left as it is). */
    val notes = ClosedNotes()

    private val sending = Mutex()

    /** The bar's voice messages (D76); null without a microphone. */
    val voice: VoiceComposer? =
        recorder?.let { r ->
            VoiceComposer(
                r,
                handle,
                viewModelScope,
                canRecord = { hears.value && draft.value.editing == null },
                sending = sending,
                photos = { draft.value.attachments },
                onSent = { sent ->
                    updateDraft { if (it.attachments == sent) it.copy(attachments = emptyList()) else it }
                    _problem.value = null
                },
                problem = { _problem.value = it },
            )
        }

    /**
     * The one limit line shown above the bar: the engine's, else the model's own: gone from the phone, or its
     * download stopped (a token, the network, the disk). A closed note is left out.
     */
    val limit: StateFlow<Limit?> =
        combine(
            handle.limit,
            handle.model,
            handle.name,
            notes.closed,
            voice?.limit ?: flowOf(null),
        ) { limit, model, name, closed, mic ->
            mic?.takeUnless { it == closed }
                ?: limit?.takeUnless { it == closed }
                ?: when (model) {
                    is ChatModelState.Missing -> Limits.modelGone(name.ifEmpty { model.name })
                    is ChatModelState.DownloadFailed -> Limits.forDownload(model.failure)
                    else -> null
                }
        }.stateIn(viewModelScope, eager, null)

    // the person's actions' outcome (a photo that couldn't be used, a message that couldn't be stored): an input
    private val _problem = MutableStateFlow<String?>(null)

    /**
     * A plain line above the bar when a picked photo can't be used or a message couldn't be stored; the next successful
     * pick or send clears it.
     */
    val problem: StateFlow<String?> = _problem.asStateFlow()

    /** Everything the screen draws apart from the reply being written and the message bar, derived in one place. */
    val uiState: StateFlow<ChatScreenState> =
        combine(
            combine(header, title, waking, wakeProgress, ::ChatBar),
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

    /**
     * The draft is snapshot state, written in the keystroke's own call: the text field's value never waits on a
     * coroutine (a derived flow would hand the field an old value and lose or reorder typing). Each change is also
     * written to [savedState].
     */
    private val draft = mutableStateOf(restoredDraft())
    private val draftLock = Any()
    val composer: State<ComposerDraft> = draft

    /**
     * What the bar takes from the engine and the recorder, combined into one value and held as snapshot state: the one
     * bridge from those flows, so [composerState] is derived with the draft in the keystroke's own call.
     */
    private val barInputs = mutableStateOf(BarInputs())

    /** What the design system's message bar draws, derived synchronously from the draft (no Think bulb: null). */
    val composerState: State<ComposerState> =
        derivedStateOf {
            val d = draft.value
            val bar = barInputs.value
            ComposerState(
                text = d.text,
                canAttach = d.editing == null,
                think = bar.think,
                busy = bar.busy,
                hasAttachment = d.attachments.isNotEmpty(),
                canRecord = voice != null && bar.hears && d.editing == null,
                recording = bar.take?.let(::recordingState),
                canTalk = voiceMode.available && bar.talks && d.editing == null,
            )
        }

    init {
        viewModelScope.launch {
            combine(handle.think, handle.busy, hears, talks, voice?.take ?: flowOf(null), ::BarInputs)
                .collect { barInputs.value = it }
        }
        // a new chat's id, once its first message made it: the restored screen reopens it instead of a blank chat
        viewModelScope.launch { handle.chatId.collect { if (it != 0L) savedState[CHAT_ID_KEY] = it } }
    }

    val busy: StateFlow<Boolean> get() = handle.busy

    fun onText(text: String) = updateDraft { it.copy(text = text) }

    private fun restoredDraft() =
        ComposerDraft(
            text = savedState.get<String>(DRAFT_TEXT_KEY).orEmpty(),
            attachments = savedState.get<ArrayList<String>>(DRAFT_IMAGES_KEY).orEmpty(),
            editing = savedState.get<Int>(DRAFT_EDITING_KEY),
        )

    /** Applies [change] to the draft atomically (two photo picks can finish together) and saves the result. */
    private fun updateDraft(change: (ComposerDraft) -> ComposerDraft): ComposerDraft =
        synchronized(draftLock) {
            val before = draft.value
            val after = change(before)
            if (after != before) {
                draft.value = after
                savedState[DRAFT_TEXT_KEY] = after.text
                savedState[DRAFT_IMAGES_KEY] = ArrayList(after.attachments)
                savedState[DRAFT_EDITING_KEY] = after.editing
            }
            before
        }

    /**
     * Sends the draft (or, while editing, replaces the message with it). The bar is emptied once the engine has taken
     * the message; when it refuses (a picture for a model that can't see, no model) the draft stays as it was.
     */
    fun send() {
        val d = draft.value
        if (d.text.isBlank() && d.attachments.isEmpty()) return
        if (d.editing != null) {
            handle.edit(d.editing, d.text)
            updateDraft { ComposerDraft() }
            return
        }
        if (!sending.tryLock()) return
        viewModelScope.launch {
            try {
                if (handle.send(d.text, d.attachments)) {
                    // what was typed or picked meanwhile stays; what was sent goes
                    updateDraft { if (it.text == d.text && it.attachments == d.attachments) ComposerDraft() else it }
                    _problem.value = null
                }
            } catch (e: CancellationException) {
                throw e
            } catch (ignored: Exception) {
                // the database refused the message (its chat went meanwhile): the draft stays, and the bar says so
                _problem.value = "That message couldn't be sent."
            } finally {
                sending.unlock()
            }
        }
    }

    /** A starter chip fills the bar and sends. */
    fun sendStarter(text: String) {
        updateDraft { ComposerDraft(text = text) }
        send()
    }

    fun stop() = handle.stop()

    /** Opens the user message at [position] in the bar; sending replaces it and what followed (`edit`). */
    fun startEdit(position: Int) {
        val m = handle.messages.value.firstOrNull { it.position == position && it.kind == MessageKind.User } ?: return
        updateDraft { ComposerDraft(text = m.text, editing = position) }
    }

    fun cancelEdit() {
        updateDraft { it.copy(text = "", editing = null) }
    }

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
                updateDraft {
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
        updateDraft { it.copy(attachments = it.attachments - name) }
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
        val eta = s.etaSeconds?.let { " · about ${if (it < 60) "$it s" else "${(it + 30) / 60} min"}" }.orEmpty()
        return "$pct%$eta"
    }

    override fun onCleared() {
        // the chat is gone: its engine handle ends, and pictures attached to a draft nobody sent are deleted, with a
        // recording nobody sent
        val left = draft.value.attachments
        voiceMode.close()
        handle.close()
        player.release()
        if (left.isNotEmpty()) background.launch { discardImages(left) }
        recorder?.let { r -> background.launch { r.discard() } }
    }

    companion object {
        /** The bar's view of a recording: its levels, its time, and whether the limit is near (D76). */
        fun recordingState(take: Take): RecordingState =
            RecordingState(
                take.levels,
                clipLength(take.millis),
                live = take.live,
                nearEnd = take.live && take.millis >= VoiceRecorder.LIMIT_MILLIS - VoiceRecorder.NEAR_END_MILLIS,
            )

        /** The chat route's own argument name, so in a back-stack entry the saved id overrides the route's 0. */
        const val CHAT_ID_KEY = "chatId"
        private const val DRAFT_TEXT_KEY = "draft.text"
        private const val DRAFT_IMAGES_KEY = "draft.images"
        private const val DRAFT_EDITING_KEY = "draft.editing"
        private const val MAX_IMAGES = 4
        private const val MILLIS_PER_SECOND = 1000.0

        /** The chat to open: the one a new chat became before the process died, else the route's [routeId]. */
        fun chatIdToOpen(savedState: SavedStateHandle, routeId: Long): Long =
            savedState.get<Long>(CHAT_ID_KEY)?.takeIf { it != 0L } ?: routeId
    }
}
