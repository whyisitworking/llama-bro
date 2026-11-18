package com.suhel.llamabro.demo.designsystem

import com.suhel.llamabro.demo.designsystem.components.lastSentenceStart
import org.junit.Assert.assertEquals
import org.junit.Test

/** The voice caption's split (D100): the last sentence in ink, the ones before it muted. */
class VoiceCaptionTest {
    private fun last(text: String) = text.substring(lastSentenceStart(text))

    @Test
    fun theLastSentenceIsTheOneBeingSpoken() {
        assertEquals("He reads it, and", last("The keeper finds a bottle. He reads it, and"))
        assertEquals("He reads it.", last("The keeper finds a bottle. He reads it."))
        assertEquals("Really?", last("Is it true! Really?"))
    }

    @Test
    fun oneSentenceIsAllInInk() {
        assertEquals(0, lastSentenceStart("The keeper finds a bottle"))
        assertEquals(0, lastSentenceStart("The keeper finds a bottle."))
        assertEquals(0, lastSentenceStart(""))
    }

    @Test
    fun aDotInsideAWordIsNoSentenceEnd() {
        assertEquals("It costs 3.50 now.", last("Hello there. It costs 3.50 now."))
    }
}
