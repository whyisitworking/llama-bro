package com.suhel.llamabro.demo.chat

import androidx.tracing.trace
import com.suhel.llamabro.LlamaBroException
import com.suhel.llamabro.ModelInfo
import com.suhel.llamabro.chat.ChatEvent
import com.suhel.llamabro.chat.Content
import com.suhel.llamabro.conversation
import com.suhel.llamabro.conversation.Conversation
import com.suhel.llamabro.demo.data.ChatRepository
import com.suhel.llamabro.demo.data.MessageChange
import com.suhel.llamabro.demo.data.MessageKind
import com.suhel.llamabro.demo.data.MessageState
import com.suhel.llamabro.demo.data.NewMessage
import com.suhel.llamabro.demo.data.SettingsRepository
import com.suhel.llamabro.demo.data.StoredMessage
import com.suhel.llamabro.demo.data.modelForNewChat
import com.suhel.llamabro.demo.designsystem.components.ThinkLevel
import com.suhel.llamabro.demo.models.Catalog
import com.suhel.llamabro.demo.models.DownloadFailure
import com.suhel.llamabro.demo.models.DownloadStatus
import com.suhel.llamabro.demo.models.LibraryModel
import com.suhel.llamabro.demo.models.ModelRepository
import com.suhel.llamabro.hub.HfModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private fun sdkStream(
    convo: Conversation,
    text: String,
    images: Array<Content.Image>,
    level: ThinkLevel?,
): Flow<ChatEvent> = convo.send(text, *images) { if (level != null) reasoning { Thinking.configure(level, this) } }

/** The divider line for a switch to [key]. */
internal fun switchNotice(key: HfModel, name: String, now: Long) =
    NewMessage(MessageKind.Switch, "Switched to $name", modelKey = key.toString(), sentAt = now)

/** Whether choosing a model now switches from another one: only once the chat has a question in it. */
internal fun isSwitch(rows: List<StoredMessage>): Boolean = rows.any { it.kind == MessageKind.User }

/**
 * After a cut at [position] (an edit, a regenerate): the model to say "Switched to" for again, or null. The cut can
 * remove the divider that named the model answering, while the model in use stays the latest one; when the last
 * divider or reply that is left names another model than [current], the transcript must say so, or a reply would
 * stand under a divider for a model that did not write it.
 */
internal fun modelToRestate(rows: List<StoredMessage>, position: Int, current: HfModel?): HfModel? {
    val answering =
        rows
            .lastOrNull {
                it.position < position &&
                    (it.kind == MessageKind.Switch || it.kind == MessageKind.Assistant) &&
                    it.modelKey != null
            }?.modelKey
    return current?.takeIf { answering != null && answering != it.toString() }
}

/** The reply being written right now. It is not in [ChatHandle.messages] until the row changes state. */
data class StreamingReply(
    val messageId: Long,
    val text: String,
    val reasoning: String?,
    /** Set once the model has answered after thinking: how long the thinking took. */
    val reasoningMillis: Long?,
    val thinking: Boolean,
)

/** What the chat knows about its model, for the header, the composer and the held-message note. */
sealed interface ChatModelState {
    data class Downloading(val status: DownloadStatus?) : ChatModelState

    data object Waking : ChatModelState

    data class Ready(val name: String, val sees: Boolean, val canThink: Boolean) : ChatModelState

    data class Missing(val name: String) : ChatModelState

    /** The model is on its way but the download stopped: [failure] says why (a token, the network, the disk). */
    data class DownloadFailed(val name: String, val failure: DownloadFailure) : ChatModelState
}

/**
 * Drives chats through the SDK's `Conversation`. Room is the source of truth: a conversation is rebuilt from stored
 * messages after an edit, a regenerate, a switch or a reopen, and reused for a plain send so the server's prompt
 * cache keeps hitting. One instance per app; [open] gives a handle per chat.
 */
