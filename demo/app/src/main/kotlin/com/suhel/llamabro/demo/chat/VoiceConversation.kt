package com.suhel.llamabro.demo.chat

import com.suhel.llamabro.LlamaBroException
import com.suhel.llamabro.demo.data.AudioStore
import com.suhel.llamabro.demo.data.ChatRepository
import com.suhel.llamabro.demo.data.MessageChange
import com.suhel.llamabro.demo.data.MessageKind
import com.suhel.llamabro.demo.data.MessageState
import com.suhel.llamabro.demo.data.NewMessage
import com.suhel.llamabro.demo.data.NewVoice
import com.suhel.llamabro.demo.data.SettingsRepository
import com.suhel.llamabro.demo.designsystem.components.VoicePhase
import com.suhel.llamabro.hub.HfModel
import com.suhel.llamabro.voice.VoiceEvent
import com.suhel.llamabro.voice.VoiceSession
import com.suhel.llamabro.voiceSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale

/** What voice mode shows (D100): the phase, the orb's level, the person's last words, the reply's caption. */
data class VoiceUi(
    val phase: VoicePhase = VoicePhase.Ready,
    val level: Float = 0f,
    val userWords: String? = null,
    val caption: String = "",
    /** The voice the model speaks with, once the session has opened. */
    val voice: String? = null,
)

/**
 * One chat's voice mode (D100): a spoken conversation with [key]'s model through the SDK's voice session (D98), hold
 * to talk. Each turn is saved to chat [chatId] as an ordinary message: the person's as a voice message whose text is
 * its caption (D99), the reply as its text with its speech. The session opens on [open] and again on the first talk
 * after [pause] (leaving the screen); [close] ends it for good.
 *
 * Every change of state runs on [dispatcher], one at a time and in the order asked, under one fair lock: a release
 * that comes while the model is still loading waits for the press before it. The microphone and the speaker block, so
 * they run on [io], and hand their results back. [microphone] and [speech] open the phone's audio; tests pass their
 * own.
 */
