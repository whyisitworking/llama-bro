package com.suhel.llamabro.demo.voice

import org.junit.Assert.assertEquals
import org.junit.Test

/** Transcript cleaning and joining against upstream's own cases (b12653 ui/lib/voice/tests/transcript.test.ts). */
class TranscriptsTest {
    @Test
    fun leavesPlainTextAsItIs() {
        assertEquals(" Hello there. ", Transcripts.clean(" Hello there. "))
        assertEquals("", Transcripts.clean(""))
        assertEquals("[laughs] language matters", Transcripts.clean("[laughs] language matters"))
    }

    @Test
    fun takesOffQwen3AsrsLanguageAndTag() {
        assertEquals("Bonjour à tous.", Transcripts.clean("language French<asr_text>Bonjour à tous."))
        assertEquals("Hello.", Transcripts.clean("<asr_text>Hello.</asr_text>"))
        assertEquals("", Transcripts.clean("language None<asr_text>"))
        repeat(3) { assertEquals("Hi.", Transcripts.clean("language English<asr_text>Hi.")) }
    }

    @Test
    fun joinsTheWordsOfVibeVoiceSegments() {
        val segments =
            """[{"Content":"Bonjour, ça va ?","End":3.1,"Speaker":0,"Start":0},""" +
                """{"Content":" Oui, \"très\" bien.","End":5.4,"Speaker":1,"Start":3.1}]"""
        assertEquals("Bonjour, ça va ? Oui, \"très\" bien.", Transcripts.clean(segments))
        assertEquals("", Transcripts.clean("""[{"Start":0.0,"End":1.0,"Speaker":0,"Content":""}]"""))
    }

    @Test
    fun showsOnlyTheWordsOfSegmentsStillStreaming() {
        assertEquals("", Transcripts.clean("["))
        assertEquals("", Transcripts.clean("""[{"Start":0.0,"End":3.1,"Spe"""))
        assertEquals("Bonjour, ça", Transcripts.clean("""[{"Start":0.0,"End":3.1,"Speaker":0,"Content":"Bonjour, ça"""))
        assertEquals("Line one", Transcripts.clean("""[{"Start":0,"Content":"Line one\"""))
        assertEquals("caf", Transcripts.clean("""[{"Start":0,"Content":"caf\u00"""))
        assertEquals("café oui", Transcripts.clean("""[{"Start":0,"Content":"café"},{"Start":1,"Content":"oui"""))
    }

    @Test
    fun joinsPiecesWithOneSpace() {
        assertEquals("one", Transcripts.join("", "one"))
        assertEquals("one", Transcripts.join("one", ""))
        assertEquals("one two", Transcripts.join("one", "two"))
        assertEquals("one two", Transcripts.join("one ", "two"))
        assertEquals("one two", Transcripts.join("one", " two"))
    }

    @Test
    fun insertsAfterTypedTextWithTheRightSpace() {
        assertEquals("Remind me", Transcripts.insert("", " Remind me "))
        assertEquals("Hi Remind me", Transcripts.insert("Hi", "Remind me"))
        assertEquals("Hi Remind me", Transcripts.insert("Hi ", "Remind me"))
        assertEquals("Hi, and then", Transcripts.insert("Hi", ", and then"))
        assertEquals("Hi", Transcripts.insert("Hi", "  "))
    }
}
