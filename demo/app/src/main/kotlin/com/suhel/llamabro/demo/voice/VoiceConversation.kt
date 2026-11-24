package com.suhel.llamabro.demo.voice

import com.suhel.llamabro.LlamaBroException
import com.suhel.llamabro.audio.VoiceEvent
import com.suhel.llamabro.audio.VoiceSession
import com.suhel.llamabro.demo.audio.Levels
import com.suhel.llamabro.demo.audio.MILLIS_PER_SECOND
import com.suhel.llamabro.demo.audio.SampleSource
import com.suhel.llamabro.demo.audio.SessionFocus
import com.suhel.llamabro.demo.audio.SpeechOutput
import com.suhel.llamabro.demo.audio.VoiceRecorder
import com.suhel.llamabro.demo.audio.Wav
import com.suhel.llamabro.demo.audio.levelsOf
import com.suhel.llamabro.demo.chat.Instructions
import com.suhel.llamabro.demo.chat.Limit
import com.suhel.llamabro.demo.chat.LimitAction
import com.suhel.llamabro.demo.chat.ModelHold
import com.suhel.llamabro.demo.chat.Thinking
import com.suhel.llamabro.demo.chat.holdUncancelled
import com.suhel.llamabro.demo.chat.key
import com.suhel.llamabro.demo.data.AudioStore
import com.suhel.llamabro.demo.data.ChatRepository
import com.suhel.llamabro.demo.data.MessageChange
import com.suhel.llamabro.demo.data.MessageKind
import com.suhel.llamabro.demo.data.MessageState
import com.suhel.llamabro.demo.data.NewMessage
import com.suhel.llamabro.demo.data.NewVoice
import com.suhel.llamabro.demo.data.SettingsRepository
import com.suhel.llamabro.demo.data.VoiceTurns
import com.suhel.llamabro.hub.HfModel
import com.suhel.llamabro.voiceSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.Locale

/** Where a voice conversation is (D100): waiting for the person, hearing them, before the reply, or speaking it. */
enum class VoiceStage { Ready, Listening, Thinking, Speaking }

/** What voice mode shows (D100): the phase, the orb's level, the person's last words, the reply's caption. */
data class VoiceUi(
    val phase: VoiceStage = VoiceStage.Ready,
    val level: Float = 0f,
    val userWords: String? = null,
    val caption: String = "",
    /** The voice the model speaks with, once the session has opened. */
    val voice: String? = null,
    /** What is being heard ends on its own at a pause (hands-free, D147), so the hint says so. */
    val sendsOnPause: Boolean = false,
)

/**
 * One chat's voice mode (D100): a spoken conversation with [key]'s model through the SDK's voice session (D98), hold
 * or tap to talk, or hands-free (D147). Each turn is saved to chat [chatId] as an ordinary message: the person's as a
 * voice message whose text is its caption (D99), the reply as its text with its speech. The session opens on [open]
 * and again on the first talk after [pause] (leaving the screen); [close] ends it for good.
 *
 * An actor ([Steps]): every change of state is a step on one thread of [dispatcher], run to its end one at a time and
 * in the order asked, so a release that comes while the model is still loading waits for the press before it. The
 * session's events, the captions and the player's end come in as steps too. The microphone side is a [TurnListener]
 * in the same actor. The microphone and the speaker block, so they run on [io] and hand their results back.
 * [microphone] and [speech] open the phone's audio, under [focus], which the session holds from its opening to its end
 * (D147); tests pass their own.
 */
