package com.suhel.llamabro.demo.audio

import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/*
 * Where dictation cuts the recording into pieces, ported from llama.cpp's web client (b12653
 * ui/lib/voice/src/speech-detector.ts, pauses.ts, levels.ts, with their constants and tests): the same rules and
 * numbers, so the demo's text keeps up with the speaker as upstream's does (D140).
 */

/**
 * Tells speech from the room it is said in. Levels are in dBFS; the noise floor follows the room (it drops to any
 * quieter level at once and creeps back up through louder ones), and a level clearly above it, and above the absolute
 * minimum, is speech. So a quiet and a loud microphone both work, and so does a room with a fan running.
 */
internal class SpeechDetector(
    /** The room's noise level in dBFS when it is known already, so speech from the first level counts. */
    floorDb: Double = 0.0,
    private val speechDb: Double = ABOVE_FLOOR_DB,
    private val quietDb: Double = QUIET_ABOVE_FLOOR_DB,
) {
    /** The room's noise level in dBFS, as heard so far; full scale until the first level, which then sets it. */
    var floorDb: Double = floorDb
        private set

    /** Whether [db] is quiet against the floor: the room, or a breath between words. */
    fun isQuiet(db: Double): Boolean = db <= floorDb + quietDb

    /** Whether [db] is speech against the floor. */
    fun isSpeech(db: Double): Boolean = isSpeechLevel(db, floorDb, speechDb)

    /** Takes the level of the newest [ms] of input, follows the floor with it, and returns whether it is speech. */
    fun push(db: Double, ms: Int): Boolean {
        floorDb = min(db, floorDb + FLOOR_RISE_DB_PER_S * ms / MILLIS_PER_SECOND)
        return isSpeech(db)
    }

    companion object {
        /** How far above the noise floor a level is speech. */
        const val ABOVE_FLOOR_DB = 9.0

        /** How fast the floor creeps back up through louder levels. */
        const val FLOOR_RISE_DB_PER_S = 2.0

        /** Quieter than this is never speech, whatever the room. */
        const val MIN_DB = -54.0

        /** Up to this far above the floor a level is quiet: the room, or a breath between words. */
        const val QUIET_ABOVE_FLOOR_DB = 5.0

        /** Whether a level is speech, given the room's noise floor (both in dBFS). */
        fun isSpeechLevel(db: Double, floorDb: Double, speechDb: Double = ABOVE_FLOOR_DB): Boolean =
            db > MIN_DB && db > floorDb + speechDb
    }
}

/**
 * Finds the pauses in speech from the live input levels, so a recording can be transcribed piece by piece while it goes
 * on, cut where the speaker breathes rather than inside a word.
 */
internal class PauseDetector(
    /** Time between two levels, as the microphone gives them. */
    private val intervalMs: Int,
    /** Silence after speech that makes a pause. */
    private val pauseMs: Int = PAUSE_MS,
    /** Speech needed before a pause counts. */
    private val minSpeechMs: Int = MIN_SPEECH_MS,
    /**
     * A piece longer than this is cut at its next quiet moment, pause or not; half as long again and it is cut where
     * it is, as with music or talk in the room no moment is ever quiet.
     */
    private val maxSegmentMs: Int = MAX_SEGMENT_MS,
    /** The room's noise level in dBFS when it is known already (as [SpeechDetector] takes it). */
    floorDb: Double = 0.0,
) {
    private val detector = SpeechDetector(floorDb)
    private var quietMs = 0
    private var segmentMs = 0
    private var speechMs = 0

    /** Whether speech was heard since the last cut. */
    val heardSpeech: Boolean get() = speechMs > 0

    /** Takes the newest level in dBFS; true when this is a place to cut the recording. */
    fun push(db: Double): Boolean {
        val loud = detector.push(db, intervalMs)
        segmentMs += intervalMs
        if (loud) {
            speechMs += intervalMs
            quietMs = 0
        }
        val spoke = speechMs >= minSpeechMs
        // no quiet moment came: the text must not wait for one that a noisy room never has
        if (spoke && segmentMs >= maxSegmentMs * OVERTIME) {
            reset()
            return true
        }
        if (loud || !detector.isQuiet(db)) return false
        quietMs += intervalMs
        val cut = spoke && (quietMs >= pauseMs || segmentMs >= maxSegmentMs)
        if (cut) reset()
        return cut
    }

    /** Starts a new piece, e.g. after one was cut elsewhere. */
    fun reset() {
        quietMs = 0
        segmentMs = 0
        speechMs = 0
    }

    companion object {
        // upstream's VOICE_PAUSE_DEFAULTS
        const val PAUSE_MS = 500
        const val MIN_SPEECH_MS = 300
        const val MAX_SEGMENT_MS = 10_000
        const val OVERTIME = 1.5

        // upstream's VOICE_PIECE: short pieces keep the text close behind the speaker
        const val PIECE_PAUSE_MS = 350
        const val PIECE_MIN_SPEECH_MS = 250
        const val PIECE_MAX_MS = 3000

        /** The detector dictation cuts its pieces with. */
        fun forPieces(intervalMs: Int): PauseDetector =
            PauseDetector(intervalMs, PIECE_PAUSE_MS, PIECE_MIN_SPEECH_MS, PIECE_MAX_MS)
    }
}

