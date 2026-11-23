package com.suhel.llamabro.demo.chat

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

        private const val MILLIS_PER_SECOND = 1000.0

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
) {
    private val detector = SpeechDetector()
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
