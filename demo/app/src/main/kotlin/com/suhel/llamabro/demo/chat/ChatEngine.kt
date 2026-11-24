package com.suhel.llamabro.demo.chat

import androidx.tracing.trace
import com.suhel.llamabro.Model
import com.suhel.llamabro.ModelInfo
import com.suhel.llamabro.chat.ChatEvent
import com.suhel.llamabro.chat.Content
import com.suhel.llamabro.chat.Message
import com.suhel.llamabro.chat.RequestOptions
import com.suhel.llamabro.chat.Sampling
import com.suhel.llamabro.conversation
import com.suhel.llamabro.conversation.Conversation
import com.suhel.llamabro.demo.data.ChatRepository
import com.suhel.llamabro.demo.data.MessageChange
import com.suhel.llamabro.demo.data.MessageKind
import com.suhel.llamabro.demo.data.MessageState
import com.suhel.llamabro.demo.data.NewMessage
import com.suhel.llamabro.demo.data.NewVoice
import com.suhel.llamabro.demo.data.SettingsRepository
import com.suhel.llamabro.demo.data.StoredMessage
import com.suhel.llamabro.demo.data.modelForNewChat
import com.suhel.llamabro.demo.data.settings.CardSampling
import com.suhel.llamabro.demo.data.settings.Knobs
import com.suhel.llamabro.demo.models.Catalog
import com.suhel.llamabro.demo.models.LibraryModel
import com.suhel.llamabro.demo.models.ModelRepository
import com.suhel.llamabro.demo.models.Names
import com.suhel.llamabro.hub.HfModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.ContinuationInterceptor

/**
 * How a turn asks: its Think [level] (null for a model that can't think), its [sampling] with the reply's length limit
 * (D60), and whether it [continues] the cut reply that ends the conversation instead of answering a new message.
 */
data class TurnOptions(
    val level: Think? = null,
    val sampling: Sampling = Sampling.Default,
    val continues: Boolean = false,
)

/**
 * How a turn reaches the model: the conversation, the message's text, its pictures and voice message, and the turn's
 * options.
 */
typealias Streamer = (Conversation, String, Array<Content>, TurnOptions) -> Flow<ChatEvent>

/**
 * What a turn streams: the SDK conversation, with the Think level and the sampling applied. A continue resends the
 * history, whose last message is the cut reply: llama-server then continues that message, its thinking or its answer,
 * instead of starting a new one (`prefill_assistant`, on by default). Journeys wrap it to hold a reply.
 */
