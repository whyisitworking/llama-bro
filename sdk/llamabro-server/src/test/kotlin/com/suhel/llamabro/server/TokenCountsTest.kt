package com.suhel.llamabro.server

import org.junit.Assert.assertEquals
import org.junit.Test

/** Token counts read from the bytes as they pass, in each API's shape, however the chunks split them. */
class TokenCountsTest {
    private fun counts(streaming: Boolean, vararg chunks: String): Counts =
        TokenCounts(streaming).apply { chunks.forEach { feed(it.toByteArray()) } }.counts()

    @Test
    fun lastTimingsWinAcrossSplitChunks() {
        val counts =
            counts(
                true,
                "data: {\"timings\":{\"prompt_n\":1,\"predicted_n\":1}}\n\ndata: {\"timi",
                "ngs\":{\"prompt_n\":9,\"predicted_n\":4}}\r\n\r\n",
                "data: [DONE]\n\n",
            )
        assertEquals(Counts(9, 4), counts)
    }

    @Test
    fun responsesApiNestsUsageInTheResponse() {
        val completed =
            "event: response.completed\n" +
                "data: {\"type\":\"response.completed\"," +
                "\"response\":{\"usage\":{\"input_tokens\":3,\"output_tokens\":8}}}\n\n"
        assertEquals(Counts(3, 8), counts(true, completed))
    }

    @Test
    fun anthropicStreamGivesOutputTokens() {
        val delta = "event: message_delta\ndata: {\"type\":\"message_delta\",\"usage\":{\"output_tokens\":6}}\n\n"
        val stop = "event: message_stop\ndata: {\"type\":\"message_stop\"}\n\n"
        assertEquals(Counts(null, 6), counts(true, delta, stop))
    }

    @Test
    fun wholeJsonBodyInPieces() =
        assertEquals(Counts(2, 5), counts(false, "{\"usage\":{\"input_tok", "ens\":2,\"output_tokens\":5}}"))

    @Test
    fun noCountsIsNull() {
        assertEquals(Counts(null, null), counts(false, "{\"tokens\":[1]}"))
        assertEquals(Counts(null, null), counts(false, "not json"))
        assertEquals(Counts(null, null), counts(true, "data: {\"choices\":[]}\n\n"))
    }
}
