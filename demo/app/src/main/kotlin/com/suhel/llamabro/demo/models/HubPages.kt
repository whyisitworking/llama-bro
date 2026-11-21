package com.suhel.llamabro.demo.models

import androidx.paging.ExperimentalPagingApi
import androidx.paging.LoadType
import androidx.paging.PagingState
import androidx.paging.RemoteMediator
import com.suhel.llamabro.demo.data.db.HubResultEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/** Where a search's pages are kept: its next cursor, and a page written in one transaction. */
interface SearchPageStore {
    /** The stored next cursor of [search]: null when no page is stored, [END] after the last. */
    suspend fun next(search: String): String?

    /**
     * Writes a page of [search]'s rows: the [first] page replaces what is stored; a later one adds rows after them,
     * a row of a model already stored merging into it where it stands. [next] is the next page's cursor, or null.
     */
    suspend fun write(search: String, rows: List<SearchModel>, next: String?, first: Boolean)

    companion object {
        /** [next]'s value once the last page is stored. */
        const val END = ""
    }
}

/**
 * Hugging Face's pages of one search into Room (models-search design §1): the Pager's source reads Room, so stored
 * pages show at once and offline; this fetches. On opening it refreshes the first page in place, [refreshAfterMillis]
 * after it starts (the field's debounce: typing on starts a new search and cancels this one before it asks); scrolling
 * near the end fetches the next page with the cursor stored with the last. [fetching] is told while the Hub is asked.
 */
@OptIn(ExperimentalPagingApi::class)
class HubPagesMediator(
    private val query: HubQuery,
    private val fetch: suspend (HubQuery, String?) -> SearchRowsPage,
    private val store: SearchPageStore,
    private val refreshAfterMillis: Long,
    private val fetching: (Boolean) -> Unit = {},
) : RemoteMediator<Int, HubResultEntity>() {
    override suspend fun initialize(): InitializeAction = InitializeAction.LAUNCH_INITIAL_REFRESH

    @Suppress("TooGenericExceptionCaught") // a failed page is the Pager's error state, whatever failed
    override suspend fun load(loadType: LoadType, state: PagingState<Int, HubResultEntity>): MediatorResult {
        val cursor =
            when (loadType) {
                LoadType.REFRESH -> {
                    null
                }

                LoadType.PREPEND -> {
                    return MediatorResult.Success(endOfPaginationReached = true)
                }

                LoadType.APPEND -> {
                    val next = store.next(query.key)
                    // nothing stored or the last page stored: nothing more to fetch
                    if (next == null || next == SearchPageStore.END) {
                        return MediatorResult.Success(endOfPaginationReached = true)
                    }
                    next
                }
            }
        if (loadType == LoadType.REFRESH) delay(refreshAfterMillis)
        return try {
            fetching(true)
            val page = fetch(query, cursor)
            store.write(query.key, page.rows, page.next, first = cursor == null)
            MediatorResult.Success(endOfPaginationReached = page.next == null)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            MediatorResult.Error(e)
        } finally {
            fetching(false)
        }
    }
}
