package com.suhel.llamabro.demo.ui.models

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.paging.CombinedLoadStates
import androidx.paging.LoadState
import androidx.paging.PagingData
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import com.suhel.llamabro.demo.models.HubSearch
import com.suhel.llamabro.demo.models.offline
import com.suhel.llamabro.hub.HubException
import kotlinx.coroutines.flow.flowOf

/**
 * Hugging Face's part of search mode from the number of paged rows the page has ([rows], already without the local
 * matches) and the Pager's [states]: the first page's fetch (the mediator's refresh) is [HubAnswer.Loading] with what
 * is stored; a failed one keeps stored rows, else says offline or failed; a failed next page is marked on the rows;
 * and no rows is "no matches" only when the local part lists nothing either, since a Hub row the local part lists is
 * left out of [rows] (an approximation: the Hub's own empty answer beside local matches says nothing, as an answer
 * the local part emptied does). On a phone where "Fits this phone" fits nothing ([nothingFits]) it says so. A refresh
 * that ended having stored rows ([refreshStored], through [PendingStored]) with none in the list yet is still
 * [HubAnswer.Loading]: Room's reload of them comes a moment after the Pager hears the refresh ended, and "no matches"
 * must not flash meanwhile.
 */
internal fun hubAnswer(
    query: String,
    rows: Int,
    states: CombinedLoadStates,
    localMatches: Int,
    nothingFits: Boolean = false,
    refreshStored: Int? = null,
): HubAnswer {
    if (query.trim().length < ModelsViewModel.MIN_QUERY) return HubAnswer.Idle
    // the Hub isn't asked: no rows and no load state say anything, the phone's cap does
    if (nothingFits) return HubAnswer.NothingFits
    val fetch = states.mediator?.refresh
    val failure = (fetch as? LoadState.Error)?.error
    return when {
        fetch == null || fetch is LoadState.Loading || states.source.refresh is LoadState.Loading -> {
            HubAnswer.Loading(rows)
        }

        rows > 0 -> {
            // a next page that failed (the mediator's fetch, or Room's read) says so under the rows; one loading
            // shows nothing, so the list doesn't move
            val more = states.mediator?.append is LoadState.Error || states.source.append is LoadState.Error
            HubAnswer.Rows(rows, moreFailed = more)
        }

        failure != null -> {
            failed(failure)
        }

        localMatches > 0 -> {
            HubAnswer.Rows(0)
        }

        (refreshStored ?: 0) > 0 -> {
            HubAnswer.Loading(rows)
        }

        else -> {
            HubAnswer.None
        }
    }
}

/** A first page that failed with no rows to keep: offline, or a refusal that says whether trying again helps. */
private fun failed(failure: Throwable): HubAnswer =
    when {
        failure is HubException && failure.offline -> HubAnswer.Offline
        failure is HubException -> HubAnswer.Failed(failure.retryable)
        else -> HubAnswer.Failed(retryable = false)
    }

/**
 * A first-page refresh's stored count ([HubSearch.refreshStored]) as [hubAnswer] may use it: only until the Pager's
 * source has loaded since the refresh ended. Room's reload of what the refresh stored comes a moment after the Pager
 * hears it ended; once a load has come and gone with no rows, that is the answer (none of the stored rows read, say),
 * not a reload still on its way, which would otherwise say "Loading" for good. Fed the load states the page draws.
 */
internal class PendingStored {
    private var reloading = false
    private var reloaded = false

    fun of(stored: Int?, states: CombinedLoadStates): Int? {
        if (stored == null || states.mediator?.refresh is LoadState.Loading) {
            // no refresh has ended, or a new one is under way: the reload of what it stores is still to come
            reloading = false
            reloaded = false
        } else if (states.source.refresh is LoadState.Loading) {
            reloading = true
        } else if (reloading) {
            reloaded = true
        }
        return stored.takeUnless { reloaded }
    }
}

/** Fixed Hugging Face rows, all loaded: a preview's or a test's, where no Pager runs. Shown from the first frame. */
@Composable
internal fun rememberHubRows(rows: List<ModelEntry>): LazyPagingItems<ModelEntry> =
    remember(rows) { flowOf(PagingData.from(rows)) }.collectAsLazyPagingItems()
