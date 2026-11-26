package com.suhel.llamabro.demo.server

import com.suhel.llamabro.server.ServerEvent
import org.junit.Assert.assertEquals
import org.junit.Test

/** Server mode's log: the requests under way, for the Server page's "N of M busy" (docs/design/server.md §3). */
class ServerLogTest {
    private val log = ServerLog { 0L }

    private fun start(id: Long) =
        log.record(ServerEvent.RequestStarted(id, "192.168.1.12", "POST", "/v1/messages", null))

    private fun end(id: Long) = log.record(ServerEvent.RequestEnded(id, 200, 1_000, 10, 20))

    @Test fun busyCountRisesAndFallsWithRequests() {
        assertEquals(0, log.busy.value)
        start(1)
        start(2)
        assertEquals(2, log.busy.value)
        // a refusal never reached the model: it takes no slot
        log.record(ServerEvent.Rejected("192.168.1.31", "/v1/models", 401, "no key"))
        assertEquals(2, log.busy.value)
        end(1)
        assertEquals(1, log.busy.value)
        // an end whose start was dropped changes nothing
        end(7)
        assertEquals(1, log.busy.value)
        end(2)
        assertEquals(0, log.busy.value)
    }

    @Test fun aStoppedServerHasNothingBusy() {
        start(1)
        log.forgetOpen()
        assertEquals(0, log.busy.value)
    }
}
