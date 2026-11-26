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
import com.suhel.llamabro.demo.data.ModelSenses
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
import com.suhel.llamabro.rag.SearchArgs
import com.suhel.llamabro.typed.TypedTool
import com.suhel.llamabro.typed.tool
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
import kotlinx.coroutines.flow.collectLatest
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
    /**
     * What a model on the phone can do, read from its header before it loads (D155); null when it can't be told. The
     * app reads the hub's; tests answer for it.
     */
    private val readSenses: suspend (HfModel) -> ModelSenses? = { null },
    /**
     * The model server mode serves, if any (docs/design/server.md §3): a turn on it shares the load whatever its
     * config, and the model is not freed while it is served.
     */
    private val served: () -> HfModel? = { null },
    /** The person's library, which a chat answers from when its "Use my library" is on; null in apps without one. */
    private val chatLibrary: ChatLibrary? = null,
    /** Whether a loaded model calls tools: the search tool then, else the passages in the turn. Tests answer for it. */
    private val callsTools: (ModelInfo) -> Boolean = { callsTools(it.templateCaps) },
) {
    private val leases = ModelLeases(host, settings, served)

    /** Stores each model's header senses once it is on the phone, for every chat (D155). */
    private val sensesReader = SensesReader(settings, readSenses)
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
     * Releases the loaded model unless a turn is loading it or streaming from it, or it is served (the app went to the
     * background); the next send loads it again. True when it was released.
     */
    suspend fun releaseModelIfIdle(): Boolean = host.release { activeTurns.value > 0 || served() != null }

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

        /** What the chat's model can do as the chat shows it ([sensesOf]); nothing while no model is chosen. */
        private val senses: StateFlow<Senses> =
            combine(
                choice,
                host.state,
                settings.modelSenses,
                settings.talkingModels,
            ) { chosen, hostState, known, talk ->
                chosen.key?.let { sensesOf(it, hostState, known[it.toString()], talk.keys) } ?: NO_MODEL_SENSES
            }.stateIn(hs, SharingStarted.Eagerly, NO_MODEL_SENSES)

        // user input: the Think level chosen; it outlives a switch to a model that can't think and back
        private val thinkLevel = MutableStateFlow(Think.Off)

        /**
         * The Think chip: the level chosen, or null when the model can't think. Shown from the header before a load,
         * corrected by the load (D155). A turn never reads it ([levelFor]).
         */
        val think: StateFlow<Think?> =
            combine(senses.map { it.thinks }, thinkLevel) { can, level -> if (can) level else null }
                .stateIn(hs, SharingStarted.Eagerly, null)

        val model: StateFlow<ChatModelState> =
            combine(
                choice,
                library,
                host.state,
                settings.modelSenses,
                settings.talkingModels,
            ) { chosen, lib, hostState, known, talking ->
                ModelInputs(chosen, lib, hostState, known, talking.keys)
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
                            flowOf(readyOrWaking(key, name, hostState, inputs.senses, inputs.talking))
                        }
                    }
                }
            }.stateIn(hs, SharingStarted.Eagerly, ChatModelState.Waking())

        /** The chat's use of the library: its "Use my library", and what a turn gets from it. */
        private val libraryUse = ChatLibraryUse(chats, chatLibrary, callsTools)

        /**
         * "Use my library" (docs/design/rag.md): on or off, saved per chat; null while it isn't offered, with no
         * library or no document in it. A turn reads it from the chat's row, not from here.
         */
        val useLibrary: StateFlow<Boolean?> =
            libraryUse.offered(_chatId).stateIn(hs, SharingStarted.Eagerly, null)

        /** Held by the pump for its whole loop, by a continue, and by a rewrite ([rewriting]). */
        private val turn = Mutex()

        /** The turn under way, load and reply both; volatile only because [stop] cancels it from the UI's thread. */
        @Volatile private var running: Job? = null
        private var conversation: Conversation? = null

        /** What [conversation] was made for: a turn asking with anything else builds a new one ([MadeFor]). */
        private var conversationMadeFor: MadeFor? = null

        init {
            hs.launch {
                val found = initialModelKey()
                choice.update { it.lookedUp(found) }
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
            // the header's word on what a model can do, once it is on the phone: the chip, photos and the mic show
            // before the first load (D155); read once, on the phone, never over the network. The latest model's read
            // starts at once: a slow one for the model left behind is cancelled, and read again when it is chosen
            hs.launch {
                combine(choice, library) { chosen, lib -> chosen.key?.takeIf { k -> lib?.any { it.key == k } == true } }
                    .filterNotNull()
                    .distinctUntilChanged()
                    .collectLatest { sensesReader.ensure(it) }
            }
        }

        // ---- the user's actions -------------------------------------------------------------------------

        /**
         * Whether the message was taken: false while a reply is coming, for an empty message, when the chat has no
         * model at all, and for pictures or a [voice] message sent to a model that can't see or hear them (the limit
         * line says so). The caller keeps the draft when this is false.
         * A taken message is stored as held and answered as soon as its model is on the phone. One sent while an edit,
         * a regenerate or a switch is under way waits for it ([rewrites]), so an edit's cut never takes it away and a
         * switch's model answers it after its divider. Runs on [confined], as a switch does, so the two never both
         * create the chat.
         */
        suspend fun send(text: String, images: List<String> = emptyList(), voice: NewVoice? = null): Boolean =
            withContext(confined) { take(text, images, voice) }

        private suspend fun take(text: String, images: List<String>, voice: NewVoice?): Boolean {
            if (rewrites.value > 0) {
                rewrites.first { it == 0 }
                // the rewrite's pump took the turn before the count fell ([rewriting]): what the rewrite left held is
                // answered first, so this message lands after its reply, not between the two (F188)
                turn.withLock { }
            }
            val names = images.take(MAX_IMAGES)
            val empty = text.isBlank() && names.isEmpty() && voice == null
            val id = if (empty || !canTake()) null else chatFor(names.isNotEmpty(), voice != null)
            if (id == null) return false
            _limit.value = null
            stopped = null
            store(id, text.trim(), names, voice)
            return true
        }

        /**
         * The chat a message with [pictures] or a [voice] message goes to, created now if it is new; null when it
         * can't be taken: the chat has no model, or the model that will answer it can't see or hear it (the limit line
         * says so).
         */
        private suspend fun chatFor(pictures: Boolean, voice: Boolean): Long? {
            val key =
                choice.value.key ?: initialModelKey()?.also { choice.value = ModelChoice.Chosen(it) } ?: return null
            if (refuses(key, pictures, voice)) return null
            val id = ensureChat() ?: return null
            // creating the chat suspends: a switch may have run meanwhile, and its model is the one that answers
            val answering = choice.value.key
            val refused = answering == null || (answering != key && refuses(answering, pictures, voice))
            return id.takeUnless { refused }
        }

        /**
         * A message can be taken now: no reply is coming, the chat isn't being deleted from the drawer (its rows would
         * point at a chat that is gone).
         */
        private fun canTake(): Boolean = !_busy.value && !deleting

        /**
         * Whether [key]'s model refuses the message's [pictures] or [voice] message; the limit line then says why. Its
         * own answer once it is loaded, else what its header or last load said, else the catalog ([sensesOf]).
         */
        private suspend fun refuses(key: HfModel, pictures: Boolean, voice: Boolean): Boolean {
            val known = settings.modelSenses.first()
            val talking = settings.talkingModels.first().keys
            val senses = sensesOf(key, host.state.value, known[key.toString()], talking)
            val refused =
                refusal(senses.sees, senses.hears, pictures, voice, Names.of(key), visionModelName(known))
                    ?: return false
            _limit.value = refused
            return true
        }

        /**
         * Stores a taken message as held, its pictures moved out of the draft, and answers what is held. A picture no
         * longer in the draft fails the send, storing nothing: the row would name a file that is gone or another's.
         */
        private suspend fun store(id: Long, text: String, names: List<String>, voice: NewVoice?) {
            // one unit, whatever happens to the caller: a stored row may resume as cancelled, and its pictures must
            // then stay where the row names them
            withContext(NonCancellable) {
                val moved = checkNotNull(chats.commitDraftImages(names)) { "a picture of the message is gone" }
                try {
                    chats.append(
                        id,
                        NewMessage(
                            MessageKind.User,
                            text,
                            names,
                            state = MessageState.Held,
                            sentAt = clock(),
                            voice = voice,
                        ),
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (
                    @Suppress("TooGenericExceptionCaught") e: Exception,
                ) {
                    // not stored: the draft gets back the pictures this send moved, never one a sent message holds
                    chats.returnDraftImages(moved)
                    throw e
                }
                pump()
            }
        }

        /** Stops the turn, whether the model is still waking (the message stays held) or the reply is streaming. */
        fun stop() {
            val current = running ?: return
            stopped = answering
            current.cancel()
        }

        /** The held message [pump]'s turn answers, by id, while that turn is [running]. */
        @Volatile private var answering: Long? = null

        /**
         * The message whose turn was stopped, by id, until the person sends or rewrites the chat: [pump] skips it and
         * answers every other held message as before. Without it a pump asked for meanwhile (a send's and a library
         * change's for one held message, the second queued behind the first's turn; or a library change after the
         * Stop) starts the stopped message's turn again. Set from any thread by [stop]; cleared on [confined].
         */
        @Volatile private var stopped: Long? = null

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
                if (deleting) return@rewriting false
                // an empty chat has no model to switch from: choosing one just sets it, with no divider
                val switching = isSwitch(messages.value)
                // the in-memory model changes first, so a message sent right after is answered by the new one, and a
                // new chat with no model yet is created on the one chosen
                invalidate()
                _limit.value = null
                choice.value = ModelChoice.Chosen(key)
                val id = ensureChat() ?: return@rewriting false
                if (switching) chats.append(id, switchNotice(key, Names.of(key), clock()))
                chats.setModel(id, key.toString())
                settings.setLastModel(key.toString())
                true
            }
        }

        fun setThink(level: Think) {
            thinkLevel.value = level
        }

        /**
         * Turns "Use my library" on or off: saved with the chat, or, for a new chat, kept until its first message
         * creates it with the choice. Under [creating], so a choice made while the chat is created isn't lost.
         */
        fun setUseLibrary(on: Boolean): Job = hs.launch { creating.withLock { libraryUse.set(_chatId.value, on) } }

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
                if (deleting) return@rewriting false
                _limit.value = null
                val id = _chatId.value.takeIf { it != 0L } ?: return@rewriting false
                val before = messages.value
                val kept = keep(before) ?: return@rewriting false
                chats.truncateFrom(id, position, keepImages = kept)
                invalidate()
                modelToRestate(before, position, choice.value.key)?.let {
                    chats.append(id, switchNotice(it, Names.of(it), clock()))
                }
                then(id, before)
                true
            }
        }

        /**
         * Rewrites (an edit, a regenerate, a switch) are under way; [pump] answers nothing while one is, and [send]
         * waits for none to be. Written on [confined] only.
         */
        private val rewrites = MutableStateFlow(0)

        /**
         * Runs [block], a change to the chat's rows or model, with no turn under way, then answers what is held when
         * [block] says it changed something. The turn is stopped, and [rewrites] keeps [pump] from starting the next
         * held message's turn meanwhile (and [send] waits): a pump whose stopped turn had made its
         * reply row would otherwise go on to the next one while [block] cuts it away. [block] then runs under [turn],
         * so no pump's turn can run beside it. A [block] that changed nothing leaves what is held as a Stop would, so
         * a Stop while the model wakes isn't undone by it.
         */
        private fun rewriting(block: suspend () -> Boolean) {
            hs.launch {
                rewrites.value++
                var changed = false
                try {
                    stopAndWait()
                    changed = turn.withLock { block() }
                } finally {
                    // the pump is queued before the count falls, so it takes the turn ahead of a send that waited
                    // for the count: that send then waits for the pump's turn ([take], F188)
                    if (changed) {
                        stopped = null
                        pump()
                    }
                    rewrites.value--
                }
            }
        }

        private fun StoredMessage.isUserBefore(position: Int) = this.position < position && kind == MessageKind.User

        private fun invalidate() {
            conversation = null
            conversationMadeFor = null
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
                    while (!deleting && rewrites.value == 0) {
                        val id = _chatId.value.takeIf { it != 0L } ?: return@withLock
                        val key = choice.value.key ?: return@withLock
                        val lib = library.value ?: return@withLock
                        if (lib.none { it.key == key }) return@withLock
                        val held =
                            chats.messages(id).first().firstOrNull {
                                it.kind == MessageKind.User &&
                                    it.state == MessageState.Held &&
                                    it.id != stopped
                            }
                                ?: return@withLock
                        // the turn, load and reply both, is the one job [stop] cancels: it is [running]
                        // before the load starts, so a Stop while the model wakes ends the turn (the message
                        // stays held, no reply row is made), and an edit, a regenerate or a switch waits for
                        // the load as it waits for a reply
                        // the chat may have started going, or a rewrite may have begun, while its rows were read;
                        // nothing suspends from here until the turn is [running], where a rewrite's stop finds it
                        if (deleting || rewrites.value > 0 || held.id == stopped) return@withLock
                        var replied = false
                        answering = held.id
                        runAsCurrent { answer(id, key, held) { replied = true } }
                        answering = null
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
                // the library: the search tool on a model that calls tools, else the passages in the turn
                val notSearched: (Exception) -> Unit = { _limit.value = Limits.libraryNotSearched() }
                libraryUse.forTurn(id, loaded.info, user.text, notSearched) { library ->
                    val asked = libraryUse.ask(user, library)
                    val convo = conversationFor(loaded, key, rows, asked, hears, systemPrompt(), library.tool)
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
                    val parts = (History.userMessage(asked, hears) as Message.User).content
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
                        turnOptions(id, key, levelFor(key, loaded.info), continues = false),
                        draft,
                    )
                }
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
         * The conversation for [user]'s turn: the one kept from the last turn while the model, its load, the system
         * prompt ([system], the instructions) and its tools are
         * the same, else a new one from [rows]. A turn with the library's search [tool] always gets a new one: the tool
         * is made for the turn, so its numbers start at [1] (D172).
         */
        @Suppress("LongParameterList") // the turn's model, rows and what it is asked with
        private fun conversationFor(
            loaded: Model,
            key: HfModel,
            rows: List<StoredMessage>,
            user: StoredMessage,
            hears: Boolean,
            system: String?,
            tool: TypedTool<SearchArgs, String>?,
        ): Conversation {
            val madeFor = MadeFor(key, loaded, system, tools = tool != null)
            conversation?.takeIf { tool == null && conversationMadeFor == madeFor }?.let { return it }
            // what the model has seen: answered rows before this question, and replies; other held questions wait
            // their turn
            val seen =
                rows.filter {
                    it.id != user.id && it.state != MessageState.Held &&
                        (it.position < user.position || it.kind == MessageKind.Assistant)
                }
            return loaded
                .conversation(system, History.forModel(seen, hears = hears)) {
                    if (tool != null) tool(tool)
                }.also {
                    conversation = it
                    conversationMadeFor = madeFor
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
                    // a rewrite under way is about to cut or switch the chat this would continue
                    if (deleting || rewrites.value > 0) return@withLock
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
            // the reply keeps the sources it has: a continue searches nothing
            libraryUse.found.clear()
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
                turnOptions(id, key, levelFor(key, loaded.info), continues = true),
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
            val sources = libraryUse.found.take()
            chats.update(replyId, if (sources.isEmpty()) change else change.copy(sources = sources))
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
         * slot), so a send after it finds the model as it was; null with no model chosen or when it didn't load,
         * [limit] then saying why.
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
                chats.createChat(key.toString(), useLibrary = libraryUse.pending.value).also { _chatId.value = it }
            }

        /**
         * A turn's Think level, by the model it loaded rather than by [think]: a send right after the load can't race
         * the chip's flow, and a model whose load says it can't think gets no reasoning settings (D155).
         */
        private fun levelFor(key: HfModel, info: ModelInfo): Think? =
            thinkLevel.value.takeIf {
                Thinking.canThink(
                    info.chatTemplate,
                    info.templateCaps,
                    Catalog.byRepo(key.repo),
                )
            }

        private fun currentName(): String = choice.value.key?.let(Names::of) ?: "This model"

        /** Starts the model's download again after a failure; what had arrived is kept. */
        fun retryDownload() {
            choice.value.key?.let(models::get)
        }

        /** A model on the phone that sees, by what is [known] of each (D155), else the catalog. */
        private fun visionModelName(known: Map<String, ModelSenses>): String? =
            library.value
                ?.firstOrNull { sensesOf(it.key, host.state.value, known[it.key.toString()], emptySet()).sees }
                ?.name
    }

    /**
     * What a chat's conversation was made for: the model's [key]; its [loaded] model (a conversation lives inside one
     * load: after a release the same key loads a new model and the old conversation is closed with the old one, so it
     * is rebuilt from the stored rows); the [system] prompt (Settings › Instructions opens over the chat, which stays
     * open, so a changed setting applies at the next turn); and whether it has the library's search [tools].
     */
    private data class MadeFor(val key: HfModel, val loaded: Model, val system: String?, val tools: Boolean)

    companion object {
        /** Builder's call, settled here: at most four images per message. */
        const val MAX_IMAGES = 4

        private val NO_MODEL_SENSES = Senses(thinks = false, sees = false, hears = false, talks = false)
    }
}
