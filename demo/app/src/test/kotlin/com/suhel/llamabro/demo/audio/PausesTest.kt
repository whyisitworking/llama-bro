package com.suhel.llamabro.demo.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pause and speech detectors against upstream's own cases (b12653 ui/lib/voice/tests/pauses.test.ts and
 * speech-detector.test.ts), levels given as upstream gives them (0..1, turned into dBFS by levelToDb).
 */
class PausesTest {
    private val interval = 50

    /** Pushes [ms] worth of one 0..1 [level]; true if any of them cut. */
    private fun PauseDetector.hold(level: Double, ms: Int): Boolean {
        var cut = false
        var t = 0
        while (t < ms) {
            cut = push(Levels.levelToDb(level)) || cut
            t += interval
        }
        return cut
    }

    /** Pushes [ms] of one level in 50 ms steps; true if the last one was speech. */
    private fun SpeechDetector.hold(db: Double, ms: Double): Boolean {
        var speech = false
        var t = 0.0
        while (t < ms) {
            speech = push(db, 50)
            t += 50
        }
        return speech
    }

    @Test
    fun cutsAtAPauseAfterSpeech() {
        val d = PauseDetector(interval)
        assertFalse(d.hold(0.1, 300))
        assertFalse(d.hold(0.6, 600))
        assertFalse(d.hold(0.1, 300))
        assertTrue(d.hold(0.1, 300))
    }

    @Test
    fun doesNotCutSilenceAloneNorAShortSound() {
        val d = PauseDetector(interval)
        assertFalse(d.hold(0.1, 2000))
        assertFalse(d.hold(0.6, 100))
        assertFalse(d.hold(0.1, 1000))
    }

    @Test
    fun followsTheNoiseFloorOfTheRoom() {
        val d = PauseDetector(interval)
        // a loud room: its noise is not speech, and speech above it still counts
        assertFalse(d.hold(0.45, 500))
        assertFalse(d.hold(0.8, 600))
        assertTrue(d.hold(0.45, 600))
    }

    @Test
    fun cutsALongStretchOfSpeechAtItsNextQuietMoment() {
        val d = PauseDetector(interval, maxSegmentMs = 2000)
        assertFalse(d.hold(0.1, 100))
        assertFalse(d.hold(0.6, 2500))
        assertTrue(d.push(Levels.levelToDb(0.1)))
    }

    @Test
    fun cutsAnywayWhenNoQuietMomentComes() {
        val d = PauseDetector(interval, maxSegmentMs = 2000)
        assertFalse(d.hold(0.1, 100))
        // speech over music: the level never comes back down
        assertFalse(d.hold(0.6, 2850))
        assertTrue(d.push(Levels.levelToDb(0.6)))
        // and again for the next piece
        assertFalse(d.hold(0.6, 2950))
        assertTrue(d.push(Levels.levelToDb(0.6)))
    }

    @Test
    fun hearsSpeechAboveAQuietRoomAndNotTheRoom() {
        val d = SpeechDetector()
        assertFalse(d.hold(-60.0, 500.0))
        assertTrue(d.hold(-35.0, 300.0))
        assertTrue(d.isQuiet(-58.0))
    }

    @Test
    fun followsALoudRoom() {
        val d = SpeechDetector()
        assertFalse(d.hold(-30.0, 1000.0))
        assertTrue(d.hold(-18.0, 300.0))
    }

    @Test
    fun theFloorCreepsUpThroughALongSound() {
        val d = SpeechDetector()
        d.hold(-60.0, 200.0)
        val riseMs = (SpeechDetector.ABOVE_FLOOR_DB + 10) / SpeechDetector.FLOOR_RISE_DB_PER_S * 1000
        assertTrue(d.hold(-41.0, 300.0))
        assertFalse(d.hold(-41.0, riseMs))
    }

    @Test
    fun startsFromAKnownFloor() {
        assertTrue(SpeechDetector(floorDb = -50.0).push(-35.0, 50))
        assertFalse(SpeechDetector().push(-35.0, 50))
    }

    @Test
    fun takesAStricterThresholdWhenAsked() {
        val strict = SpeechDetector(floorDb = -50.0, speechDb = 15.0)
        assertFalse(strict.isSpeech(-40.0))
        assertTrue(strict.isSpeech(-30.0))
    }

    @Test
    fun neverHearsSpeechBelowTheAbsoluteMinimum() {
        assertFalse(SpeechDetector.isSpeechLevel(SpeechDetector.MIN_DB - 1, -120.0))
        assertTrue(SpeechDetector.isSpeechLevel(SpeechDetector.MIN_DB + 1, -120.0))
    }

    @Test
    fun turnsRmsAndLevelsIntoDbfs() {
        assertEquals(0.0, Levels.rmsToDb(1.0), 1e-9)
        assertEquals(-20.0, Levels.rmsToDb(0.1), 1e-9)
        assertTrue(Levels.rmsToDb(0.0) < -100)
        assertEquals(0.0, Levels.levelToDb(1.0), 1e-9)
        assertEquals(-30.0, Levels.levelToDb(0.5), 1e-9)
        assertEquals(-60.0, Levels.levelToDb(0.0), 1e-9)
    }

    @Test
    fun aQuietBlockReadsAsTheFloor() {
        assertEquals(Levels.FLOOR_DB, Levels.detectorDb(ShortArray(800), 800), 1e-9)
        val full = ShortArray(800) { Short.MAX_VALUE }
        assertEquals(0.0, Levels.detectorDb(full, 800), 1e-9)
    }
}
