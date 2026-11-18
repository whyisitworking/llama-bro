package com.suhel.llamabro.demo.testing

import com.suhel.llamabro.DeviceKind
import com.suhel.llamabro.demo.models.CatalogEntry
import com.suhel.llamabro.demo.models.DeviceFacts
import com.suhel.llamabro.demo.models.FitView
import com.suhel.llamabro.demo.models.ModelDiscovery
import com.suhel.llamabro.demo.models.RepoFile
import com.suhel.llamabro.demo.models.SearchModel
import com.suhel.llamabro.demo.models.TestHub
import kotlinx.coroutines.CompletableDeferred
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The app's discovery over the SDK's fake hub: search is the hub's own over the repos a test lists there
 * (`TestHub.list`), so the SDK's grouping runs. What the fake can't hold is stood in: a repo's files at real sizes
 * ([repoFiles]; the fake keeps each GGUF in memory, a few KB), failing as an offline phone does when the test set none;
 * and gates that hold a row's quick fit back.
 */
class FakeDiscovery(hub: TestHub, facts: DeviceFacts = DeviceFacts(8L shl 30, DeviceKind.Npu)) :
    ModelDiscovery(hub.hub, { facts }) {
    val searches: MutableList<String> = CopyOnWriteArrayList()
    val repoFiles = ConcurrentHashMap<String, List<RepoFile>>()

    /** A repo whose quick fit waits for the test to open its gate. */
    val fitGates = ConcurrentHashMap<String, CompletableDeferred<Unit>>()

    override suspend fun search(query: String): List<SearchModel> {
        searches += query
        return super.search(query)
    }

    override suspend fun files(repo: String): List<RepoFile> = repoFiles[repo] ?: throw IOException("no such repo yet")

    override suspend fun quickFit(repo: String, catalog: CatalogEntry?): FitView? {
        fitGates[repo]?.await()
        return super.quickFit(repo, catalog)
    }
}
