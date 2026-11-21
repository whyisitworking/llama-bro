package com.suhel.llamabro.demo.ui.models

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.paging.CombinedLoadStates
import androidx.paging.LoadState
import androidx.paging.PagingData
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import com.suhel.llamabro.demo.models.offline
import com.suhel.llamabro.hub.HubException
import kotlinx.coroutines.flow.flowOf

/**
 * Hugging Face's part of search mode from the number of paged rows the page has ([rows], already without the local
 * matches) and the Pager's [states]: the first page's fetch (the mediator's refresh) is [HubAnswer.Loading] with what
 * is stored; a failed one keeps stored rows, else says offline or failed; a failed next page is marked on the rows;
 * and no rows is "no matches" only when the local part lists nothing either, since a Hub row the local part lists is
 * left out of [rows] (an approximation: the Hub's own empty answer beside local matches says nothing, as an answer
 * the local part emptied does). On a phone where "Fits this phone" fits nothing ([nothingFits]) it says so.
 */
internal fun hubAnswer(
    query: String,
    rows: Int,
    states: CombinedLoadStates,
    localMatches: Int,
    nothingFits: Boolean = false,
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

        failure is HubException && failure.offline -> {
            HubAnswer.Offline
        }

        failure is HubException -> {
            HubAnswer.Failed(failure.retryable)
        }

        failure != null -> {
            HubAnswer.Failed(retryable = false)
        }

        localMatches > 0 -> {
            HubAnswer.Rows(0)
        }

        else -> {
            HubAnswer.None
        }
    }
}

/** Fixed Hugging Face rows, all loaded: a preview's or a test's, where no Pager runs. Shown from the first frame. */
@Composable
internal fun rememberHubRows(rows: List<ModelEntry>): LazyPagingItems<ModelEntry> =
    remember(rows) { flowOf(PagingData.from(rows)) }.collectAsLazyPagingItems()