/**
 * Where a hands-free turn in voice mode starts and ends (D147), from the live input levels, with the two detectors
 * above. A turn starts once speech has gone on for [startMs] without a break: the room's steady sound never does, as
 * [SpeechDetector] takes it for the floor, and a click or a cough is too short. It ends at a pause of [endPauseMs]
 * after that (a [PauseDetector] that starts from the floor heard so far), or, as a pause never comes in a loud room, at
 * the first quiet moment after [maxTurnMs] (and half as long again at the latest). The room's floor starts at [floorDb]
 * when it is known already (the last ear's), so speech from the first level counts. While a reply plays ([watching],
 * to interrupt it by talking), the phone's own voice comes back through the microphone, so a turn starts only on
 * speech the [EchoGuard] hears as clearly louder than the reply's echo, for [interruptStartMs]. Not upstream's: at
 * b12653 its realtime conversation leaves turn detection to the client.
 */
internal class TurnDetector(
    /** Time between two levels, as the microphone gives them. */
    private val intervalMs: Int,
    floorDb: Double = 0.0,
    private val startMs: Int = START_SPEECH_MS,
    private val endPauseMs: Int = END_PAUSE_MS,
    private val maxTurnMs: Int = MAX_TURN_MS,
    private val interruptStartMs: Int = INTERRUPT_SPEECH_MS,
) {
    /** What a level made of the turn. */
    enum class Heard { Nothing, Start, End }

    private val room = SpeechDetector(floorDb)
    private val echo = EchoGuard(intervalMs)
    private var speechMs = 0
    private var turn: PauseDetector? = null

    /** Whether a reply is playing: a turn then interrupts it, and must be clearly louder than its echo. */
    var watching: Boolean = false
        set(value) {
            field = value
            speechMs = 0
            echo.reset()
        }

    /** Whether the turn has started and not ended. */
    val inTurn: Boolean get() = turn != null

    /** The room's noise level in dBFS, as heard so far: where the next listener starts. */
    val floorDb: Double get() = room.floorDb

    /** The unbroken speech that started the last turn, in milliseconds. */
    var startRunMs: Int = 0
        private set

    /**
     * Takes the newest level in dBFS, and while [watching] the level the speaker plays ([playbackDb]): [Heard.Start]
     * when the turn starts with it, [Heard.End] when it ends.
     */
    fun push(db: Double, playbackDb: Double = Levels.SILENCE_DB): Heard {
        val speech = room.push(db, intervalMs)
        turn?.let { pauses ->
            if (!pauses.push(db)) return Heard.Nothing
            turn = null
            speechMs = 0
            return Heard.End
        }
        // the guard learns from every level under a reply, speech or not
        val heard = if (watching) echo.isOver(db, playbackDb) && speech else speech
        speechMs = if (heard) speechMs + intervalMs else 0
        if (speechMs < if (watching) interruptStartMs else startMs) return Heard.Nothing
        startRunMs = speechMs
        watching = false
        // the speech that started it was heard already, so the pause may come at once
        turn = PauseDetector(intervalMs, endPauseMs, minSpeechMs = 0, maxSegmentMs = maxTurnMs, floorDb = room.floorDb)
        return Heard.Start
    }

    companion object {
        /** Speech without a break that starts a turn: a short word, not a click (upstream's piece minimum). */
        const val START_SPEECH_MS = PauseDetector.PIECE_MIN_SPEECH_MS

        /** Quiet after speech that ends a turn: longer than a breath between sentences, shorter than waiting. */
        const val END_PAUSE_MS = 900

        /** A turn this long ends at its next quiet moment, pause or not; half as long again, it ends where it is. */
        const val MAX_TURN_MS = 60_000

        /** Speech without a break that interrupts a reply: longer than a start, so a word of echo doesn't. */
        const val INTERRUPT_SPEECH_MS = 400
    }
}

