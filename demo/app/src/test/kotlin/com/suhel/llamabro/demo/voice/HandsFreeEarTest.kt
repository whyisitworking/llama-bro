package com.suhel.llamabro.demo.voice

import com.suhel.llamabro.demo.audio.EchoGuard
import com.suhel.llamabro.demo.audio.TurnDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The hands-free ear (D147) on synthetic levels: each block's samples are a marker, its level is given apart. */
class HandsFreeEarTest {
    private val block = HandsFreeEar.BLOCK_MS

    /** [ms] of blocks marked [marker] at [db] (the speaker at [playbackDb]); every word the ear said, Nothing left out. */
    private fun HandsFreeEar.hear(
        marker: Short,
        db: Double,
        ms: Int,
        playbackDb: Double = -120.0,
    ): List<HandsFreeEar.Word> =
        (0 until ms step block)
            .map { take(ShortArray(4) { marker }, db, playbackDb) }
            .filter { it != HandsFreeEar.Word.Nothing }

    private fun List<ShortArray>.markers(): List<Short> = map { it.first() }

    @Test fun anInterruptionKeepsOnlyItsSpeechAndItsOnsetNotTheReplyBeforeIt() {
        val ear = HandsFreeEar(floorDb = -50.0)
        ear.watching = true
        // the reply's echo, 10 dB under what plays: the warm-up, then a while longer
        assertEquals(emptyList<HandsFreeEar.Word>(), ear.hear(ECHO, -30.0, EchoGuard.ECHO_WARM_UP_MS + 1_000, -20.0))
        val words = ear.hear(SPEECH, -5.0, TurnDetector.INTERRUPT_SPEECH_MS, -20.0)
        assertEquals(listOf(HandsFreeEar.Word.Start), words)
        // what went before the speech is two blocks of onset at most, not the 700 ms kept before a turn
        val sent = ear.turn!!.blocks.markers()
        assertEquals(TurnDetector.INTERRUPT_SPEECH_MS / block, sent.count { it == SPEECH })
        assertTrue("$sent", sent.count { it == ECHO } <= HandsFreeEar.ONSET_BLOCKS)
    }

    @Test fun whatWasKeptBeforeGoesWhenAReplyStartsOrEnds() {
        val ear = HandsFreeEar(floorDb = -50.0)
        ear.hear(ROOM, -50.0, 300)
        ear.watching = true
        ear.hear(ECHO, -30.0, 300, -20.0)
        ear.watching = false
        assertEquals(listOf(HandsFreeEar.Word.Start), ear.hear(SPEECH, -15.0, TurnDetector.START_SPEECH_MS))
        assertEquals(
            listOf(SPEECH),
            ear.turn!!
                .blocks
                .markers()
                .distinct(),
        )
    }

    @Test fun speechInTheFirstBlockAfterAReplyStartsATurnWithItsFirstWords() {
        // the floor the last ear heard: the first block of speech counts
        val ear = HandsFreeEar(floorDb = -50.0)
        assertEquals(listOf(HandsFreeEar.Word.Start), ear.hear(SPEECH, -15.0, TurnDetector.START_SPEECH_MS))
        assertEquals(TurnDetector.START_SPEECH_MS / block, ear.turn!!.blocks.size)
        // without it, the first level is taken for the room, and the same speech starts nothing
        val fresh = HandsFreeEar()
        assertEquals(emptyList<HandsFreeEar.Word>(), fresh.hear(SPEECH, -15.0, TurnDetector.START_SPEECH_MS))
    }

    @Test fun aTurnWithTooLittleSpeechIsDroppedAndTheSessionHearsNothing() {
        val ear = HandsFreeEar(floorDb = -50.0)
        ear.hear(ROOM, -50.0, 500)
        // a burst just long enough to start a turn, under the speech a turn needs, then the pause
        assertEquals(listOf(HandsFreeEar.Word.Start), ear.hear(SPEECH, -15.0, TurnDetector.START_SPEECH_MS))
        val words = ear.hear(ROOM, -50.0, TurnDetector.END_PAUSE_MS)
        assertEquals(listOf(HandsFreeEar.Word.Discard), words)
        assertEquals(emptyList<ShortArray>(), ear.flush())
        assertNull(ear.heard())
    }

