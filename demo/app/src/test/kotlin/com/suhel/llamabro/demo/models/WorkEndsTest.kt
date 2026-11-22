package com.suhel.llamabro.demo.models

import androidx.work.WorkInfo
import androidx.work.WorkInfo.State
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.UUID

/**
 * When a download's work ends, read from the state sequences WorkManager emits for its unique name. The device test
 * `WorkDownloadSchedulerTest` drives the same function over a real WorkManager.
 */
class WorkEndsTest {
    private val first = UUID.randomUUID()
    private val second = UUID.randomUUID()

    private fun info(id: UUID, state: State) = WorkInfo(id, state, emptySet())

    private fun ends(vararg emissions: List<WorkInfo>, seenRunning: Boolean = false): Int =
        runBlocking { workEnds(flowOf(*emissions), seenRunning).toList().size }

    @Test fun aCancelAfterARunIsOneEnd() =
        assertEquals(
            1,
            ends(
                emptyList(),
                listOf(info(first, State.ENQUEUED)),
                listOf(info(first, State.RUNNING)),
                listOf(info(first, State.CANCELLED)),
            ),
        )

    @Test fun aRetryBetweenTriesIsNotAnEnd() =
        assertEquals(
            1,
            ends(
                listOf(info(first, State.ENQUEUED)),
                listOf(info(first, State.RUNNING)),
                listOf(info(first, State.ENQUEUED)),
                listOf(info(first, State.RUNNING)),
                listOf(info(first, State.SUCCEEDED)),
            ),
        )

    @Test fun aRunFinishedBeforeTheWatchIsNotAnEnd() =
        assertEquals(0, ends(listOf(info(first, State.FAILED)), listOf(info(first, State.FAILED))))

    @Test fun anOldFinishedRunBesideANewOneEndsOnceWithTheNew() =
        assertEquals(
            1,
            ends(
                listOf(info(first, State.CANCELLED)),
                listOf(info(first, State.CANCELLED), info(second, State.ENQUEUED)),
                listOf(info(first, State.CANCELLED), info(second, State.RUNNING)),
                listOf(info(first, State.CANCELLED), info(second, State.SUCCEEDED)),
            ),
        )

    @Test fun nothingUnderTheNameIsNotAnEnd() = assertEquals(0, ends(listOf(info(first, State.RUNNING)), emptyList()))

    @Test fun workSeenRunningThatIsFinishedAtTheFirstLookEndedInBetween() {
        assertEquals(1, ends(listOf(info(first, State.SUCCEEDED)), seenRunning = true))
        assertEquals("not seen running: an old run kept under the name", 0, ends(listOf(info(first, State.SUCCEEDED))))
    }
}
