package com.suhel.llamabro.hexagon

import org.junit.Assert.assertEquals
import org.junit.Test

/** The environment the NPU backend starts with (F156: polling keeps the host core awake while the NPU works). */
class HexagonEnvTest {
    @Test
    fun pollingOnSetsUpstreamsSwitch() {
        assertEquals(
            mapOf("GGML_HEXAGON_LIBHTP_DIR" to "/htp", "GGML_HEXAGON_OPPOLL" to "1"),
            hexagonEnv("/htp", npuPolling = true),
        )
    }

    @Test
    fun pollingOffLeavesUpstreamsDefault() {
        assertEquals(mapOf("GGML_HEXAGON_LIBHTP_DIR" to "/htp"), hexagonEnv("/htp", npuPolling = false))
    }
}
