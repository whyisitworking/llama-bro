package com.suhel.llamabro.demo.testing

import com.suhel.llamabro.DeviceKind
import com.suhel.llamabro.demo.models.CatalogEntry
import com.suhel.llamabro.demo.models.DeviceFacts
import com.suhel.llamabro.demo.models.FitView
import com.suhel.llamabro.demo.models.HfSearch
import com.suhel.llamabro.demo.models.ModelDiscovery
import com.suhel.llamabro.demo.models.RepoFile
import com.suhel.llamabro.demo.models.SearchFailed
import com.suhel.llamabro.demo.models.SearchModel
import kotlinx.coroutines.CompletableDeferred
import java.io.IOException

/**
 * Hugging Face as a test sets it: the search results, each repo's files, and gates that hold a row's quick fit back.
 * (The HTTP layer itself is covered by `HfSearchTest` on the JVM.)
 */
class FakeDiscovery(facts: DeviceFacts = DeviceFacts(8L shl 30, DeviceKind.Npu)) :
    ModelDiscovery(HfSearch(token = { null }), { facts }) {
    val searches = mutableListOf<String>()
    var results: (String) -> List<SearchModel> = { emptyList() }
    val repoFiles = mutableMapOf<String, List<RepoFile>>()

    /** When set, every search fails with it (an [IOException] cause reads as offline). */
    var searchFailure: SearchFailed? = null

    /** A repo whose quick fit waits for the test to open its gate. */
    val fitGates = mutableMapOf<String, CompletableDeferred<Unit>>()

    override suspend fun search(query: String): List<SearchModel> {
        searches += query
        searchFailure?.let { throw it }
        return results(query)
    }

    override suspend fun files(repo: String): List<RepoFile> =
        repoFiles[repo] ?: throw SearchFailed("no such repo yet", retryable = true)

    override suspend fun quickFit(repo: String, catalog: CatalogEntry?): FitView? {
        fitGates[repo]?.await()
        return super.quickFit(repo, catalog)
    }

    companion object {
        fun offline() = SearchFailed("Couldn't reach Hugging Face", retryable = true, cause = IOException("offline"))
    }
}