@Suppress("LongParameterList") // seams the tests replace, passed in
class VoiceConversation(
    private val chatId: Long,
    private val key: HfModel,
    private val name: String,
    private val hold: suspend (slots: Int, limit: MutableStateFlow<Limit?>) -> ModelHold?,
    private val settings: SettingsRepository,
    private val chats: ChatRepository,
    private val audio: AudioStore,
    private val microphone: () -> SampleSource,
    private val speech: (rate: Int) -> SpeechOutput,
    private val scope: CoroutineScope,
    dispatcher: CoroutineDispatcher,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = System::currentTimeMillis,
    private val locale: () -> Locale = Locale::getDefault,
    private val focus: SessionFocus = SessionFocus.None,
) {
    private val steps = Steps(scope, dispatcher)
    private val confined = steps.dispatcher

    // external event: the speech session's phases, levels and captions
    private val _ui = MutableStateFlow(VoiceUi())
    val ui: StateFlow<VoiceUi> = _ui.asStateFlow()

    /**
     * Why voice mode can't go on (the model didn't load, the microphone was refused), with its way out. An outside
     * event: the session's failures, and the load's, which the engine writes.
     */
    val limit = MutableStateFlow<Limit?>(null)

    private var held: ModelHold? = null
    private var session: VoiceSession? = null
    private var events: Job? = null

    // read by the microphone's reader too, for the echo guard
    @Volatile private var output: SpeechOutput? = null

    // turns end at a pause, and talking over a reply stops it, as Settings › Voice said when the session opened (D147)
    private var handsFree = false
    private var interrupting = false

    private val isOpen: Boolean get() = session != null && !closed

    private val listener =
        TurnListener(
            scope,
            steps,
            io,
            microphone,
            limit,
            _ui,
            object : TurnListener.Callbacks {
                override fun onAppend(chunk: ShortArray) {
                    session?.append(chunk)
                }

                override suspend fun onInterrupt() = stopReply()

                override suspend fun onTurn(turn: PcmBuffer) = sendTurn(turn)

                override fun playbackDb(): Double = output?.let { VoiceRecorder.levelDb(it.level) } ?: Levels.SILENCE_DB
            },
        )

    /** Called when listening has been told to stop, before it is waited for: a test's sign that a release arrived. */
    internal var stoppingListening: () -> Unit
        get() = listener.stoppingListening
        set(value) {
            listener.stoppingListening = value
        }

    private var reply: Reply? = null
    private var meter: Job? = null
    private var closed = false

    /** Captions of the person's turns while the session is open and captions are on (D109). */
    private var captions: TurnCaptions? = null

    /** The reply being made: its id, what it said, its speech so far and its playback. */
    private class Reply(val id: String) {
        val text = StringBuilder()
        val pcm = PcmBuffer()
        var playback: Channel<ShortArray>? = null
        var player: Job? = null
        var saved = false

        /** Its [VoiceEvent.ReplyDone] came: the server has finished it, so there is nothing to cancel. */
        var done = false
    }

    fun open() {
        steps.run { if (ensureSession()) listenAgain() }
    }

    /** The line's button: the line goes, and the session opens again if it had closed. */
    fun retry() {
        limit.value = null
        open()
    }

    /**
     * The talk button went down: stop a reply being made, then listen until the release or the next tap (D100).
     * Hands-free, the turn starts at once on the ear's microphone, with what it kept from before the press (D147).
     */
    fun talkStart() {
        steps.run {
            // an ear that hasn't heard a turn start gives way to the press, or, hands-free, takes it; one that has is
            // that turn
            if (listener.isWaiting) {
                if (!handsFree) {
                    listener.stop()
                } else {
                    if (reply != null) stopReply()
                    listener.press()
                    return@run
                }
            }
            if (listener.isListening) return@run
            if (reply != null) stopReply()
            if (!ensureSession()) return@run
            if (handsFree) listener.startEar(held = true) else listener.startHeld(session)
        }
    }

    /** Hands-free, voice mode listens again once a reply is over (D147); one that a tap stopped waits for a tap. */
    private suspend fun listenAgain() {
        if (!handsFree || !isOpen) return
        if (reply == null && !listener.isListening && _ui.value.phase == VoiceStage.Ready) listener.startEar()
    }

    /**
     * The talk button came up, or was tapped again: the turn goes to the model, which answers (D100). Hands-free, the
     * turn ends at once, without waiting for the pause; with no speech heard yet, listening stops until a tap (D147).
     */
    fun talkEnd() {
        steps.run { listener.endHeld()?.let { sendTurn(it) } }
    }

    /** A turn heard: saved to the chat and committed, so the model answers; a tap's worth is nothing to send. */
    private suspend fun sendTurn(samples: PcmBuffer) {
        if (samples.size * MILLIS_PER_SECOND < MIN_TURN_MILLIS * listener.rate) {
            _ui.update { it.copy(phase = VoiceStage.Ready, level = 0f) }
            return
        }
        // a turn whose recording can't be kept (the disk is full) still goes to the model; the chat says so
        saveClip(samples, listener.rate)?.let { voice ->
            val row = chats.append(chatId, NewMessage(MessageKind.User, "", sentAt = clock(), voice = voice))
            captions?.add(row, audio.file(voice.name))
        }
        _ui.update {
            it.copy(phase = VoiceStage.Thinking, level = 0f, userWords = null, caption = "", sendsOnPause = false)
        }
        session?.commit()
    }

    /** Back on the screen (D147): hands-free listens again by itself; Tap to talk waits for the press, as before. */
    fun resume() {
        steps.run { if (handsFree && ensureSession()) listenAgain() }
    }

    /** Stop: the reply ends where it is, kept as Stop keeps a written one. */
    fun stop() {
        steps.run {
            stopReply()
            listenAgain()
        }
    }

    /**
     * Leaving the screen (Home, a call, the lock, D100): recording and playback stop as Stop does, and the session
     * closes and lets the model go; the next talk opens a new one.
     */
    fun pause() {
        steps.run { endSession() }
    }

    /** Voice mode closes: as [pause], for good. */
    fun close() {
        steps.run {
            closed = true
            endSession()
        }
    }

    /** The session, opened (and the model held) if it isn't; false when it couldn't be, with [limit] saying why. */
    private suspend fun ensureSession(): Boolean {
        if (closed) return false
        if (session != null) return true
        limit.value = null
        val now = settings.settings.first()
        handsFree = now.voice.turns == VoiceTurns.HandsFree
        interrupting = now.voice.interruptByTalking
        val lease = holdUncancelled { hold(if (now.voice.captionYou) 2 else 1, limit) } ?: return false
        val info = lease.model.info
        val voice = now.voice.name?.takeIf { it in info.voices } ?: info.voices.firstOrNull()
        val opened =
            try {
                lease.model.voiceSession {
                    this.voice = voice
                    language = spokenLanguage(locale())
                    instructions =
                        listOfNotNull(INSTRUCTION, Instructions.system(now.instructionsAbout, now.instructionsStyle))
                            .joinToString("\n\n")
                    maxOutputTokens = MAX_REPLY_TOKENS
                    // the microphone opens only at the first talk, so its rate is checked against this one then
                    inputSampleRate = TurnListener.INPUT_RATE
                }
            } catch (e: CancellationException) {
                lease.release()
                throw e
            } catch (expected: LlamaBroException) {
                lease.release()
                limit.value = Limit("$name couldn't start talking. Try again.", LimitAction.Retry, "Retry")
                return false
            }
        held = lease
        session = opened
        // before the speaker is made: it plays as a call's voice when talking interrupts a reply (D147)
        focus.take(communication = interrupting)
        if (now.voice.captionYou) {
            captions =
                TurnCaptions(lease.model, scope, confined) { row, text ->
                    steps.run { onCaption(opened, row, text) }.join()
                }
        }
        output = speech(opened.outputSampleRate ?: OUT_RATE)
        _ui.update { it.copy(voice = voice) }
        events = collectEvents(opened)
        return true
    }

    /** The session's events, one at a time and each a step; the session's end, a step that ends it here too. */
    private fun collectEvents(opened: VoiceSession): Job =
        scope.launch(confined) {
            try {
                // one event at a time, each a step: a session that has ended since takes none of them
                opened.events.collect { event -> steps.run { if (session === opened) onEvent(event) }.join() }
            } catch (expected: LlamaBroException) {
                // the model was closed under the session: the next talk opens a new one
            }
            steps.run {
                if (session === opened) {
                    // the server ended it: this collector is done, so it mustn't be cancelled while it tidies up
                    events = null
                    endSession()
                }
            }
        }

    private suspend fun onEvent(event: VoiceEvent) {
        when (event) {
            is VoiceEvent.TurnCommitted -> Unit
            is VoiceEvent.ReplyStarted -> reply = Reply(event.replyId)
            is VoiceEvent.ReplyText -> onReplyText(event)
            is VoiceEvent.ReplyAudio -> onReplyAudio(event)
            is VoiceEvent.ReplyDone -> onReplyDone(event)
            is VoiceEvent.Error -> onError(event)
            is VoiceEvent.Other -> Unit
        }
    }

    /**
     * The person's words, captioned: kept as their voice message's text, and shown under the orb while the session
     * they were said in ([from]) is still the one open.
     */
    private suspend fun onCaption(from: VoiceSession, row: Long, text: String) {
        if (text.isNotEmpty()) chats.update(row, MessageChange(text = text))
        if (session === from) _ui.update { it.copy(userWords = text.ifEmpty { null }) }
    }

    private fun onReplyText(event: VoiceEvent.ReplyText) {
        val r = reply ?: return
        r.text.append(event.delta)
        _ui.update { it.copy(caption = r.text.toString()) }
    }

    private fun onReplyAudio(event: VoiceEvent.ReplyAudio) {
        val r = reply ?: return
        r.pcm.add(event.pcm)
        if (r.playback == null) startPlayback(r)
        r.playback?.trySend(event.pcm)
    }

    private suspend fun onReplyDone(event: VoiceEvent.ReplyDone) {
        val r = reply ?: return
        r.done = true
        if (r.text.isEmpty()) r.text.append(event.text)
        r.playback?.close()
        saveReply(r, stopped = event.cancelled)
        if (r.player == null) {
            finishReply(r)
            listenAgain()
        }
    }

    /**
     * A short tap's empty commit and a caption that failed leave the conversation as it was; others say so, and
     * hands-free listens again for the next try.
     */
    private suspend fun onError(event: VoiceEvent.Error) {
        if (event.type in QUIET_ERRORS) return
        limit.value = Limit("$name couldn't answer that. Try again.", LimitAction.Retry, "Retry")
        _ui.update { if (it.phase == VoiceStage.Thinking) it.copy(phase = VoiceStage.Ready) else it }
        listenAgain()
    }

    /** The reply's speech plays as it arrives, on [io]; the orb follows what plays. */
    private fun startPlayback(r: Reply) {
        val out = output ?: return
        val queue = Channel<ShortArray>(Channel.UNLIMITED)
        r.playback = queue
        _ui.update { it.copy(phase = VoiceStage.Speaking) }
        meter =
            scope.launch(confined) {
                while (isActive) {
                    _ui.update { if (it.phase == VoiceStage.Speaking) it.copy(level = out.level) else it }
                    delay(METER_MILLIS)
                }
            }
        r.player =
            scope.launch(io) {
                var first = interrupting
                for (pcm in queue) {
                    ensureActive() // a stopped reply plays nothing more of what was queued
                    out.write(pcm)
                    if (first) {
                        // the ear opens once the speaker plays, so the guard learns from the reply's first words
                        first = false
                        steps.run {
                            if (reply === r && !listener.isListening && isOpen) {
                                listener.startEar(watching = true, staysOpen = handsFree)
                            }
                        }
                    }
                }
                out.drain()
                steps.run {
                    finishReply(r)
                    listenAgain()
                }
            }
    }

    /** The reply has been made and spoken (or stopped): back to ready, unless another turn began meanwhile. */
    private fun finishReply(r: Reply) {
        if (reply !== r) return
        reply = null
        meter?.cancel()
        meter = null
        _ui.update {
            if (it.phase == VoiceStage.Speaking ||
                it.phase == VoiceStage.Thinking
            ) {
                it.copy(phase = VoiceStage.Ready, level = 0f)
            } else {
                it
            }
        }
        // the ear that listened for an interruption: hands-free it waits for the next turn, else it closes
        listener.replyEnded(staysOpen = handsFree)
    }

    /** Stops the reply being made or spoken: what it said so far is kept, marked as stopped. */
    private suspend fun stopReply() {
        val r = reply ?: return
        // a reply the server has finished is only playing out: a cancel would reach nothing
        if (!r.done) session?.cancelReply()
        // drop the speech still queued (close would play it out), stop the player, then the track: stopping it
        // releases a write blocked on a full buffer. A write that slipped in before the player saw its cancel would
        // take audio focus again, so the track is stopped once more after the player has ended.
        r.playback?.cancel()
        r.player?.cancel()
        output?.stop()
        r.player?.join()
        output?.stop()
        saveReply(r, stopped = true)
        finishReply(r)
    }

    private suspend fun saveReply(r: Reply, stopped: Boolean) {
        if (r.saved || (r.text.isEmpty() && r.pcm.size == 0)) return
        r.saved = true
        val rate = session?.outputSampleRate ?: OUT_RATE
        val voice = if (r.pcm.size > 0) saveClip(r.pcm, rate) else null
        chats.append(
            chatId,
            NewMessage(
                MessageKind.Assistant,
                r.text.toString().trim(),
                modelKey = key.toString(),
                state = if (stopped) MessageState.Stopped else MessageState.Done,
                sentAt = clock(),
                voice = voice,
            ),
        )
    }

    /** Ends the session: the turn being recorded is dropped, the reply stops, and the model is let go. */
    private suspend fun endSession() {
        listener.stop()
        stopReply()
        events?.cancel()
        events = null
        session?.close()
        session = null
        output?.close()
        output = null
        focus.release()
        captions?.close()
        captions = null
        held?.release()
        held = null
        _ui.update { it.copy(phase = VoiceStage.Ready, level = 0f) }
    }

    /**
     * A clip in the [AudioStore] as a 16-bit mono WAV at [rate], with its length and the bubble's levels, written on
     * [io]; null, with [limit] saying so, when it can't be written.
     */
    private suspend fun saveClip(pcm: PcmBuffer, rate: Int): NewVoice? =
        try {
            withContext(io) {
                val samples = pcm.toArray()
                val name = audio.newName()
                val file = audio.file(name)
                // the store's folder exists only once something was recorded: voice mode may be the first
                file.parentFile?.mkdirs()
                file.writeBytes(Wav.file(Wav.bytes(samples), rate))
                NewVoice(
                    name,
                    samples.size * MILLIS_PER_SECOND / rate,
                    VoiceRecorder.peaks(levelsOf(samples, rate), VoiceRecorder.BUBBLE_LEVELS),
                )
            }
        } catch (expected: IOException) {
            limit.value = Limit("This turn couldn't be kept in the chat. Free some space.", LimitAction.Retry, "Got it")
            null
        }

    companion object {
        /** LFM3-Omni answers only when told to (F166); the person's custom instructions follow it. */
        const val INSTRUCTION =
            "You are a helpful voice assistant. Answer the user's spoken question in your own words."

        /** A cap on a reply (about a minute of speech, F170), against one that runs on (F166, F167). */
        const val MAX_REPLY_TOKENS = 1024

        /**
         * The languages upstream's LFM3 speech prompt names (`common/chat.cpp`, `lfm3_language_name`): the phone's,
         * when it is one of them, else English (D100).
         */
        val SPOKEN =
            setOf(
                "ar",
                "da",
                "de",
                "el",
                "en",
                "es",
                "fi",
                "fr",
                "he",
                "it",
                "ja",
                "ko",
                "nl",
                "pl",
                "pt",
                "sv",
                "tr",
                "zh",
            )

        fun spokenLanguage(locale: Locale): String = locale.language.lowercase().takeIf { it in SPOKEN } ?: "en"

        private const val OUT_RATE = 24_000

        /** Under a fifth of a second is a tap, not something said. */
        private const val MIN_TURN_MILLIS = 200L

        private const val METER_MILLIS = 50L
        private val QUIET_ERRORS = setOf("input_audio_buffer_commit_empty")
    }
}

/** 16-bit samples gathered chunk by chunk. */
internal class PcmBuffer {
    private val chunks = mutableListOf<ShortArray>()
    var size = 0
        private set

    /** The chunks, in the order they came. */
    val blocks: List<ShortArray> get() = chunks

    fun add(chunk: ShortArray) {
        chunks += chunk
        size += chunk.size
    }

    fun toArray(): ShortArray {
        val out = ShortArray(size)
        var at = 0
        for (c in chunks) {
            c.copyInto(out, at)
            at += c.size
        }
        return out
    }
}
