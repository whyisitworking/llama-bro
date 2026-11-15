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
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

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
}