class ChatEngine(
    private val chats: ChatRepository,
    private val host: ModelHost,
    private val models: ModelRepository,
    private val settings: SettingsRepository,
    private val clock: () -> Long,
    private val scope: CoroutineScope,
    private val frameMillis: Long = 16,
    private val writeMillis: Long = 250,
    /** How a turn reaches the model; tests replace it to script timing. */
    private val streamer: (Conversation, String, Array<Content.Image>, ThinkLevel?) -> Flow<ChatEvent> = ::sdkStream,
) {
    /** [chatId] 0 is a new chat: it is created with the first message. */
    fun open(chatId: Long): ChatHandle = ChatHandle(chatId)

    @OptIn(ExperimentalCoroutinesApi::class)
    inner class ChatHandle internal constructor(initialChatId: Long) {
        /**
         * Everything this handle runs lives in its own scope, under the engine's: [close] ends it, so a chat that was
         * left stops observing the database, stops pumping held messages and stops its reply (which is saved as
         * stopped).
         */
        private val hs = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))

        /** Ends this handle. Call once, when the chat screen is gone. */
        fun close() = hs.cancel()

        private val _chatId = MutableStateFlow(initialChatId)
        val chatId: StateFlow<Long> = _chatId.asStateFlow()

        val messages: StateFlow<List<StoredMessage>> =
            _chatId
                .flatMapLatest { if (it == 0L) emptyFlow() else chats.messages(it) }
                .stateIn(hs, SharingStarted.Eagerly, emptyList())

        private val _streaming = MutableStateFlow<StreamingReply?>(null)
        val streaming: StateFlow<StreamingReply?> = _streaming.asStateFlow()

        private val _busy = MutableStateFlow(false)
        val busy: StateFlow<Boolean> = _busy.asStateFlow()

        private val _limit = MutableStateFlow<Limit?>(null)
        val limit: StateFlow<Limit?> = _limit.asStateFlow()

        private val _think = MutableStateFlow<ThinkLevel?>(null)

        /** Null when the model can't think. */
        val think: StateFlow<ThinkLevel?> = _think.asStateFlow()

        private val modelKey = MutableStateFlow<HfModel?>(null)
        private val library: StateFlow<List<LibraryModel>?> =
            models.library.stateIn(hs, SharingStarted.Eagerly, null)

        /** The model this chat uses (null until it is known), for the switcher's check mark. */
        val current: StateFlow<HfModel?> = modelKey.asStateFlow()

        /** The model's name for the header, whatever state it is in; empty until the model is known. */
        val name: StateFlow<String> =
            combine(modelKey, library) { key, lib ->
                key?.let { k -> lib?.firstOrNull { it.key == k }?.name ?: displayName(k) } ?: ""
            }.stateIn(hs, SharingStarted.Eagerly, "")

        val model: StateFlow<ChatModelState> =
            combine(
                modelKey,
                library,
                host.state,
            ) { key, lib, hostState -> key to (lib to hostState) }
                .flatMapLatest { (key, rest) ->
                    val (lib, hostState) = rest
                    if (key == null || lib == null) {
                        emptyFlow()
                    } else {
                        val name = lib.firstOrNull { it.key == key }?.name ?: displayName(key)
                        if (lib.none { it.key == key }) {
                            models.state(key).let { status ->
                                kotlinx.coroutines.flow.flow { status.collect { emit(missingOrDownloading(name, it)) } }
                            }
                        } else {
                            kotlinx.coroutines.flow.flowOf(readyOrWaking(key, name, hostState))
                        }
                    }
                }.stateIn(hs, SharingStarted.Eagerly, ChatModelState.Waking)

        private val turn = Mutex()
        private var running: Job? = null
        private var conversation: Conversation? = null
        private var conversationKey: HfModel? = null

        init {
            hs.launch {
                modelKey.value = initialModelKey()
                syncThink()
            }
            // a model that arrives (a download finishes) answers what was held
            hs.launch { library.collect { if (it != null) pump() } }
        }

        // ---- the user's actions -------------------------------------------------------------------------

        /**
         * Whether the message was taken: false while a reply is coming, for an empty message, when the chat has no
         * model
         * at all, and for pictures sent to a model that can't see them (the limit line says so). The caller keeps the
         * draft when this is false. A taken message is stored as held and answered as soon as its model is on the
         * phone.
         */
        suspend fun send(text: String, images: List<String> = emptyList()): Boolean {
            if (_busy.value) return false
            if (text.isBlank() && images.isEmpty()) return false
            val key = modelKey.value ?: initialModelKey()?.also { modelKey.value = it } ?: return false
            val names = images.take(MAX_IMAGES)
            // the model's own answer once it is loaded; until then what the catalog says about it
            val sees = (model.value as? ChatModelState.Ready)?.sees ?: Catalog.byKey(key)?.sees ?: false
            if (names.isNotEmpty() && !sees) {
                _limit.value = Limits.noVision(currentName(), visionModelName())
                return false
            }
            val id = ensureChat() ?: return false
            _limit.value = null
            chats.append(
                id,
                NewMessage(MessageKind.User, text.trim(), names, state = MessageState.Held, sentAt = clock()),
            )
            pump()
            return true
        }

        fun stop() {
            running?.cancel()
        }

        fun regenerate(position: Int) {
            hs.launch {
                stopAndWait()
                _limit.value = null
                val id = _chatId.value.takeIf { it != 0L } ?: return@launch
                // the user's message first: with none before this reply there is nothing to ask again
                val user =
                    messages.value.lastOrNull { it.position < position && it.kind == MessageKind.User }
                        ?: return@launch
                val before = messages.value
                chats.truncateFrom(id, position)
                invalidate()
                modelToRestate(before, position, modelKey.value)?.let {
                    chats.append(id, switchNotice(it, displayName(it), clock()))
                }
                chats.update(user.id, MessageChange(state = MessageState.Held))
                pump()
            }
        }

        fun edit(position: Int, text: String) {
            if (text.isBlank()) return
            hs.launch {
                stopAndWait()
                _limit.value = null
                val id = _chatId.value.takeIf { it != 0L } ?: return@launch
                val before = messages.value
                val old = before.firstOrNull { it.position == position } ?: return@launch
                chats.truncateFrom(id, position)
                invalidate()
                modelToRestate(before, position, modelKey.value)?.let {
                    chats.append(id, switchNotice(it, displayName(it), clock()))
                }
                chats.append(
                    id,
                    NewMessage(
                        MessageKind.User,
                        text.trim(),
                        old.images.map {
                            it.name
                        },
                        state = MessageState.Held,
                        sentAt = clock(),
                    ),
                )
                pump()
            }
        }

        fun switchModel(key: HfModel) {
            hs.launch {
                stopAndWait()
                // an empty chat has no model to switch from: choosing one just sets it, with no divider
                val switching = isSwitch(messages.value)
                val id = ensureChat() ?: return@launch
                // the in-memory model changes first, so a message sent right after is answered by the new one
                invalidate()
                _limit.value = null
                modelKey.value = key
                syncThink()
                if (switching) chats.append(id, switchNotice(key, displayName(key), clock()))
                chats.setModel(id, key.toString())
                settings.setLastModel(key.toString())
                pump()
            }
        }

        fun setThink(level: ThinkLevel) {
            if (_think.value != null) _think.value = level
        }

        /** Deletes the chat; a reply still streaming into it is stopped first. */
        fun delete() {
            scope.launch {
                stopAndWait()
                _chatId.value.takeIf { it != 0L }?.let { chats.delete(it) }
            }
        }

        /** Instructions changed in Settings: the next turn rebuilds the conversation with the new system prompt. */
        fun instructionsChanged() = invalidate()

        // ---- the engine ----------------------------------------------------------------------------------

        private fun invalidate() {
            conversation = null
            conversationKey = null
        }

        private suspend fun stopAndWait() {
            running?.cancel()
            running?.join()
        }

        /** Answers every held user message in order, once, as soon as its model is on the phone. */
        private fun pump() {
            hs.launch {
                turn.withLock {
                    while (true) {
                        val id = _chatId.value.takeIf { it != 0L } ?: return@withLock
                        val key = modelKey.value ?: return@withLock
                        val lib = library.value ?: return@withLock
                        if (lib.none { it.key == key }) return@withLock
                        val held =
                            chats.messages(id).first().firstOrNull {
                                it.kind == MessageKind.User &&
                                    it.state == MessageState.Held
                            }
                                ?: return@withLock
                        if (!answer(id, key, held)) return@withLock
                    }
                }
            }
        }

        /** Answers [user]; false when the turn could not start (the message stays held). */
        private suspend fun answer(id: Long, key: HfModel, user: StoredMessage): Boolean {
            _busy.value = true
            try {
                val loaded =
                    try {
                        host.use(key)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        _limit.value = Limits.forLoadFailure(displayName(key), e)
                            ?: Limit("${displayName(key)} didn't start. Try again.", LimitAction.Retry, "Retry")
                        return false
                    }
                syncThink(loaded.info)
                val rows = chats.messages(id).first()
                var convo = conversation.takeIf { conversationKey == key }
                if (convo == null) {
                    val s = settings.settings.first()
                    // what the model has seen: answered rows before this question, and replies; other held questions
                    // wait their turn
                    val seen =
                        rows.filter {
                            it.id != user.id && it.state != MessageState.Held &&
                                (it.position < user.position || it.kind == MessageKind.Assistant)
                        }
                    convo =
                        loaded.conversation(
                            Instructions.system(s.instructionsAbout, s.instructionsStyle),
                            History.forModel(seen),
                        )
                    conversation = convo
                    conversationKey = key
                }
                val replyId =
                    chats.append(
                        id,
                        NewMessage(
                            MessageKind.Assistant,
                            "",
                            modelKey = key.toString(),
                            state = MessageState.Streaming,
                            sentAt = clock(),
                        ),
                    )
                val level = _think.value
                val images = user.images.map { Content.Image(it.readBytes(), "image/jpeg") }.toTypedArray()
                val job = hs.launch { stream(convo, replyId, user, images, level) }
                running = job
                job.join()
                running = null
                return true
            } finally {
                _busy.value = false
            }
        }

        private suspend fun stream(
            convo: Conversation,
            replyId: Long,
            user: StoredMessage,
            images: Array<Content.Image>,
            level: ThinkLevel?,
        ) {
            val draft = ReplyDraft(clock)
            val ticker = hs.launch { showAndSave(draft, replyId) }
            try {
                streamer(convo, user.text, images, level).collect { trace("ChatEngine.onDelta") { draft.accept(it) } }
                ticker.cancel()
                finish(replyId, user, draft.ended(MessageState.Done))
            } catch (e: CancellationException) {
                ticker.cancel()
                kotlinx.coroutines.withContext(NonCancellable) {
                    finish(replyId, user, draft.ended(MessageState.Stopped))
                }
                invalidate()
                throw e
            } catch (e: Exception) {
                ticker.cancel()
                val limit = Limits.forLoadFailure(currentName(), e)
                finish(replyId, user, draft.failed(limit?.line ?: "That didn't work. Try again."))
                _limit.value = limit
                invalidate()
            }
        }

        /** Every frame, shows what arrived; every [writeMillis], saves it too. Runs until the reply ends. */
        private suspend fun showAndSave(draft: ReplyDraft, replyId: Long) {
            var lastWrite = clock()
            while (currentCoroutineContext().isActive) {
                delay(frameMillis)
                if (!draft.takeIfDirty()) continue
                _streaming.value = draft.shown(replyId)
                if (clock() - lastWrite >= writeMillis) {
                    lastWrite = clock()
                    chats.update(replyId, draft.progress())
                }
            }
        }

        /** Stores how the reply ended, marks the question answered and stops showing the stream. */
        private suspend fun finish(replyId: Long, user: StoredMessage, change: MessageChange) {
            chats.update(replyId, change)
            chats.update(user.id, MessageChange(state = MessageState.Done))
            _streaming.value = null
        }

        // ---- helpers -------------------------------------------------------------------------------------

        private suspend fun initialModelKey(): HfModel? {
            val id = _chatId.value
            val stored = if (id != 0L) chats.modelKey(id) else settings.settings.first().modelForNewChat()
            return stored?.let { runCatching { HfModel.parse(it) }.getOrNull() }
        }

        /** Creates the chat on the first message; null when no model is known yet. */
        private suspend fun ensureChat(): Long? {
            _chatId.value.takeIf { it != 0L }?.let { return it }
            val key = modelKey.value ?: initialModelKey()?.also { modelKey.value = it } ?: return null
            return chats.createChat(key.toString()).also { _chatId.value = it }
        }

        private fun syncThink(info: ModelInfo? = null) {
            val key = modelKey.value ?: return
            val catalog = Catalog.byKey(key)
            val can = if (info != null) Thinking.canThink(info, catalog) else catalog?.thinks == true
            _think.update { current -> if (can) current ?: ThinkLevel.Off else null }
        }

        private fun currentName(): String = modelKey.value?.let(::displayName) ?: "This model"

        /** Starts the model's download again after a failure; what had arrived is kept. */
        fun retryDownload() {
            modelKey.value?.let(models::get)
        }

        private fun visionModelName(): String? =
            library.value?.firstOrNull { Catalog.byKey(it.key)?.sees == true }?.name

        private fun displayName(key: HfModel): String =
            library.value?.firstOrNull { it.key == key }?.name
                ?: Catalog.byRepo(key.repo)?.realName
                ?: key.repo.substringAfter('/').removeSuffix("-GGUF")

        private fun missingOrDownloading(name: String, status: DownloadStatus): ChatModelState =
            when (status) {
                is DownloadStatus.Downloading, is DownloadStatus.Partial -> ChatModelState.Downloading(status)
                is DownloadStatus.Failed -> ChatModelState.DownloadFailed(name, status.failure)
                else -> ChatModelState.Missing(name)
            }

        private fun readyOrWaking(key: HfModel, name: String, hostState: HostState): ChatModelState =
            when {
                hostState is HostState.Waking && hostState.key == key -> {
                    ChatModelState.Waking
                }

                hostState is HostState.Ready && hostState.key == key -> {
                    ChatModelState.Ready(
                        name,
                        hostState.model.info.supportsVision,
                        Thinking.canThink(hostState.model.info, Catalog.byKey(key)),
                    )
                }

                else -> {
                    ChatModelState.Ready(name, Catalog.byKey(key)?.sees ?: false, Catalog.byKey(key)?.thinks ?: false)
                }
            }
    }

    private companion object {
        /** Builder's call, settled here: at most four images per message. */
        const val MAX_IMAGES = 4
    }
}