/**
 * Tells the person's voice from the reply's own echo while a reply plays (D147), against what the speaker plays rather
 * than against the room: the echo rises and falls with the reply, so a floor that follows the room's quietest moments
 * sits far below it. For the first [warmUpMs] of audible playback it only learns how loud the reply comes back through
 * the microphone (the coupling: microphone level less playback level, at its loudest); after that, a level is the
 * person's when it is [marginDb] above the playback, plus that coupling. The playback taken is the loudest of the last
 * [ECHO_TAIL_MS], as the echo comes back a little after the speaker plays it and rings on in the room.
 */
internal class EchoGuard(
    private val intervalMs: Int,
    private val warmUpMs: Int = ECHO_WARM_UP_MS,
    private val marginDb: Double = ECHO_MARGIN_DB,
) {
    private val recent = ArrayDeque<Double>()
    private var learntMs = 0
    private var couplingDb: Double? = null

    /** Starts over, for a new reply. */
    fun reset() {
        recent.clear()
        learntMs = 0
        couplingDb = null
    }

    /** Takes the newest microphone level [db] and the speaker's, [playbackDb]: true when it is the person's voice. */
    fun isOver(db: Double, playbackDb: Double): Boolean {
        recent.addLast(playbackDb)
        if (recent.size > ECHO_TAIL_MS / intervalMs) recent.removeFirst()
        val reference = recent.max()
        if (learntMs < warmUpMs) {
            // silence before the reply's first words teaches nothing, so the warm-up counts only what was heard
            if (reference > AUDIBLE_DB) {
                learntMs += intervalMs
                couplingDb = max(couplingDb ?: Double.NEGATIVE_INFINITY, min(db - reference, MAX_COUPLING_DB))
            }
            return false
        }
        return db > reference + (couplingDb ?: MAX_COUPLING_DB) + marginDb
    }

    companion object {
        /** Audible playback it learns from before anything interrupts: also echo cancellation's own warm-up. */
        const val ECHO_WARM_UP_MS = 600

        /** How far above the reply's echo the person's voice must be. */
        const val ECHO_MARGIN_DB = 10.0

        /** How long after the speaker plays a level its echo is still expected. */
        const val ECHO_TAIL_MS = 300

        /** The loudest the echo is taken to be, against the playback: speech during the warm-up teaches no more. */
        const val MAX_COUPLING_DB = 0.0

        /** Playback quieter than this is silence, which teaches nothing. */
        const val AUDIBLE_DB = -45.0
    }
}

/** dBFS helpers (upstream's levels.ts). */
internal object Levels {
    /** Quieter than anything a microphone gives, for a block of digital silence. */
    const val SILENCE_DB = -120.0

    /** The quietest level the 0..1 levels show; a level of 0 reads as this. */
    const val FLOOR_DB = -60.0

    private const val DB_PER_DECADE = 20.0

    /** Root mean square of [n] samples of [buffer], against full scale (0 to 1). */
    fun rms(buffer: ShortArray, n: Int): Double {
        if (n <= 0) return 0.0
        var sum = 0.0
        for (i in 0 until n) {
            val v = buffer[i].toDouble() / Short.MAX_VALUE
            sum += v * v
        }
        return sqrt(sum / n)
    }

    /** RMS to dBFS, digital silence as [SILENCE_DB] rather than minus infinity. */
    fun rmsToDb(value: Double): Double = if (value > 0) max(SILENCE_DB, DB_PER_DECADE * log10(value)) else SILENCE_DB

    /**
     * A block's dBFS as the pause detector hears it: upstream hands it 0..1 levels, which bottom out at [FLOOR_DB], so
     * a quieter block reads as the floor.
     */
    fun detectorDb(buffer: ShortArray, n: Int): Double = max(FLOOR_DB, rmsToDb(rms(buffer, n)))

    /** A 0..1 level back to dBFS; 0 reads as the floor. */
    fun levelToDb(level: Double): Double = (1 - level.coerceIn(0.0, 1.0)) * FLOOR_DB
}
