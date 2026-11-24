package com.suhel.llamabro.demo.ui.models

import androidx.paging.CombinedLoadStates
import androidx.paging.LoadState
import androidx.paging.LoadStates
import com.suhel.llamabro.demo.models.TestHub
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Hugging Face's part of search mode from the Pager's rows and load states (models-search design §1): the stored rows
 * while the first page is fetched, offline first on a failure, and one line when there is nothing to show.
 */
class HubAnswersTest {
    private val testHub = TestHub()

    @After fun stop() = testHub.close()

    /** One stored row loaded. */
    private val stored = 1
    private val idle = LoadState.NotLoading(endOfPaginationReached = false)
    private val sourceDone = LoadStates(idle, LoadState.NotLoading(true), LoadState.NotLoading(true))

    private fun states(fetch: LoadState?, source: LoadStates = sourceDone): CombinedLoadStates {
        val mediator = fetch?.let { LoadStates(it, LoadState.NotLoading(true), idle) }
        return CombinedLoadStates(fetch ?: source.refresh, source.prepend, source.append, source, mediator)
    }

    @Test fun underTwoCharactersTheHubIsNotAsked() =
        assertEquals(HubAnswer.Idle, hubAnswer("q", stored, states(LoadState.Loading), localMatches = 0))

    @Test fun theStoredRowsShowWhileTheFirstPageIsFetched() {
        assertEquals(HubAnswer.Loading(stored), hubAnswer("phi", stored, states(LoadState.Loading), 0))
        assertEquals("before the Pager has said anything", HubAnswer.Loading(0), hubAnswer("phi", 0, states(null), 0))
        val reading = LoadStates(LoadState.Loading, idle, idle)
        assertEquals(HubAnswer.Loading(0), hubAnswer("phi", 0, states(idle, reading), 0))
    }

    @Test fun offlineWithStoredRowsKeepsThemAndWithNoneSaysOffline() {
        val offline = LoadState.Error(testHub.offlineFailure())
        assertEquals("offline first", HubAnswer.Rows(stored), hubAnswer("phi", stored, states(offline), 0))
        assertEquals(HubAnswer.Offline, hubAnswer("phi", 0, states(offline), 0))
    }

    @Test fun aRefusalOffersRetryWhenItHelps() {
        val busy = LoadState.Error(testHub.busyFailure())
        assertEquals(HubAnswer.Failed(retryable = true), hubAnswer("phi", 0, states(busy), 0))
        val other = LoadState.Error(IllegalStateException("bad answer"))
        assertEquals(HubAnswer.Failed(retryable = false), hubAnswer("phi", 0, states(other), 0))
    }

    @Test fun noRowsIsNoMatchesUnlessTheLocalPartListsSome() {
        assertEquals(HubAnswer.None, hubAnswer("zzzz", 0, states(idle), localMatches = 0))
        assertEquals(
            "the Hub's rows were all listed locally: nothing is said",
            HubAnswer.Rows(0),
            hubAnswer("phi", 0, states(idle), localMatches = 1),
        )
        assertEquals(HubAnswer.Rows(stored), hubAnswer("phi", stored, states(idle), 0))
    }

    @Test fun aRefreshThatStoredRowsIsLoadingUntilRoomBringsThem() {
        // the refresh ended and Room's reload of what it stored is still coming: no rows yet, and no "no matches"
        assertEquals(HubAnswer.Loading(0), hubAnswer("phi", 0, states(idle), 0, refreshStored = 3))
        assertEquals(HubAnswer.Rows(3), hubAnswer("phi", 3, states(idle), 0, refreshStored = 3))
        assertEquals("the Hub had none", HubAnswer.None, hubAnswer("zzzz", 0, states(idle), 0, refreshStored = 0))
    }

    @Test fun aStoredCountHoldsOnlyUntilRoomsReloadHasCome() {
        val pending = PendingStored()
        val reading = LoadStates(LoadState.Loading, idle, idle)
        // the refresh ended: its count holds while Room's reload is on its way, and while it reads
        assertEquals(3, pending.of(3, states(idle)))
        assertEquals(3, pending.of(3, states(idle, reading)))
        // the reload came with no rows (none of the stored ones read): no matches, not Loading for good
        assertNull(pending.of(3, states(idle)))
        assertEquals(HubAnswer.None, hubAnswer("phi", 0, states(idle), 0, refreshStored = pending.of(3, states(idle))))
        // a new refresh: its count holds again until its own reload has come
        assertEquals(3, pending.of(3, states(LoadState.Loading)))
        assertEquals(3, pending.of(3, states(idle)))
    }

    @Test fun aFailedNextPageIsMarkedOnTheRowsAndOneLoadingSaysNothing() {
        val failed = LoadState.Error(testHub.offlineFailure())
        val fetchFailed = CombinedLoadStates(idle, idle, failed, sourceDone, LoadStates(idle, idle, failed))
        assertEquals(HubAnswer.Rows(stored, moreFailed = true), hubAnswer("phi", stored, fetchFailed, 0))
        val loading =
            CombinedLoadStates(idle, idle, LoadState.Loading, sourceDone, LoadStates(idle, idle, LoadState.Loading))
        assertEquals(HubAnswer.Rows(stored), hubAnswer("phi", stored, loading, 0))
    }
}