    @Test fun aNoiseThatSwitchesOnIsJudgedAgainstItselfNotTheQuietBeforeIt() {
        val ear = HandsFreeEar(floorDb = -50.0)
        ear.hear(ROOM, -50.0, 500)
        // a fan comes on, steady and far above the room the floor knew
        assertEquals(listOf(HandsFreeEar.Word.Start), ear.hear(NOISE, -25.0, TurnDetector.START_SPEECH_MS))
        ear.hear(NOISE, -25.0, 3_000)
        assertEquals(emptyList<ShortArray>(), ear.flush())
    }

    @Test fun aTurnWithEnoughSpeechGoesToTheSessionAndEndsAtThePause() {
        val ear = HandsFreeEar(floorDb = -50.0)
        ear.hear(ROOM, -50.0, 300)
        ear.hear(SPEECH, -15.0, 600)
        assertEquals(listOf(HandsFreeEar.Word.End), ear.hear(ROOM, -50.0, TurnDetector.END_PAUSE_MS))
        assertTrue(ear.flush().isNotEmpty())
        val turn = ear.heard()!!
        // read ahead of the steps after the end: one block more at most
        ear.take(ShortArray(4) { ROOM }, -50.0, decide = false)
        ear.take(ShortArray(4) { ROOM }, -50.0, decide = false)
        assertEquals(300 / block + 600 / block + TurnDetector.END_PAUSE_MS / block + 1, turn.blocks.size)
    }

    @Test fun threeTurnsOfNoiseInARowGiveUp() {
        val ear = HandsFreeEar(floorDb = -50.0)
        val words =
            (1..HandsFreeEar.MAX_DISCARDS).flatMap {
                ear.hear(ROOM, -50.0, 500) +
                    ear.hear(SPEECH, -15.0, TurnDetector.START_SPEECH_MS) +
                    ear.hear(ROOM, -50.0, TurnDetector.END_PAUSE_MS)
            }
        assertEquals(HandsFreeEar.Word.GiveUp, words.last())
        assertEquals(HandsFreeEar.MAX_DISCARDS - 1, words.count { it == HandsFreeEar.Word.Discard })
    }

    @Test fun twoMinutesWithNoTurnGiveUp() {
        val ear = HandsFreeEar(floorDb = -50.0)
        assertEquals(emptyList<HandsFreeEar.Word>(), ear.hear(ROOM, -50.0, HandsFreeEar.IDLE_MS - block))
        assertEquals(listOf(HandsFreeEar.Word.GiveUp), ear.hear(ROOM, -50.0, block))
    }

    @Test fun underAReplyTheWaitIsNotCounted() {
        val ear = HandsFreeEar(floorDb = -50.0)
        ear.watching = true
        assertEquals(emptyList<HandsFreeEar.Word>(), ear.hear(ROOM, -50.0, HandsFreeEar.IDLE_MS, -20.0))
    }

    @Test fun aPressStartsTheTurnAtOnceKeepsWhatCameBeforeAndAPauseDoesNotEndIt() {
        val ear = HandsFreeEar(floorDb = -50.0)
        ear.hear(ROOM, -50.0, 200)
        ear.press()
        // a quiet, short "yes", too short to start a turn by itself, then a long pause while the button is held
        assertEquals(emptyList<HandsFreeEar.Word>(), ear.hear(SPEECH, -40.0, 200))
        assertEquals(emptyList<HandsFreeEar.Word>(), ear.hear(ROOM, -50.0, 2 * TurnDetector.END_PAUSE_MS))
        val sent = ear.flush().markers()
        assertEquals(listOf(ROOM, ROOM, ROOM, ROOM, SPEECH, SPEECH, SPEECH, SPEECH), sent.take(8))
        assertEquals(sent, ear.heard()!!.blocks.markers())
    }

    private companion object {
        const val ROOM: Short = 1
        const val ECHO: Short = 2
        const val SPEECH: Short = 3
        const val NOISE: Short = 4
    }
}
