package com.suhel.llamabro.demo.server

import com.suhel.llamabro.server.PairingRequest
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The phone's side of pairing (docs/design/server.md §6, D174): one question at a time, and silence is a no. */
class PairingApprovalsTest {
    private val mac = PairingRequest("4821", "192.168.1.12", "Mozilla/5.0 (Macintosh) Chrome/131.0")
    private val pc = PairingRequest("0937", "192.168.1.31", "Mozilla/5.0 (Windows NT 10.0) Firefox/133.0")

    @Test fun approveAndDenyAnswerTheOneShown() =
        runTest {
            val approvals = PairingApprovals()
            val first = async { approvals.approve(mac) }
            runCurrent()
            assertEquals(mac, approvals.shown.value)
            approvals.answer(mac, approved = true)
            assertTrue(first.await())
            assertNull(approvals.shown.value)

            val second = async { approvals.approve(pc) }
            runCurrent()
            approvals.answer(pc, approved = false)
            assertFalse(second.await())
        }

    @Test fun othersQueueBehindTheOneShown() =
        runTest {
            val approvals = PairingApprovals()
            val first = async { approvals.approve(mac) }
            val second = async { approvals.approve(pc) }
            runCurrent()
            assertEquals(mac, approvals.shown.value)
            // an answer for one not shown yet is not taken
            approvals.answer(pc, approved = true)
            runCurrent()
            assertTrue(second.isActive)
            approvals.answer(mac, approved = false)
            assertFalse(first.await())
            assertEquals(pc, approvals.shown.value)
            approvals.answer(pc, approved = true)
            assertTrue(second.await())
        }

    @Test fun noAnswerIsADeny() =
        runTest {
            val approvals = PairingApprovals()
            val asked = async { approvals.approve(mac) }
            val queued = async { approvals.approve(pc) }
            advanceTimeBy(PairingApprovals.EXPIRY_MILLIS - 1)
            assertEquals(mac, approvals.shown.value)
            advanceTimeBy(2)
            assertFalse(asked.await())
            // the one behind it waited as long, so it goes too
            assertFalse(queued.await())
            assertNull(approvals.shown.value)
        }

    @Test fun aCancelledQuestionLeavesTheQueue() =
        runTest {
            val approvals = PairingApprovals()
            val asked = async { approvals.approve(mac) }
            runCurrent()
            asked.cancel()
            runCurrent()
            assertNull(approvals.shown.value)
        }
}
