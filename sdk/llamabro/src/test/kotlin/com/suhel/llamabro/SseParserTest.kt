package com.suhel.llamabro

import com.suhel.llamabro.internal.SseParser
import org.junit.Assert.assertEquals
import org.junit.Test

class SseParserTest {
    @Test
    fun sseParserHandlesSplitUtf8AndEvents() {
        val stream = "data: {\"t\":\"😀漢字\"}\n\n: ping\n\ndata: {\"t\":\"é\"}\n\ndata: [DONE]\n\n".toByteArray(Charsets.UTF_8)
        val whole = SseParser().feed(stream)
        assertEquals(listOf("{\"t\":\"😀漢字\"}", "{\"t\":\"é\"}", "[DONE]"), whole)
        for (cut in 0..stream.size) {
            val p = SseParser()
            val got = p.feed(stream.copyOfRange(0, cut)) + p.feed(stream.copyOfRange(cut, stream.size))
            assertEquals("cut at $cut", whole, got)
        }
    }

    @Test
    fun byteAtATime() {
        val stream = "data: {\"t\":\"😀\"}\n\ndata: [DONE]\n\n".toByteArray(Charsets.UTF_8)
        val p = SseParser()
        val got = stream.flatMap { p.feed(byteArrayOf(it)) }
        assertEquals(listOf("{\"t\":\"😀\"}", "[DONE]"), got)
    }
}
