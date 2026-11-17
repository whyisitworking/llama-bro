package com.suhel.llamabro.hub.internal

import com.suhel.llamabro.DownloadPhase
import com.suhel.llamabro.DownloadProgress
import com.suhel.llamabro.hub.HubException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class TransfersTest {
    private fun report(phase: DownloadPhase) = DownloadProgress(phase, 0, 1, null, 0, 1, null, null)

    @Test fun twoCollectorsShareJobAndReplayTerminalProgress() =
        runTest {
            val transfers = Transfers(backgroundScope)
            val release = CompletableDeferred<Unit>()
            var starts = 0
            val final = report(DownloadPhase.Complete)
            val flow =
                transfers.join("model|mmproj=false|draft=none") { emit ->
                    starts++
                    emit(report(DownloadPhase.Resolving))
                    release.await()
                    emit(final)
                }
            val first = async { flow.toList() }
            runCurrent()
            val second = async { flow.toList() }
            runCurrent()
            assertEquals(1, starts)
            release.complete(Unit)
            runCurrent()
            assertSame(final, first.await().last())
            assertSame(final, second.await().last())
        }

    @Test fun cancellationFromInsideFailsCollectorsAndFrees() =
        runTest {
            val transfers = Transfers(backgroundScope)
            var starts = 0
            val flow =
                transfers.join("model") { emit ->
                    starts++
                    emit(report(DownloadPhase.Resolving))
                    if (starts == 1) throw CancellationException("token provider timed out")
                    emit(report(DownloadPhase.Complete))
                }
            val error = runCatching { flow.toList() }.exceptionOrNull()
            assertTrue("collectors see a failure, not a hang: $error", error is HubException.Network)
            assertEquals(DownloadPhase.Complete, flow.toList().last().phase) // the key is free for a fresh transfer
            assertEquals(2, starts)
        }

    @Test fun failureInstanceReachesEveryCollector() =
        runTest {
            val transfers = Transfers(backgroundScope)
            val release = CompletableDeferred<Unit>()
            val error = HubException.Network("offline", null, true)
            val flow =
                transfers.join("model") { emit ->
                    emit(report(DownloadPhase.Resolving))
                    release.await()
                    throw error
                }
            val first = async { runCatching { flow.toList() }.exceptionOrNull() }
            val second = async { runCatching { flow.toList() }.exceptionOrNull() }
            runCurrent()
            release.complete(Unit)
            runCurrent()
            assertSame(error, first.await())
            assertSame(error, second.await())
        }

    @Test fun lastCollectorLeavingCancelsTransfer() =
        runTest {
            val transfers = Transfers(backgroundScope)
            var cancelled = false
            var idleCalls = 0
            val flow =
                transfers.join("model", onIdle = { idleCalls++ }) { emit ->
                    try {
                        emit(report(DownloadPhase.Downloading))
                        awaitCancellation()
                    } finally {
                        cancelled = true
                    }
                }
            val collector = async { flow.first() }
            runCurrent()
            assertEquals(DownloadPhase.Downloading, collector.await().phase)
            runCurrent()
            assertTrue(cancelled)
            assertEquals(1, idleCalls)
        }

    @Test fun aProgrammingErrorPassesThroughAsItIs() =
        runTest {
            val transfers = Transfers(backgroundScope)
            val bug = IllegalArgumentException("a parse bug")
            val flow = transfers.join("model") { throw bug }
            val error = runCatching { flow.toList() }.exceptionOrNull()
            assertSame("a bug is not a flaky network for a worker to retry", bug, error)
        }

    @Test fun anIOExceptionIsARetryableNetworkFailure() =
        runTest {
            val transfers = Transfers(backgroundScope)
            val flow = transfers.join("model") { throw IOException("connection reset") }
            val error = runCatching { flow.toList() }.exceptionOrNull()
            assertTrue("$error", error is HubException.Network && error.retryable)
        }

    @Test fun cancelStopsTheTransferAndEndsItsCollectorsWithTheError() =
        runTest {
            val transfers = Transfers(backgroundScope)
            var running = false
            val flow =
                transfers.join("repo:Q4_K_M") { emit ->
                    running = true
                    try {
                        emit(report(DownloadPhase.Downloading))
                        awaitCancellation()
                    } finally {
                        running = false
                    }
                }
            val other = transfers.join("other") { awaitCancellation() }
            val collector = async { runCatching { flow.toList() }.exceptionOrNull() }
            val bystander = async { runCatching { other.toList() }.exceptionOrNull() }
            runCurrent()
            assertTrue(running)
            val error = HubException.Network("deleted", null, false)
            transfers.cancel(error) { it == "repo:Q4_K_M" }
            assertFalse("joined: the transfer wrote its last byte before cancel returned", running)
            runCurrent()
            assertSame(error, collector.await())
            assertTrue("another key runs on", bystander.isActive)
            bystander.cancel()
        }

    @Test fun cancelWinsOverTheFailureTheStopCausesInside() =
        runTest {
            val transfers = Transfers(backgroundScope)
            val flow =
                transfers.join("repo:Q4_K_M") { emit ->
                    emit(report(DownloadPhase.Downloading))
                    try {
                        awaitCancellation()
                    } catch (_: CancellationException) {
                        // the connection closed under a blocked read, on the last try: a retryable network failure
                        throw HubException.Network("connection closed", IOException("closed"), true)
                    }
                }
            val collector = async { runCatching { flow.toList() }.exceptionOrNull() }
            runCurrent()
            val stop = HubException.Network("deleted", null, false)
            transfers.cancel(stop) { true }
            runCurrent()
            assertSame(stop, collector.await())
        }
}