class VoiceConversation(
    private val chatId: Long,
    private val key: HfModel,
    private val name: String,
    private val hold: suspend (slots: Int, limit: MutableStateFlow<Limit?>) -> VoiceHold?,
    private val settings: SettingsRepository,
    private val chats: ChatRepository,
    private val audio: AudioStore,
    private val microphone: () -> SampleSource,
    private val speech: (rate: Int) -> SpeechOutput,
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = System::currentTimeMillis,
    private val locale: () -> Locale = Locale::getDefault,
) {
    private val _ui = MutableStateFlow(VoiceUi())
    val ui: StateFlow<VoiceUi> = _ui.asStateFlow()

    /** Why voice mode can't go on (the model didn't load, the microphone was refused), with its way out. */
    val limit = MutableStateFlow<Limit?>(null)

    private var held: VoiceHold? = null
    private var session: VoiceSession? = null
    private var events: Job? = null
    private var output: SpeechOutput? = null
    private var listening: Job? = null
    private var listeningMic: SampleSource? = null
    private var turnAudio: Channel<ShortArray>? = null
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
    }

    fun open() {
        run { ensureSession() }
    }

    /** The line's button: the line goes, and the session opens again if it had closed. */
    fun retry() {
        limit.value = null
        open()
    }

    /** The talk button went down: stop a reply being made, then listen (D100). */
    fun talkStart() {
        run {
            if (listening != null) return@run
            if (reply != null) stopReply()
            if (!ensureSession()) return@run
            val mic = openMicrophone() ?: return@run
            val samples = Channel<ShortArray>(Channel.UNLIMITED)
            turnAudio = samples
            listeningMic = mic
            _ui.update { it.copy(phase = VoicePhase.Listening, level = 0f) }
            val live = session
            listening = scope.launch(io) { listen(mic, live, samples) }
        }
    }

    /** The microphone, or null with [limit] saying why it couldn't be opened (refused, or taken by a call). */
    private fun openMicrophone(): SampleSource? =
        runCatching { microphone() }
            .onFailure { if (it is CancellationException) throw it }
            .onFailure { limit.value = Limits.microphone() }
            .getOrNull()

    /** Reads [mic] until the turn ends or the microphone does: each block goes to [live] and to [samples]. */
    private fun CoroutineScope.listen(mic: SampleSource, live: VoiceSession?, samples: Channel<ShortArray>) {
        val buffer = ShortArray(mic.rate / VoiceRecorder.LEVELS_PER_SECOND)
        var n = 0
        while (isActive && n >= 0) {
            n = mic.read(buffer)
            if (n > 0) {
                val chunk = buffer.copyOf(n)
                live?.append(chunk)
                samples.trySend(chunk)
                val level = VoiceRecorder.level(chunk, n)
                _ui.update { if (it.phase == VoicePhase.Listening) it.copy(level = level) else it }
            }
        }
        samples.close()
    }

    /**
     * Ends listening: the reading stops and the microphone closes, here rather than in the reading, which a release
     * right after the press can cancel before it starts.
     */
    private suspend fun stopListening() {
        listening?.let {
            it.cancel()
            it.join()
        }
        listening = null
        listeningMic?.close()
        listeningMic = null
        turnAudio?.close()
    }

    /** The talk button came up: the turn goes to the model, which answers (D100). */
    fun talkEnd() {
        run {
            if (listening == null) return@run
            stopListening()
            val samples = PcmBuffer()
            turnAudio?.let { channel -> for (chunk in channel) samples.add(chunk) }
            turnAudio = null
            if (samples.size < MIN_TURN_SAMPLES) {
                // a tap, not a turn: nothing to send
                _ui.update { it.copy(phase = VoicePhase.Ready, level = 0f) }
                return@run
            }
            // a turn whose recording can't be kept (the disk is full) still goes to the model; the chat says so
            saveClip(samples, MIC_RATE)?.let { voice ->
                val row = chats.append(chatId, NewMessage(MessageKind.User, "", sentAt = clock(), voice = voice))
                captions?.add(row, audio.file(voice.name))
            }
            _ui.update { it.copy(phase = VoicePhase.Thinking, level = 0f, userWords = null, caption = "") }
            session?.commit()
        }
    }

    /** Stop: the reply ends where it is, kept as Stop keeps a written one. */
    fun stop() {
        run { stopReply() }
    }

    /**
     * Leaving the screen (Home, a call, the lock, D100): recording and playback stop as Stop does, and the session
     * closes and lets the model go; the next talk opens a new one.
     */
    fun pause() {
        run { endSession() }
    }

    /** Voice mode closes: as [pause], for good. */
    fun close() {
        run {
            closed = true
            endSession()
        }
    }

    private val turn = Mutex()

    private fun run(block: suspend () -> Unit) {
        // UNDISPATCHED: the lock is asked for in the caller's order, so steps queue as they were asked
        scope.launch(dispatcher, start = CoroutineStart.UNDISPATCHED) { turn.withLock { block() } }
    }

    /** The session, opened (and the model held) if it isn't; false when it couldn't be, with [limit] saying why. */
    private suspend fun ensureSession(): Boolean {
        if (closed) return false
        if (session != null) return true
        limit.value = null
        val now = settings.settings.first()
        val lease = hold(if (now.captionYou) 2 else 1, limit) ?: return false
        val info = lease.model.info
        val voice = now.voice?.takeIf { it in info.voices } ?: info.voices.firstOrNull()
        val opened =
            try {
                lease.model.voiceSession {
                    this.voice = voice
                    language = spokenLanguage(locale())
                    instructions =
                        listOfNotNull(INSTRUCTION, Instructions.system(now.instructionsAbout, now.instructionsStyle))
                            .joinToString("\n\n")
                    maxOutputTokens = MAX_REPLY_TOKENS
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
        if (now.captionYou) {
            captions =
                TurnCaptions(lease.model, scope, dispatcher) { row, text -> turn.withLock { onCaption(row, text) } }
        }
        output = speech(opened.outputSampleRate ?: OUT_RATE)
        _ui.update { it.copy(voice = voice) }
        events =
            scope.launch(dispatcher) {
                try {
                    opened.events.collect { event -> turn.withLock { onEvent(event) } }
                } catch (expected: LlamaBroException) {
                    // the model was closed under the session: the next talk opens a new one
                }
                turn.withLock {
                    if (session === opened) {
                        // the server ended it: this collector is done, so it mustn't cancel itself while it tidies up
                        events = null
                        endSession()
                    }
                }
            }
        return true
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

    /** The person's words, captioned: kept as their voice message's text, and shown under the orb. */
    private suspend fun onCaption(row: Long, text: String) {
        if (text.isNotEmpty()) chats.update(row, MessageChange(text = text))
        _ui.update { it.copy(userWords = text.ifEmpty { null }) }
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
        if (r.text.isEmpty()) r.text.append(event.text)
        r.playback?.close()
        saveReply(r, stopped = event.cancelled)
        if (r.player == null) finishReply(r)
    }

    /** A short tap's empty commit and a caption that failed leave the conversation as it was; others say so. */
    private fun onError(event: VoiceEvent.Error) {
        if (event.type in QUIET_ERRORS) return
        limit.value = Limit("$name couldn't answer that. Try again.", LimitAction.Retry, "Retry")
        _ui.update { if (it.phase == VoicePhase.Thinking) it.copy(phase = VoicePhase.Ready) else it }
    }

    /** The reply's speech plays as it arrives; the orb follows what plays. */
    private fun startPlayback(r: Reply) {
        val out = output ?: return
        val queue = Channel<ShortArray>(Channel.UNLIMITED)
        r.playback = queue
        _ui.update { it.copy(phase = VoicePhase.Speaking) }
        meter =
            scope.launch(dispatcher) {
                while (isActive) {
                    _ui.update { if (it.phase == VoicePhase.Speaking) it.copy(level = out.level) else it }
                    delay(METER_MILLIS)
                }
            }
        r.player =
            scope.launch(io) {
                for (pcm in queue) {
                    ensureActive() // a stopped reply plays nothing more of what was queued
                    out.write(pcm)
                }
                out.drain()
                withContext(dispatcher) { turn.withLock { finishReply(r) } }
            }
    }

    /** The reply has been made and spoken (or stopped): back to ready, unless another turn began meanwhile. */
    private fun finishReply(r: Reply) {
        if (reply !== r) return
        reply = null
        meter?.cancel()
        meter = null
        _ui.update {
            if (it.phase == VoicePhase.Speaking ||
                it.phase == VoicePhase.Thinking
            ) {
                it.copy(phase = VoicePhase.Ready, level = 0f)
            } else {
                it
            }
        }
    }

    /** Stops the reply being made or spoken: what it said so far is kept, marked as stopped. */
    private suspend fun stopReply() {
        val r = reply ?: return
        session?.cancelReply()
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
        stopListening()
        turnAudio = null
        stopReply()
        events?.cancel()
        events = null
        session?.close()
        session = null
        output?.close()
        output = null
        captions?.close()
        captions = null
        held?.release()
        held = null
        _ui.update { it.copy(phase = VoicePhase.Ready, level = 0f) }
    }

    /**
     * A clip in the [AudioStore] as a 16-bit mono WAV at [rate], with its length and the bubble's levels; null, with
     * [limit] saying so, when it can't be written.
     */
    private suspend fun saveClip(pcm: PcmBuffer, rate: Int): NewVoice? =
        try {
            writeClip(pcm, rate)
        } catch (expected: IOException) {
            limit.value = Limit("This turn couldn't be kept in the chat. Free some space.", LimitAction.Retry, "Got it")
            null
        }

    private suspend fun writeClip(pcm: PcmBuffer, rate: Int): NewVoice =
        withContext(io) {
            val name = audio.newName()
            val file: File = audio.file(name)
            // the store's folder exists only once something was recorded: voice mode may be the first
            file.parentFile?.mkdirs()
            val samples = pcm.toArray()
            RandomAccessFile(file, "rw").use { out ->
                out.setLength(0)
                VoiceRecorder.writeHeader(out, rate, samples.size.toLong())
                val bytes = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
                samples.forEach { bytes.putShort(it) }
                out.write(bytes.array())
            }
            val block = rate / VoiceRecorder.LEVELS_PER_SECOND
            val levels =
                (samples.indices step block).map { i ->
                    val n = minOf(block, samples.size - i)
                    VoiceRecorder.level(samples.copyOfRange(i, i + n), n)
                }
            NewVoice(
                name,
                samples.size * MILLIS_PER_SECOND / rate,
                VoiceRecorder.peaks(levels, VoiceRecorder.BUBBLE_LEVELS),
            )
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

        private const val MIC_RATE = 16_000
        private const val OUT_RATE = 24_000

        /** Under a fifth of a second is a tap, not something said. */
        private const val MIN_TURN_SAMPLES = MIC_RATE / 5
        private const val METER_MILLIS = 50L
        private const val MILLIS_PER_SECOND = 1_000L
        private val QUIET_ERRORS = setOf("input_audio_buffer_commit_empty")
    }
}

/** 16-bit samples gathered chunk by chunk. */
internal class PcmBuffer {
    private val chunks = mutableListOf<ShortArray>()
    var size = 0
        private set

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
