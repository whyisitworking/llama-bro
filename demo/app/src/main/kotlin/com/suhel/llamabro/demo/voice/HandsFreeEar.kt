package com.suhel.llamabro.demo.voice

import com.suhel.llamabro.demo.audio.Levels
import com.suhel.llamabro.demo.audio.MILLIS_PER_SECOND
import com.suhel.llamabro.demo.audio.SpeechDetector
import com.suhel.llamabro.demo.audio.TurnDetector
import com.suhel.llamabro.demo.audio.VoiceRecorder

/**
 * Voice mode listening by itself (D147), one microphone block at a time, with no coroutines of its own: [take] keeps
 * each block and says what it made of the turn. Until a turn starts, the last [BEFORE_MILLIS] are kept, so its first
 * word isn't lost to the time it takes to tell speech from the room. A turn the detector started is held back from the
 * session until its speech, judged against the turn's own floor (its quietest level since it started, so a noise that
 * switches on can't count the quiet before it), comes to [MIN_SPEECH_MS]: noise that started one is dropped with
 * nothing sent, and after [MAX_DISCARDS] of those in a row, or [IDLE_MS] with no turn, the ear gives up.
 * A press of the talk button ([press]) starts the turn at once and holds it until the release. The room's floor starts
 * at [floorDb] when the last ear knew it.
 */
internal class HandsFreeEar(private val blockMs: Int = BLOCK_MS, floorDb: Double? = null) {
    /** What a block made of the turn. */
    enum class Word {
        Nothing,

        /** Speech began: the turn holds what was kept before it. */
        Start,

        /** The turn ended at a pause, and is the person's: it goes to the model. */
        End,

        /** The turn ended with too little speech in it: noise, dropped. */
        Discard,

        /** Too much noise or too long a wait: the ear stops, and voice mode waits for a tap. */
        GiveUp,
    }

    private class Block(val pcm: ShortArray, val db: Double)

    // the room's first level sets its floor when no ear has heard it yet (SpeechDetector's full scale)
    private val detector = TurnDetector(blockMs, floorDb ?: 0.0)
    private val before = ArrayDeque<Block>()

    // the turn's levels, what was kept before it included, until its speech is enough to send; its floor is its own
    private val levels = mutableListOf<Double>()
    private var turnFloorDb = Double.POSITIVE_INFINITY

    // once the turn has ended, the blocks it still takes: the one being read then, not those read ahead of the steps
    private var tail = -1
    private val forSession = mutableListOf<ShortArray>()
    private var confirmed = false
    private var discards = 0
    private var idleMs = 0

    /** The turn being heard, once speech (or a press) started it. */
    var turn: PcmBuffer? = null
        private set

    /** The talk button holds the turn: a pause doesn't end it, and the release sends it. */
    var held: Boolean = false
        private set

    /** Whether a reply is playing: a turn then interrupts it. What was kept before is the other side's: it goes. */
    var watching: Boolean
        get() = detector.watching
        set(value) {
            if (value == detector.watching) return
            detector.watching = value
            before.clear()
        }

    /** The room's noise level in dBFS, as heard: where the next ear starts. */
    val floorDb: Double get() = detector.floorDb

    /**
     * One block, at [db], with the speaker playing at [playbackDb]: kept before the turn, or part of it. With [decide]
     * (the turn hasn't been told to end), the detector's word on it.
     */
    fun take(chunk: ShortArray, db: Double, playbackDb: Double = Levels.SILENCE_DB, decide: Boolean = true): Word {
        if (!keep(chunk, db)) return Word.Nothing
        if (!decide || held) return Word.Nothing
        val wasWatching = detector.watching
        if (!wasWatching && !confirmed) idleMs += blockMs
        return when (detector.push(db, playbackDb)) {
            TurnDetector.Heard.Start -> {
                // under a reply, only the speech that started it and its onset: before that is the reply's echo
                begin(if (wasWatching) detector.startRunMs / blockMs + ONSET_BLOCKS else BEFORE_BLOCKS)
                Word.Start
            }

            TurnDetector.Heard.End -> {
                tail = 1
                if (confirmed) Word.End else discard()
            }

            TurnDetector.Heard.Nothing -> {
                if (idleMs >= IDLE_MS) Word.GiveUp else Word.Nothing
            }
        }
    }

    /** Keeps [chunk]: before the turn, or in it; false once the turn has ended and has its last block. */
    private fun keep(chunk: ShortArray, db: Double): Boolean {
        val current = turn
        if (current == null) {
            before.addLast(Block(chunk, db))
            if (before.size > BEFORE_BLOCKS) before.removeFirst()
            return true
        }
        if (tail == 0) return false
        if (tail > 0) tail--
        current.add(chunk)
        if (confirmed) {
            forSession += chunk
        } else {
            levels += db
            turnFloorDb = minOf(turnFloorDb, db)
            confirmIfSpoken()
        }
        return true
    }

    /** The talk button went down: the turn starts now, with what was kept before (none of a reply's), and is sent. */
    fun press() {
        held = true
        if (turn == null) begin(if (watching) 0 else BEFORE_BLOCKS)
        confirm()
    }

    /** A tap ends the turn: whatever it heard is the person's, and is sent. */
    fun send() {
        if (turn == null) return
        confirm()
        tail = 1
    }

    /** The blocks the session is to hear now, in order: none until the turn is the person's. */
    fun flush(): List<ShortArray> = forSession.toList().also { forSession.clear() }

    /** What goes to the model once the ear has stopped: the turn, if it is the person's. */
    fun heard(): PcmBuffer? = turn?.takeIf { confirmed }

    private fun begin(keep: Int) {
        while (before.size > keep) before.removeFirst()
        val started = PcmBuffer()
        for (block in before) {
            started.add(block.pcm)
            levels += block.db
        }
        before.clear()
        turn = started
    }

    private fun confirmIfSpoken() {
        if (levels.count { SpeechDetector.isSpeechLevel(it, turnFloorDb) } * blockMs >= MIN_SPEECH_MS) confirm()
    }

    private fun confirm() {
        if (confirmed) return
        confirmed = true
        discards = 0
        idleMs = 0
        levels.clear()
        turn?.let { forSession += it.blocks }
    }

    private fun discard(): Word {
        turn = null
        tail = -1
        turnFloorDb = Double.POSITIVE_INFINITY
        levels.clear()
        before.clear()
        discards++
        return if (discards >= MAX_DISCARDS) Word.GiveUp else Word.Discard
    }

    companion object {
        /** One block of the microphone, and so one level, in milliseconds. */
        val BLOCK_MS = (MILLIS_PER_SECOND / VoiceRecorder.LEVELS_PER_SECOND).toInt()

        /** What is kept from before a turn starts: the speech that started it and a little before (D147). */
        const val BEFORE_MILLIS = 700
        val BEFORE_BLOCKS = BEFORE_MILLIS / BLOCK_MS

        /** Blocks kept from before an interruption's speech was loud enough: its first sound. */
        const val ONSET_BLOCKS = 2

        /** Speech a started turn needs to be the person's and not the room's. */
        const val MIN_SPEECH_MS = 300

        /** Turns of noise in a row after which the ear gives up. */
        const val MAX_DISCARDS = 3

        /** How long the ear waits for a turn before it gives up: two minutes. */
        const val IDLE_MS = 120_000
    }
}
