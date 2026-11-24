package com.suhel.llamabro.demo.voice

import com.suhel.llamabro.audio.VoiceSession
import com.suhel.llamabro.demo.audio.Levels
import com.suhel.llamabro.demo.audio.SampleSource
import com.suhel.llamabro.demo.audio.VoiceRecorder
import com.suhel.llamabro.demo.audio.blocks
import com.suhel.llamabro.demo.chat.Limit
import com.suhel.llamabro.demo.chat.LimitAction
import com.suhel.llamabro.demo.chat.Limits
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Voice mode's microphone side (D100, D147): a turn held by the talk button ([startHeld], [endHeld]), or an ear that
 * listens by itself ([startEar]) and decides with a [HandsFreeEar]. Part of [VoiceConversation]'s actor: every call
 * comes from one of its [steps], and what the reader hears comes back as steps too; the microphone is opened and read
 * on [io], and closed once, by the reader, however listening ends. What the session is to hear, a reply to stop and a
 * finished turn go back through [callbacks]; [ui] shows the phase and the microphone's level.
 */
internal class TurnListener(
    private val scope: CoroutineScope,
    private val steps: Steps,
    private val io: CoroutineDispatcher,
    private val microphone: () -> SampleSource,
    private val limit: MutableStateFlow<Limit?>,
    private val ui: MutableStateFlow<VoiceUi>,
    private val callbacks: Callbacks,
) {
    /** What the listener hands back to the conversation, each inside a step. */
    interface Callbacks {
        /** A block of the person's turn, for the session to hear. */
        fun onAppend(chunk: ShortArray)

        /** Talking over the reply started a turn: the reply stops before the session hears it. */
        suspend fun onInterrupt()

        /** The ear has closed on a turn that is the person's: it goes to the model. */
        suspend fun onTurn(turn: PcmBuffer)

        /** What the speaker plays now, in dBFS, for the ear's echo guard; asked on the reader's thread. */
        fun playbackDb(): Double
    }

    /** A block as the reader read it, with what the speaker played then: the echo in it is of that, not of later. */
    private class Read(val pcm: ShortArray, val playbackDb: Double)

    // the one word the actor sends the reader: stop after the block being read, which is kept
    private val talking = MutableStateFlow(false)
    private var listening: Job? = null
    private var turnAudio: Channel<Read>? = null

    /** Voice mode listening by itself (D147); null while the talk button holds the turn, or nothing listens. */
    private var ear: HandsFreeEar? = null

    /** The room's floor as the last ear heard it, where the next one starts, so speech in its first block counts. */
    private var lastFloorDb: Double? = null

    /** After a noise turn, whether the ear stays open (hands-free) or closes (an interruption in Tap to talk). */
    private var staysOpen = false

    /** Called when listening has been told to stop, before it is waited for: a test's sign that a release arrived. */
    var stoppingListening: () -> Unit = {}

    /** The rate of the microphone the turn is recorded from: its clip's, and the measure of a tap. */
    var rate: Int = 0
        private set

    val isListening: Boolean get() = listening != null

    /** An ear is open and no turn has started in it: a press gives way to it, or takes it over. */
    val isWaiting: Boolean get() = ear?.let { it.turn == null } == true

    /** A turn held by the talk button, read live into [session]; false when the microphone couldn't open. */
    suspend fun startHeld(session: VoiceSession?): Boolean {
        val mic = openTurnMicrophone() ?: return false
        val samples = Channel<Read>(Channel.UNLIMITED)
        turnAudio = samples
        rate = mic.rate
        ui.update { it.copy(phase = VoiceStage.Listening, level = 0f, sendsOnPause = false) }
        talking.value = true
        listening = listen(mic, session, samples, playback = false)
        return true
    }

    /**
     * Listens by itself (D147): the microphone opens, and the ear starts and ends the turn. [watching] a reply being
     * spoken, a turn it hears interrupts the reply; [held], the press has started the turn already; [staysOpen], noise
     * that started a turn leaves it listening.
     */
    suspend fun startEar(watching: Boolean = false, held: Boolean = false, staysOpen: Boolean = true) {
        val mic = openTurnMicrophone() ?: return
        val samples = Channel<Read>(Channel.UNLIMITED)
        val heard = HandsFreeEar(floorDb = lastFloorDb)
        heard.watching = watching
        ear = heard
        this.staysOpen = staysOpen
        rate = mic.rate
        talking.value = true
        listening = listen(mic, null, samples, playback = true)
        if (held) {
            press()
        } else if (!watching) {
            ui.update { it.copy(phase = VoiceStage.Listening, level = 0f, sendsOnPause = true) }
        }
        scope.launch(steps.dispatcher) {
            for (read in samples) steps.run { hear(heard, read) }.join()
            steps.run { earDone(heard) }
        }
    }

    /** The press holds the ear's turn: it starts now, the session hears it as it goes, and the release sends it. */
    fun press() {
        val heard = ear ?: return
        heard.press()
        for (chunk in heard.flush()) callbacks.onAppend(chunk)
        ui.update { it.copy(phase = VoiceStage.Listening, level = 0f, sendsOnPause = false) }
    }

    /**
     * The talk button came up, or was tapped again: a held turn's samples, once the microphone has closed. An ear's
     * turn ends at once instead and reaches [Callbacks.onTurn] when its reader has stopped; null then, as when nothing
     * listens.
     */
    suspend fun endHeld(): PcmBuffer? {
        if (listening == null) return null
        val heard = ear
        if (heard != null) {
            // the reader stops after the block it is reading, and the ear sends what it heard
            heard.send()
            for (chunk in heard.flush()) callbacks.onAppend(chunk)
            talking.value = false
            stoppingListening()
            return null
        }
        val audio = turnAudio
        stop()
        val samples = PcmBuffer()
        audio?.let { channel -> for (read in channel) samples.add(read.pcm) }
        return samples
    }

    /** The reply is over: an ear watching it listens on, on the same microphone, hands-free ([staysOpen]), or ends. */
    fun replyEnded(staysOpen: Boolean) {
        val watcher = ear?.takeIf { it.watching } ?: return
        if (staysOpen) {
            watcher.watching = false
            ui.update { it.copy(phase = VoiceStage.Listening, level = 0f, sendsOnPause = true) }
        } else {
            talking.value = false
        }
    }

    /** Ends listening: the reading stops after the block it is reading, and the microphone has closed once it has. */
    suspend fun stop() {
        talking.value = false
        stoppingListening()
        listening?.join()
        listening = null
        turnAudio?.close()
        turnAudio = null
        ear?.let { lastFloorDb = it.floorDb }
        ear = null
    }

    /** One block [heard]: the ear's word on it, and what the session is to hear of it. */
    private suspend fun hear(heard: HandsFreeEar, read: Read) {
        if (ear !== heard) return
        val chunk = read.pcm
        val watching = heard.watching
        // under a reply, the guard needs what the speaker played as the block was read: the echo follows it (D147)
        val playing = if (watching) read.playbackDb else Levels.SILENCE_DB
        // the block read while the turn was ending is part of it, and nothing more is decided
        when (heard.take(chunk, Levels.detectorDb(chunk, chunk.size), playing, talking.value)) {
            HandsFreeEar.Word.Start -> {
                // talking over the reply: it stops, kept as Stop keeps it, before the session hears the new turn
                if (watching) callbacks.onInterrupt()
                ui.update { it.copy(phase = VoiceStage.Listening, level = 0f, sendsOnPause = true) }
            }

            HandsFreeEar.Word.Discard -> {
                if (!staysOpen) talking.value = false
            }

            HandsFreeEar.Word.End, HandsFreeEar.Word.GiveUp -> {
                talking.value = false
            }

            HandsFreeEar.Word.Nothing -> {
                Unit
            }
        }
        for (block in heard.flush()) callbacks.onAppend(block)
    }

    /**
     * The ear's microphone has closed: its turn goes to the model; with none (a tap stopped it, or it gave up on noise
     * or waiting), voice mode waits for a tap.
     */
    private suspend fun earDone(heard: HandsFreeEar) {
        if (ear !== heard) return
        ear = null
        lastFloorDb = heard.floorDb
        listening?.join()
        listening = null
        val turn = heard.heard()
        if (turn != null) {
            callbacks.onTurn(turn)
            return
        }
        ui.update {
            if (it.phase == VoiceStage.Listening) {
                it.copy(phase = VoiceStage.Ready, level = 0f, sendsOnPause = false)
            } else {
                it
            }
        }
    }

    /** The microphone for a turn; null, with [limit] saying why, when it is refused, taken or at the wrong rate. */
    private suspend fun openTurnMicrophone(): SampleSource? {
        val mic = openMicrophone() ?: return null
        if (mic.rate != INPUT_RATE) {
            // the session was told the rate before the microphone opened: audio at another would play wrong
            mic.close()
            limit.value =
                Limit(
                    "This phone's microphone records at a rate voice mode can't take.",
                    LimitAction.Retry,
                    "Got it",
                    dismissible = true,
                )
            return null
        }
        return mic
    }

    /** The microphone, opened on [io], or null with [limit] saying why it couldn't be (refused, or taken by a call). */
    private suspend fun openMicrophone(): SampleSource? {
        var opened: SampleSource? = null
        var refused = false
        try {
            // no timeout: opening the microphone returns or throws, and a bound would only leak it
            withContext(io + NonCancellable) {
                try {
                    opened = microphone()
                } catch (ignored: Exception) {
                    refused = true
                }
            }
        } catch (e: CancellationException) {
            opened?.close()
            throw e
        }
        if (refused) limit.value = Limits.microphone()
        return opened
    }

    /**
     * Reads [mic] on [io] until the turn ends (the block being read then is kept) or the microphone does: each block
     * goes to [live] and to [samples], with what the speaker plays as it is read when [playback]. Started ATOMIC:
     * cancelled before it runs (the session ending), it still takes the microphone over and closes it.
     */
    @OptIn(DelicateCoroutinesApi::class)
    private fun listen(mic: SampleSource, live: VoiceSession?, samples: Channel<Read>, playback: Boolean): Job =
        scope.launch(io, start = CoroutineStart.ATOMIC) {
            try {
                mic.blocks(io) { talking.value }.collect { chunk ->
                    live?.append(chunk)
                    samples.trySend(Read(chunk, if (playback) callbacks.playbackDb() else Levels.SILENCE_DB))
                    val level = VoiceRecorder.level(chunk, chunk.size)
                    ui.update { if (it.phase == VoiceStage.Listening) it.copy(level = level) else it }
                }
            } finally {
                samples.close()
            }
        }

    companion object {
        /** The rate the session is told the person's audio comes at: the phone's microphone's (D76). */
        const val INPUT_RATE = 16_000
    }
}