internal fun sdkStream(
    convo: Conversation,
    text: String,
    media: Array<Content>,
    options: TurnOptions,
): Flow<ChatEvent> {
    val configure: RequestOptions.Builder.() -> Unit = {
        options.level?.let { level -> reasoning { Thinking.configure(level, this) } }
        sampling = options.sampling
    }
    return if (options.continues) convo.resume(configure) else convo.send(text, *media, options = configure)
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

/** The chat voice mode talks in (D100): its id, its model and the model's name. */
data class VoiceChat(val chatId: Long, val key: HfModel, val name: String)

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
    private val streamer: Streamer = ::sdkStream,
    /**
     * Awaited before every turn: the app's recovery of replies a dead process left streaming, so it can never mark a
     * reply this process has just started as stopped.
     */
    private val beforeTurn: suspend () -> Unit = {},
    /**
     * A model's card values (D60), at once: the catalog's; the app adds the hub's for other models once looked up
     * ([SamplingStore.card], which never holds a turn).
     */
    private val card: (HfModel) -> CardSampling? = { Catalog.byRepo(it.repo)?.sampling },
) {
    private val leases = ModelLeases(host, settings)
    private val activeTurns get() = leases.activeTurns

    /**
     * A reply is being loaded for or written, in any chat: the model can't be unloaded now, and a reply that goes on
     * in the background keeps the app alive while it does (D59).
     */
    val replying: StateFlow<Boolean> =
        activeTurns.map { it > 0 }.stateIn(scope, SharingStarted.Eagerly, false)

    /**
     * A hold on [key]'s model (shown as [name]) for an audio feature, loaded with [slots] (two while voice mode
     * captions what the person says, D99; one otherwise, through [ChatHandle.holdModel]), or null when it didn't load:
     * [limit] then says why. The hold counts as a turn until [ModelHold.release]: the model isn't freed under it, and
     * the app stays alive while it runs (D59).
     */
    suspend fun holdModel(key: HfModel, name: String, slots: Int, limit: MutableStateFlow<Limit?>): ModelHold? =
        leases.hold(key, name, slots, limit)

    /**
     * Releases the loaded model unless a turn is loading it or streaming from it (the app went to the background); the
     * next send loads it again. True when it was released.
     */
    suspend fun releaseModelIfIdle(): Boolean = host.release { activeTurns.value > 0 }

    /** The system prompt from Settings › Instructions as it is now, or null when there are none. */
    private suspend fun systemPrompt(): String? =
        settings.settings.first().let { Instructions.system(it.instructionsAbout, it.instructionsStyle) }

    /** A turn at [level]: sampling from the chat, the model and the card, and the reply's length limit (D60). */
    private suspend fun turnOptions(id: Long, key: HfModel, level: Think?, continues: Boolean): TurnOptions {
        val layers =
            SamplingLayers(
                card = card(key),
                model = settings.modelSampling.first()[key.toString()] ?: Knobs.None,
                chat = chats.sampling(id).first(),
            )
        val thinks = Thinking.thinks(level)
        val length = settings.settings.first().replyLength
        return TurnOptions(level, layers.sampling(thinks, Thinking.replyTokens(level, length)), continues)
    }

    /** The chats open now: each registers when opened and leaves when closed. */
    private val open: MutableSet<ChatHandle> = ConcurrentHashMap.newKeySet()

    /**
     * [chatId] 0 is a new chat: it is created with the first message, on [startModel] when one is given (a model card's
     * "Chat with"), else on the model for new chats. [startModel] is only held in memory until then, so a new chat
     * left without a message leaves no chat behind; it is ignored for a chat that exists.
     */
    fun open(chatId: Long, startModel: HfModel? = null): ChatHandle = ChatHandle(chatId, startModel).also { open += it }

    /**
     * Stops every reply under way, keeping what arrived, as Stop would: the reply notification's Stop, and Android's
     * limit on how long a reply may run in the background (D59).
     */
    fun stopAllReplies() = open.forEach { it.stop() }

    /**
     * Deletes [chatId] from outside its screen (the drawer), on the app's scope so leaving the screen doesn't stop it:
     * a reply streaming into it is stopped first, and no other held message of it is answered after, since a reply
     * reads the chat's pictures and recordings from their files until it ends (D87).
     */
    fun delete(chatId: Long): Job =
        scope.launch {
            open.filter { it.chatId.value == chatId }.forEach { it.stopAndWait(forDelete = true) }
            chats.delete(chatId)
        }

    // the chat's facade, as Conversation is the SDK's: each public function is one thing the chat screen does
    @Suppress("TooManyFunctions")
    @OptIn(ExperimentalCoroutinesApi::class)
    inner class ChatHandle internal constructor(initialChatId: Long, private val startModel: HfModel? = null) {
        /**
         * One thread at a time for everything this handle runs: [running], [conversation] and [conversationKey] are
         * plain fields, read and written only on it, so a turn, a stop and an edit never see each other half done.
         */
        private val confined: CoroutineDispatcher =
            (scope.coroutineContext[ContinuationInterceptor] as? CoroutineDispatcher ?: Dispatchers.Default)
                .limitedParallelism(1)

        /**
         * Everything this handle runs lives in its own scope, under the engine's: [close] ends it, so a chat that was
         * left stops observing the database, stops pumping held messages and stops its reply (which is saved as
         * stopped).
         */
        private val hs = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]) + confined)

        /** Ends this handle. Call once, when the chat screen is gone. */
        fun close() {
            open -= this
            hs.cancel()
        }

        // engine's own source: a new chat gets its id with its first message
        private val _chatId = MutableStateFlow(initialChatId)
        val chatId: StateFlow<Long> = _chatId.asStateFlow()

        val messages: StateFlow<List<StoredMessage>> =
            _chatId
                .flatMapLatest { if (it == 0L) emptyFlow() else chats.messages(it) }
                .stateIn(hs, SharingStarted.Eagerly, emptyList())

        // engine's own source: the turn writes the reply as it streams
        private val _streaming = MutableStateFlow<StreamingReply?>(null)
        val streaming: StateFlow<StreamingReply?> = _streaming.asStateFlow()

        // engine's own source: the turn marks itself under way
        private val _busy = MutableStateFlow(false)
        val busy: StateFlow<Boolean> = _busy.asStateFlow()

        // engine's own source: the turn's refusals and failures
        private val _limit = MutableStateFlow<Limit?>(null)
        val limit: StateFlow<Limit?> = _limit.asStateFlow()

        // user input and engine: the Think level chosen, null for a model that can't think
        private val _think = MutableStateFlow<Think?>(null)

        /** Null when the model can't think. */
        val think: StateFlow<Think?> = _think.asStateFlow()

        // the engine's own source: written by the lookup, the first send and a switch
        private val choice = MutableStateFlow<ModelChoice>(ModelChoice.Unknown)
        private val library: StateFlow<List<LibraryModel>?> =
            models.library.stateIn(hs, SharingStarted.Eagerly, null)

        /** The model this chat uses (null until it is known), for the switcher's check mark. */
        val current: StateFlow<HfModel?> =
            choice
                .map { it.key }
                .distinctUntilChanged()
                .stateIn(hs, SharingStarted.Eagerly, null)

        /**
         * The model's name for the header, whatever state it is in; empty until the model is looked up, then
         * [NO_MODEL_TITLE] for a new chat with no model chosen, which left the header blank.
         */
        val name: StateFlow<String> =
            choice
                .map { chosen ->
                    when (chosen) {
                        ModelChoice.Unknown -> ""
                        ModelChoice.None -> NO_MODEL_TITLE
                        is ModelChoice.Chosen -> Names.of(chosen.key)
                    }
                }.stateIn(hs, SharingStarted.Eagerly, "")

        val model: StateFlow<ChatModelState> =
            combine(
                choice,
                library,
                host.state,
                settings.hearingModels,
                settings.talkingModels,
            ) { chosen, lib, hostState, heard, talking ->
                ModelInputs(chosen, lib, hostState, heard, talking.keys)
            }.flatMapLatest { inputs ->
                val key = inputs.choice.key
                val lib = inputs.library
                val hostState = inputs.host
                when {
                    // looked up and none chosen: nothing to load, so no wake line
                    key == null -> {
                        if (inputs.choice == ModelChoice.None) flowOf(ChatModelState.NoModel) else emptyFlow()
                    }

                    lib == null -> {
                        emptyFlow()
                    }

                    else -> {
                        val name = Names.of(key)
                        if (lib.none { it.key == key }) {
                            models.state(key).map { missingOrDownloading(name, it) }
                        } else {
                            flowOf(readyOrWaking(key, name, hostState, inputs.heard, inputs.talking))
                        }
                    }
                }
            }.stateIn(hs, SharingStarted.Eagerly, ChatModelState.Waking())

        /** Held by the pump for its whole loop, by a continue, and by a rewrite ([rewriting]). */
        private val turn = Mutex()

        /** The turn under way, load and reply both; volatile only because [stop] cancels it from the UI's thread. */
        @Volatile private var running: Job? = null
        private var conversation: Conversation? = null
        private var conversationKey: HfModel? = null

        /**
         * The loaded model [conversation] was made on. A conversation lives inside one load: after a release (the app
         * went to the background, or the person unloaded it) the same key loads a new model and the old conversation
         * is closed with the old one, so it is rebuilt from the stored rows.
         */
        private var conversationModel: Model? = null

        /**
         * The system prompt [conversation] was made with. Settings › Instructions opens over the chat, which stays
         * open: a turn that finds the setting changed since rebuilds the conversation, so the new instructions apply at
         * once.
         */
        private var conversationSystem: String? = null

        init {
            hs.launch {
                val found = initialModelKey()
                choice.update { it.lookedUp(found) }
                syncThink()
            }
            // a model that arrives (a download finishes) answers what was held
            hs.launch { library.collect { if (it != null) pump() } }
            // the hub's values for a model the catalog lacks are looked up once the chat's model is on the phone, so
            // its first reply has them as the later ones do; never before, since the lookup would then read the file's
            // header over the network (D64); it runs in the background and never holds a turn
            hs.launch {
                combine(current.filterNotNull(), library.filterNotNull()) { key, lib ->
                    key.takeIf { lib.any { model -> model.key == key } }
                }.filterNotNull()
                    .distinctUntilChanged()
                    .collect { card(it) }
            }
        }

        // ---- the user's actions -------------------------------------------------------------------------

        /**
         * Whether the message was taken: false while a reply is coming, for an empty message, when the chat has no
         * model at all, and for pictures or a [voice] message sent to a model that can't see or hear them (the limit
         * line says so). The caller keeps the draft when this is false. A taken message is stored as held and answered
         * as soon as its model is on the phone. Runs on [confined], as a switch does, so the two never both create
         * the chat.
         */
        suspend fun send(text: String, images: List<String> = emptyList(), voice: NewVoice? = null): Boolean =
            withContext(confined) { take(text, images, voice) }

        private suspend fun take(text: String, images: List<String>, voice: NewVoice?): Boolean {
            // a chat deleted from the drawer takes nothing more: its rows would point at a chat that is gone
            if (_busy.value || deleting) return false
            if (text.isBlank() && images.isEmpty() && voice == null) return false
            val key =
                choice.value.key ?: initialModelKey()?.also { choice.value = ModelChoice.Chosen(it) } ?: return false
            val names = images.take(MAX_IMAGES)
            val ready = model.value as? ChatModelState.Ready
            // the model's own answer once it is loaded; until then what the catalog says about it
            val sees = ready?.sees ?: Catalog.byKey(key)?.sees ?: false
            val hears = ready?.hears == true
            refusal(sees, hears, names.isNotEmpty(), voice != null, currentName(), visionModelName())?.let {
                _limit.value = it
                return false
            }
            val id = ensureChat() ?: return false
            _limit.value = null
            chats.commitDraftImages(names)
            try {
                chats.append(
                    id,
                    NewMessage(
                        MessageKind.User,
                        text.trim(),
                        names,
                        state = MessageState.Held,
                        sentAt = clock(),
                        voice = voice,
                    ),
                )
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception,
            ) {
                // not stored: the draft keeps its pictures (even when this call was cancelled)
                withContext(NonCancellable) { chats.returnDraftImages(names) }
                throw e
            }
            pump()
            return true
        }

        /** Stops the turn, whether the model is still waking (the message stays held) or the reply is streaming. */
        fun stop() {
            running?.cancel()
        }

        /** Asks again from [position]. */
        fun regenerate(position: Int) {
            rewriteFrom(
                position,
                // the user's message first: with none before this reply there is nothing to ask again
                keep = { rows -> emptySet<String>().takeIf { rows.any { row -> row.isUserBefore(position) } } },
            ) { _, before ->
                val user = before.last { it.isUserBefore(position) }
                chats.update(user.id, MessageChange(state = MessageState.Held))
            }
        }

        fun edit(position: Int, text: String) {
            if (text.isBlank()) return
            rewriteFrom(
                position,
                keep = { rows ->
                    rows.firstOrNull { it.position == position }?.images?.mapTo(mutableSetOf()) { it.name }
                },
            ) { id, before ->
                val old = before.first { it.position == position }
                chats.append(
                    id,
                    NewMessage(
                        MessageKind.User,
                        text.trim(),
                        old.images.map { it.name },
                        state = MessageState.Held,
                        sentAt = clock(),
                    ),
                )
            }
        }

        fun switchModel(key: HfModel) {
            rewriting {
                if (deleting) return@rewriting
                // an empty chat has no model to switch from: choosing one just sets it, with no divider
                val switching = isSwitch(messages.value)
                // the in-memory model changes first, so a message sent right after is answered by the new one, and a
                // new chat with no model yet is created on the one chosen
                invalidate()
                _limit.value = null
                choice.value = ModelChoice.Chosen(key)
                val id = ensureChat() ?: return@rewriting
                syncThink()
                if (switching) chats.append(id, switchNotice(key, Names.of(key), clock()))
                chats.setModel(id, key.toString())
                settings.setLastModel(key.toString())
            }
        }

        fun setThink(level: Think) {
            if (_think.value != null) _think.value = level
        }

        // ---- the engine ----------------------------------------------------------------------------------

        /**
         * Cuts the chat from [position] and asks again (an edit, a regenerate): stops the turn, then, with [keep]
         * saying which pictures of the cut rows stay (null: nothing to rewrite), cuts, says "Switched to" again
         * where the cut removed it ([modelToRestate]), runs [then] with the chat's id and the rows before the cut,
         * and answers what is held.
         */
        private fun rewriteFrom(
            position: Int,
            keep: (List<StoredMessage>) -> Set<String>?,
            then: suspend (id: Long, before: List<StoredMessage>) -> Unit,
        ) {
            rewriting {
                beforeTurn() // startup recovery must not run between the cut and the append
                if (deleting) return@rewriting
                _limit.value = null
                val id = _chatId.value.takeIf { it != 0L } ?: return@rewriting
                val before = messages.value
                val kept = keep(before) ?: return@rewriting
                chats.truncateFrom(id, position, keepImages = kept)
                invalidate()
                modelToRestate(before, position, choice.value.key)?.let {
                    chats.append(id, switchNotice(it, Names.of(it), clock()))
                }
                then(id, before)
            }
        }

        /**
         * Rewrites (an edit, a regenerate, a switch) are under way; [pump] answers nothing while one is. Read and
         * written on [confined] only.
         */
        private var rewrites = 0

        /**
         * Runs [block], a change to the chat's rows or model, with no turn under way, then answers what is held. The
         * turn is stopped, and [rewrites] keeps [pump] from starting the next held message's turn meanwhile: a pump
         * whose stopped turn had made its reply row would otherwise go on to the next one while [block] cuts it
         * away. [block] then runs under [turn], so no pump's turn can run beside it.
         */
        private fun rewriting(block: suspend () -> Unit) {
            hs.launch {
                rewrites++
                try {
                    stopAndWait()
                    turn.withLock { block() }
                } finally {
                    rewrites--
                }
                // also when [block] changed nothing: a pump that gave way to it may have left messages held
                pump()
            }
        }

        private fun StoredMessage.isUserBefore(position: Int) = this.position < position && kind == MessageKind.User

        private fun invalidate() {
            conversation = null
            conversationKey = null
            conversationModel = null
            conversationSystem = null
        }

        /**
         * Stops the turn and waits for it to end; on this handle's thread, so the engine may call it too. [forDelete]
         * also ends the answering of held messages for good ([deleting]), before the turn is cancelled.
         */
        internal suspend fun stopAndWait(forDelete: Boolean = false) =
            withContext(confined) {
                if (forDelete) deleting = true
                running?.cancel()
                running?.join()
            }

        /**
         * The chat is being deleted: [pump] answers nothing more, and the person's actions are refused. Written on
         * [confined] only.
         */
        private var deleting = false

        /** Answers every held user message in order, once, as soon as its model is on the phone. */
        private fun pump() {
            hs.launch {
                turn.withLock {
                    while (!deleting && rewrites == 0) {
                        val id = _chatId.value.takeIf { it != 0L } ?: return@withLock
                        val key = choice.value.key ?: return@withLock
                        val lib = library.value ?: return@withLock
                        if (lib.none { it.key == key }) return@withLock
                        val held =
                            chats.messages(id).first().firstOrNull {
                                it.kind == MessageKind.User &&
                                    it.state == MessageState.Held
                            }
                                ?: return@withLock
                        // the turn, load and reply both, is the one job [stop] cancels: it is [running]
                        // before the load starts, so a Stop while the model wakes ends the turn (the message
                        // stays held, no reply row is made), and an edit, a regenerate or a switch waits for
                        // the load as it waits for a reply
                        // the chat may have started going, or a rewrite may have begun, while its rows were read;
                        // nothing suspends from here until the turn is [running], where a rewrite's stop finds it
                        if (deleting || rewrites > 0) return@withLock
                        var replied = false
                        runAsCurrent { answer(id, key, held) { replied = true } }
                        if (!replied) return@withLock
                    }
                }
            }
        }

        /** Answers [user]; [onReply] runs once the reply row exists (the load worked and was not stopped). */
        private suspend fun answer(id: Long, key: HfModel, user: StoredMessage, onReply: () -> Unit) =
            leasedTurn(key) { loaded ->
                val hears = loaded.info.supportsAudio
                rememberSenses(settings, key, loaded.info)
                val rows = chats.messages(id).first()
                val convo = conversationFor(loaded, key, rows, user, hears)
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
                onReply()
                // a model that can't hear gets a note for each voice message, and the line says so (D76)
                if (!hears && rows.any { it.voice != null && it.position <= user.position }) {
                    _limit.value = Limits.cantHear(currentName())
                }
                val parts = (History.userMessage(user, hears) as Message.User).content
                val text = parts.filterIsInstance<Content.Text>().joinToString("") { it.text }
                val media = parts.filterNot { it is Content.Text }.toTypedArray()
                val report = loaded.loadReport
                val draft = ReplyDraft(clock, report.device.label, contextSize = report.contextSize)
                stream(
                    convo,
                    replyId,
                    user,
                    text,
                    media,
                    turnOptions(id, key, _think.value, continues = false),
                    draft,
                )
            }

        /**
         * One turn's frame, for an answer and a continue: counted and busy from before the load to its end, with
         * [block] run on the loaded model under a lease released when it ends; nothing runs when the load fails.
         */
        private suspend fun leasedTurn(key: HfModel, block: suspend (Model) -> Unit) {
            activeTurns.update { it + 1 }
            _busy.value = true
            var lease: ModelHost.Lease? = null
            try {
                beforeTurn()
                val held = leases.load(key, Names.of(key), _limit) ?: return
                lease = held
                syncThink(held.model.info)
                block(held.model)
            } finally {
                lease?.release()
                _busy.value = false
                activeTurns.update { it - 1 }
            }
        }

        /**
         * Runs [block] as the turn [stop] cancels ([running]) and waits for it; a newer turn that took its place
         * meanwhile stays.
         */
        private suspend fun runAsCurrent(block: suspend () -> Unit) {
            val job = hs.launch { block() }
            running = job
            job.join()
            if (running === job) running = null
        }

        /**
         * The conversation for [user]'s turn: the one kept from the last turn while the model, its load and the system
         * prompt are the same, else a new one from [rows].
         */
        private suspend fun conversationFor(
            loaded: Model,
            key: HfModel,
            rows: List<StoredMessage>,
            user: StoredMessage,
            hears: Boolean,
        ): Conversation {
            val system = systemPrompt()
            conversation
                ?.takeIf { conversationKey == key && conversationModel === loaded && conversationSystem == system }
                ?.let { return it }
            // what the model has seen: answered rows before this question, and replies; other held questions wait
            // their turn
            val seen =
                rows.filter {
                    it.id != user.id && it.state != MessageState.Held &&
                        (it.position < user.position || it.kind == MessageKind.Assistant)
                }
            return loaded.conversation(system, History.forModel(seen, hears = hears)).also {
                conversation = it
                conversationKey = key
                conversationModel = loaded
                conversationSystem = system
            }
        }

        /**
         * Continues the cut reply at [position] (D60): the chat's last row, cut at its length limit, written by the
         * chat's model. The model reads the chat with that reply as its last message and goes on writing it, its
         * thinking or its answer; the new words join the same row, which may be cut again and continued again.
         */
        fun continueReply(position: Int) {
            hs.launch {
                turn.withLock {
                    if (deleting) return@withLock
                    val id = _chatId.value.takeIf { it != 0L } ?: return@withLock
                    val key = choice.value.key ?: return@withLock
                    val rows = chats.messages(id).first()
                    val reply = rows.lastOrNull()?.takeIf { it.position == position && canContinue(it, key) }
                    val user = rows.lastOrNull { it.kind == MessageKind.User && it.position < position }
                    if (reply == null || user == null) return@withLock
                    _limit.value = null
                    runAsCurrent { continueTurn(id, key, rows, reply, user) }
                }
            }
        }

        private suspend fun continueTurn(
            id: Long,
            key: HfModel,
            rows: List<StoredMessage>,
            reply: StoredMessage,
            user: StoredMessage,
        ) = leasedTurn(key) { loaded ->
            // the chat before the reply, then the reply as far as it got, its thinking with it
            val history =
                History.forModel(rows, upTo = reply.position, hears = loaded.info.supportsAudio) +
                    Message.Assistant(content = reply.text, reasoning = reply.reasoning)
            // a one-off: kept, this conversation would hold the reply twice once the next message is sent
            invalidate()
            val convo = loaded.conversation(systemPrompt(), history)
            chats.update(reply.id, MessageChange(state = MessageState.Streaming))
            val draft =
                ReplyDraft(
                    clock,
                    loaded.loadReport.device.label,
                    ReplyDraft.Continued(reply.text, reply.reasoning, reply.reasoningMillis),
                    loaded.loadReport.contextSize,
                )
            stream(
                convo,
                reply.id,
                user,
                "",
                emptyArray(),
                turnOptions(id, key, _think.value, continues = true),
                draft,
            )
            invalidate()
        }

        private suspend fun stream(
            convo: Conversation,
            replyId: Long,
            user: StoredMessage,
            text: String,
            media: Array<Content>,
            options: TurnOptions,
            draft: ReplyDraft,
        ) {
            val ticker = hs.launch { showAndSave(draft, replyId) }
            try {
                streamer(convo, text, media, options).collect { trace("ChatEngine.onDelta") { draft.accept(it) } }
                ticker.cancel()
                if (draft.filledContext) {
                    // no room to continue: it ends as a chat too long for the model, with its way out
                    val limit = Limits.contextExceeded(currentName())
                    finish(replyId, user, draft.failed(limit.line))
                    _limit.value = limit
                    invalidate()
                } else {
                    finish(replyId, user, draft.ended(MessageState.Done))
                }
            } catch (e: CancellationException) {
                ticker.cancel()
                withContext(NonCancellable) {
                    finish(replyId, user, draft.ended(MessageState.Stopped))
                }
                invalidate()
                throw e
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception,
            ) {
                ticker.cancel()
                val limit = Limits.forLoadFailure(currentName(), e)
                finish(replyId, user, draft.failed(limit?.line ?: "That didn't work."))
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
            if (id == 0L && startModel != null) return startModel
            val stored = if (id != 0L) chats.modelKey(id) else settings.settings.first().modelForNewChat()
            return stored?.let { runCatching { HfModel.parse(it) }.getOrNull() }
        }

        /**
         * A hold on the chat's model for dictation (D140) or reading a reply aloud (D141), loaded as for a turn (one
         * slot), so a send after it finds the model as it was; null with no model chosen or when it didn't load, [limit]
         * then saying why.
         */
        suspend fun holdModel(): ModelHold? {
            val key = choice.value.key ?: return null
            return holdModel(key, Names.of(key), 1, _limit)
        }

        /**
         * Voice mode's chat (D100): this one, created now if it is new, with its model and the model's name; null when
         * no model is known yet.
         */
        suspend fun voiceChat(): VoiceChat? =
            withContext(confined) {
                val id = ensureChat() ?: return@withContext null
                val key = choice.value.key ?: return@withContext null
                VoiceChat(id, key, Names.of(key))
            }

        /**
         * Held while the chat is created: a send and a switch both on [confined] still meet at the creation's
         * suspension, and the second must find the chat the first made rather than make another.
         */
        private val creating = Mutex()

        /** Creates the chat on the first message; null when no model is known yet. */
        private suspend fun ensureChat(): Long? =
            creating.withLock {
                _chatId.value.takeIf { it != 0L }?.let { return@withLock it }
                val key =
                    choice.value.key ?: initialModelKey()?.also { choice.value = ModelChoice.Chosen(it) }
                        ?: return@withLock null
                chats.createChat(key.toString()).also { _chatId.value = it }
            }

        private fun syncThink(info: ModelInfo? = null) {
            val key = choice.value.key ?: return
            val catalog = Catalog.byKey(key)
            val can = if (info != null) Thinking.canThink(info, catalog) else catalog?.thinks == true
            _think.update { current -> if (can) current ?: Think.Off else null }
        }

        private fun currentName(): String = choice.value.key?.let(Names::of) ?: "This model"

        /** Starts the model's download again after a failure; what had arrived is kept. */
        fun retryDownload() {
            choice.value.key?.let(models::get)
        }

        private fun visionModelName(): String? =
            library.value?.firstOrNull { Catalog.byKey(it.key)?.sees == true }?.name
    }

    companion object {
        /** Builder's call, settled here: at most four images per message. */
        const val MAX_IMAGES = 4
    }
}
