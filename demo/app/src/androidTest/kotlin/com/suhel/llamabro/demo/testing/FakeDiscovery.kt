package com.suhel.llamabro.demo.testing

import com.suhel.llamabro.demo.models.HubQuery
import com.suhel.llamabro.demo.models.ModelDiscovery
import com.suhel.llamabro.demo.models.RepoFile
import com.suhel.llamabro.demo.models.SearchModel
import com.suhel.llamabro.demo.models.SearchRowsPage
import com.suhel.llamabro.demo.models.TestHub
import kotlinx.coroutines.CompletableDeferred
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * The app's discovery over the SDK's fake hub: search is the hub's own over the repos a test lists there
 * (`TestHub.list`), so the SDK's grouping runs. What the fake can't hold is stood in: a repo's files at real sizes
 * ([repoFiles]; the fake keeps each GGUF in memory, a few KB), failing as an offline phone does when the test set none.
 * A model's page is the test's ([pages], by the repo the card asks for), else none.
 */
class FakeDiscovery(hub: TestHub) : ModelDiscovery(hub.hub) {
    val searches: MutableList<String> = CopyOnWriteArrayList()
    val repoFiles = ConcurrentHashMap<String, List<RepoFile>>()
    val pages = ConcurrentHashMap<String, String>()

    /** While set, a search waits for it after it is recorded: a test holds an answer back and then lets it arrive. */
    @Volatile var searchGate: CompletableDeferred<Unit>? = null

    /** The searches asked and not yet answered: the page's own refreshes of its suggestions' facts among them. */
    val searching = AtomicInteger(0)

    override suspend fun search(query: String): List<SearchModel> {
        searches += query
        searching.incrementAndGet()
        try {
            searchGate?.await()
            return super.search(query)
        } finally {
            searching.decrementAndGet()
        }
    }

    /** A page of a search, recorded and held back as [search] is: what the page's list asks the Hub. */
    override suspend fun searchPage(query: HubQuery, cursor: String?): SearchRowsPage {
        searches += query.text
        searching.incrementAndGet()
        try {
            searchGate?.await()
            return super.searchPage(query, cursor)
        } finally {
            searching.decrementAndGet()
        }
    }

    override suspend fun files(repo: String): List<RepoFile> = repoFiles[repo] ?: throw IOException("no such repo yet")

    override suspend fun page(repo: String): String? = pages[repo]
}
