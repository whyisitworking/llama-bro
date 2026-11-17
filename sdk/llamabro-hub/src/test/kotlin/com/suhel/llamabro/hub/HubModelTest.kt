package com.suhel.llamabro.hub

import com.suhel.llamabro.DownloadPhase
import com.suhel.llamabro.DownloadProgress
import com.suhel.llamabro.hub.internal.HttpTransport
import com.suhel.llamabro.hub.internal.HubTestHooks
import com.suhel.llamabro.hub.internal.JvmStorage
import com.suhel.llamabro.hub.internal.Response
import com.suhel.llamabro.hub.internal.Transport
import com.suhel.llamabro.hub.internal.repoFolder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/** `hub.model()` as core sees it: a [com.suhel.llamabro.RemoteModelSource] (D64, D75). */
internal class HubModelTest {
    @Before fun reset() = hub.reset()

    @Test fun cachedModelLoadsWithNoNetworkRequest() =
        runBlocking {
            val cache = Files.createTempDirectory("hub-model-cached-").toFile()
            val ref = "test/progress:Q4_K_M"
            val options: PlanOptions.() -> Unit = { draft = DraftRequest.dflash(HfModel("test/draftfull")) }
            val tokens = AtomicInteger()
            instance(cache, tokens = tokens).download(HfModel(ref), options).toList()
            hub.reset()
            tokens.set(0)
            val requests = RecordingTransport(HttpTransport(hub.apiUrl, null, null))
            val restarted = instance(cache, requests, tokens = tokens)
            val progress = mutableListOf<DownloadProgress>()
            val local = restarted.model(ref, options).fetch { progress += it }.toString()
            assertTrue(local, pathOf(local).endsWith("model-Q4_K_M.gguf"))
            assertTrue(local, File(fieldOf(local, "mmproj")).isFile)
            assertTrue(local, fieldOf(local, "draft").contains(repoFolder("test/draftfull")))
            restarted.model(ref, options).withHeaders { headers ->
                assertEquals("the cached files are their own headers", local, headers.toString())
            }
            assertEquals("no request through the transport", emptyList<String>(), requests.urls)
            assertEquals("no token lookup", 0, tokens.get())
            assertTrue("no request reaches the Hub", hub.log().isEmpty())
            assertTrue("a cached copy has nothing to report", progress.isEmpty())
        }

    @Test fun missingModelDownloadsThenLoads() =
        runBlocking {
            val cache = Files.createTempDirectory("hub-model-missing-").toFile()
            val progress = mutableListOf<DownloadProgress>()
            val local = instance(cache).model("test/download").fetch { progress += it }
            assertEquals(DownloadPhase.Complete, progress.last().phase)
            val path = pathOf(local.toString())
            assertTrue(path, File(path).isFile)
            assertEquals(4L shl 20, File(path).length())
            assertTrue(path.startsWith(File(cache, repoFolder("test/download")).absolutePath))
        }

    @Test fun cancelledFetchResumesNextTime() =
        runBlocking {
            val cache = Files.createTempDirectory("hub-model-cancel-").toFile()
            val client = instance(cache)
            hub.control("[{\"kind\":\"slow\",\"path\":\"model-Q4_K_M.gguf\",\"rate_mib_s\":0.5}]")
            val reached = CompletableDeferred<Unit>()
            val job =
                launch {
                    client.model("test/download").fetch { update ->
                        if (update.phase == DownloadPhase.Downloading && update.bytesDone >= 1L shl 20) {
                            reached.complete(Unit)
                        }
                    }
                    fail("a cancelled fetch must not return files")
                }
            withTimeout(10_000) { reached.await() }
            job.cancelAndJoin()
            val blobs = File(cache, "${repoFolder("test/download")}/blobs")
            val partial = blobs.listFiles().orEmpty().single { it.name.endsWith(".downloadInProgress") }
            assertTrue("the partial file stays (F114-F116)", partial.length() > 0)
            hub.reset()
            val local = withTimeout(15_000) { client.model("test/download").fetch {} }
            assertTrue(File(pathOf(local.toString())).isFile)
            val firstRange =
                hub
                    .log()
                    .first {
                        it.jsonObject
                            .getValue("host")
                            .jsonPrimitive.content == "cdn" &&
                            it.jsonObject
                                .getValue("method")
                                .jsonPrimitive.content == "GET"
                    }.jsonObject
                    .getValue("range")
                    .jsonPrimitive.content
            assertTrue(
                "resumed, not restarted: $firstRange",
                firstRange.startsWith("bytes=") && firstRange != "bytes=0-",
            )
        }

    @Test fun hasUpdateIsTrueForANewerCommit() =
        runBlocking {
            val cache = Files.createTempDirectory("hub-model-update-").toFile()
            val ref = HfModel("test/update")
            instance(cache).model(ref).fetch {}
            assertFalse("the same commit is no update", instance(cache).hasUpdate(ref))
            FakeHub(updateRepo(commit = "b".repeat(40), seed = 51)).use { newer ->
                val requests = RecordingTransport(HttpTransport(newer.apiUrl, null, null))
                val client = instance(cache, requests, newer.apiUrl)
                assertTrue(client.hasUpdate(ref))
                // The check moves nothing: the cached copy still loads, offline, until the app downloads (D64).
                requests.urls.clear()
                val local = client.model(ref).fetch {}
                assertTrue(local.toString().contains("a".repeat(40)))
                assertEquals(emptyList<String>(), requests.urls)
                client.download(ref).toList()
                assertFalse("downloaded: up to date", client.hasUpdate(ref))
                assertTrue(
                    client
                        .model(ref)
                        .fetch {}
                        .toString()
                        .contains("b".repeat(40)),
                )
            }
            FakeHub(updateRepo(commit = "c".repeat(40), seed = 51)).use { sameFiles ->
                val client = instance(cache, endpoint = sameFiles.apiUrl)
                assertFalse("a new commit with the same files is no update", client.hasUpdate(ref))
            }
        }

    @Test fun planSeeingANewerCommitKeepsTheInstalledCopy() =
        runBlocking {
            val cache = Files.createTempDirectory("hub-model-plan-newer-").toFile()
            val ref = HfModel("test/update")
            instance(cache).model(ref).fetch {}
            FakeHub(updateRepo(commit = "b".repeat(40), seed = 51)).use { newer ->
                val requests = RecordingTransport(HttpTransport(newer.apiUrl, null, null))
                val client = instance(cache, requests, newer.apiUrl)
                // a started update: refs/main and the plan record move
                assertEquals("b".repeat(40), client.recordedPlan(ref).commit)
                requests.urls.clear()
                assertTrue(
                    client
                        .model(ref)
                        .fetch {}
                        .toString()
                        .contains("a".repeat(40)),
                )
                assertTrue(client.source(ref).toString().contains("a".repeat(40)))
                assertEquals(emptyList<String>(), requests.urls)
                client.awaitDiskState(ref)
                assertEquals("what loads is complete", DownloadState.Complete, client.progress(ref).value)
            }
        }

    @Test fun cancelledUpdateKeepsTheInstalledCopyUntilOneCompletes() =
        runBlocking {
            val cache = Files.createTempDirectory("hub-model-update-cancel-").toFile()
            val ref = HfModel("test/update")
            instance(cache).model(ref).fetch {}
            FakeHub(updateRepo(commit = "b".repeat(40), seed = 53, size = 4 shl 20)).use { newer ->
                val client = instance(cache, endpoint = newer.apiUrl)
                assertTrue(client.hasUpdate(ref))
                newer.control("[{\"kind\":\"slow\",\"path\":\"model-Q4_K_M.gguf\",\"rate_mib_s\":0.5}]")
                val reached = CompletableDeferred<Unit>()
                val job =
                    launch {
                        client.download(ref).collect { update ->
                            if (update.phase == DownloadPhase.Downloading && update.bytesDone >= 1L shl 20) {
                                reached.complete(Unit)
                            }
                        }
                    }
                withTimeout(10_000) { reached.await() }
                job.cancelAndJoin()

                val dead = "http://127.0.0.1:1/"
                val requests = RecordingTransport(HttpTransport(dead, null, null))
                val offline = instance(cache, requests, dead)
                assertTrue(
                    offline
                        .model(ref)
                        .fetch {}
                        .toString()
                        .contains("a".repeat(40)),
                )
                assertEquals("offline, from the installed copy", emptyList<String>(), requests.urls)

                newer.reset()
                withTimeout(15_000) { client.download(ref).toList() }
                assertTrue(
                    "a completed update loads",
                    client
                        .model(ref)
                        .fetch {}
                        .toString()
                        .contains("b".repeat(40)),
                )
                assertFalse(client.hasUpdate(ref))
            }
        }

    @Test fun oldFormatCacheCountsAsInstalled() =
        runBlocking {
            val cache = Files.createTempDirectory("hub-model-old-format-").toFile()
            val ref = HfModel("test/update")
            instance(cache).model(ref).fetch {}
            val installed = File(cache, ".llamabro/installed")
            installed.deleteRecursively() // as a cache written before the installed record
            hub.reset()
            val requests = RecordingTransport(HttpTransport(hub.apiUrl, null, null))
            assertTrue(
                instance(cache, requests)
                    .model(ref)
                    .fetch {}
                    .toString()
                    .contains("a".repeat(40)),
            )
            assertEquals(emptyList<String>(), requests.urls)
            assertTrue("adopted", leftovers(installed).isNotEmpty())

            installed.deleteRecursively()
            FakeHub(updateRepo(commit = "b".repeat(40), seed = 51)).use { newer ->
                val client = instance(cache, endpoint = newer.apiUrl)
                client.recordedPlan(ref) // a started update adopts the old copy before its listing moves refs/main
                assertTrue(
                    client
                        .model(ref)
                        .fetch {}
                        .toString()
                        .contains("a".repeat(40)),
                )
                assertTrue(
                    newer.log().none {
                        it.jsonObject
                            .getValue("host")
                            .jsonPrimitive.content == "cdn"
                    },
                )
            }
        }

    @Test fun hasUpdateThrowsWhenRefused() =
        runBlocking {
            val cache = Files.createTempDirectory("hub-model-refused-").toFile()
            val ref = HfModel("test/update")
            instance(cache).model(ref).fetch {}
            hub.control("[{\"kind\":\"status\",\"host\":\"api\",\"match\":\"refs\",\"status\":429}]")
            try {
                instance(cache).hasUpdate(ref)
                fail("a rate limit is not \"no update\"")
            } catch (expected: HubException.RateLimited) {
                assertTrue(expected.retryable)
            }
            hub.control("[{\"kind\":\"status\",\"host\":\"api\",\"match\":\"refs\",\"status\":401}]")
            try {
                instance(cache).hasUpdate(ref)
                fail("a refusal is not \"no update\"")
            } catch (expected: HubException.Unauthorized) {
                assertEquals(401, expected.httpStatus)
            }
        }

    @Test fun hasUpdateThrowsWhenTheHubAnswersWithAnError() =
        runBlocking {
            val cache = Files.createTempDirectory("hub-model-5xx-").toFile()
            val ref = HfModel("test/update")
            instance(cache).model(ref).fetch {}
            hub.control("[{\"kind\":\"status\",\"host\":\"api\",\"match\":\"refs\",\"status\":503}]")
            try {
                instance(cache).hasUpdate(ref)
                fail("a 5xx is the Hub's answer, not \"offline\"")
            } catch (expected: HubException.Network) {
                assertTrue(expected.retryable)
            }
        }

    @Test fun planIsALookThatChangesNothingOnThePhone() =
        runBlocking {
            val ref = HfModel("test/update")
            val fresh = Files.createTempDirectory("hub-model-plan-fresh-").toFile()
            instance(fresh).plan(ref)
            assertFalse("no plan record for a model never downloaded", File(fresh, ".llamabro").exists())
            assertFalse(File(fresh, "${repoFolder(ref.repo)}/refs/main").exists())

            val cache = Files.createTempDirectory("hub-model-plan-look-").toFile()
            instance(cache).model(ref).fetch {}

            fun onDisk() = cache.walkTopDown().filter { it.isFile }.associate { it.path to it.readText() }
            val before = onDisk()
            FakeHub(updateRepo(commit = "b".repeat(40), seed = 51, size = 4 shl 20)).use { newer ->
                val client = instance(cache, endpoint = newer.apiUrl)
                val cached = client.cached().map { it.model.toString() to it.sizeBytes }
                assertEquals("b".repeat(40), client.plan(ref).commit)
                assertEquals("refs/main and every record as they were", before, onDisk())
                assertEquals(cached, client.cached().map { it.model.toString() to it.sizeBytes })
                val restarted = instance(cache, endpoint = newer.apiUrl)
                restarted.awaitDiskState(ref)
                assertEquals(DownloadState.Complete, restarted.progress(ref).value)
            }
        }

    @Test fun cachedStillListsTheInstalledCopyAfterAPlanOrACancelledUpdate() =
        runBlocking {
            val ref = HfModel("test/update")
            val listed = listOf("test/update:Q4_K_M")
            val planned = Files.createTempDirectory("hub-model-cached-plan-").toFile()
            instance(planned).model(ref).fetch {}
            assertEquals(listed, instance(planned).cached().map { it.model.toString() })
            FakeHub(updateRepo(commit = "b".repeat(40), seed = 51)).use { newer ->
                val client = instance(planned, endpoint = newer.apiUrl)
                client.recordedPlan(ref) // a started update moves refs/main to a commit the phone doesn't hold
                val cached = client.cached()
                assertEquals("still installed, still loads (D64)", listed, cached.map { it.model.toString() })
                assertEquals(1L shl 16, cached.single().sizeBytes)
            }

            val cancelled = Files.createTempDirectory("hub-model-cached-cancel-").toFile()
            instance(cancelled).model(ref).fetch {}
            FakeHub(updateRepo(commit = "b".repeat(40), seed = 53, size = 4 shl 20)).use { newer ->
                val client = instance(cancelled, endpoint = newer.apiUrl)
                newer.control("[{\"kind\":\"slow\",\"path\":\"model-Q4_K_M.gguf\",\"rate_mib_s\":0.5}]")
                val reached = CompletableDeferred<Unit>()
                val job =
                    launch {
                        client.download(ref).collect { update ->
                            if (update.phase == DownloadPhase.Downloading && update.bytesDone >= 1L shl 20) {
                                reached.complete(Unit)
                            }
                        }
                    }
                withTimeout(10_000) { reached.await() }
                job.cancelAndJoin()
                assertEquals("a cancelled update keeps it listed", listed, client.cached().map { it.model.toString() })
            }
        }

    @Test fun completedUpdateReclaimsTheOldCopyButKeepsSharedFiles() =
        runBlocking {
            val ref = HfModel("test/update")
            val alone = Files.createTempDirectory("hub-model-reclaim-").toFile()
            instance(alone).model(ref).fetch {}
            val oldBlobs = blobsOf(alone)
            val oldEntry = File(alone, "${repoFolder("test/update")}/snapshots/${"a".repeat(40)}/model-Q4_K_M.gguf")
            assertTrue(Files.exists(oldEntry.toPath(), LinkOption.NOFOLLOW_LINKS))
            FakeHub(updateRepo(commit = "b".repeat(40), seed = 51)).use { newer ->
                instance(alone, endpoint = newer.apiUrl).download(ref).toList()
            }
            assertTrue("the replaced copy's blob is gone", blobsOf(alone).none { it in oldBlobs })
            assertFalse("and its snapshot entry", Files.exists(oldEntry.toPath(), LinkOption.NOFOLLOW_LINKS))
            assertEquals(1, blobsOf(alone).size)

            val shared = Files.createTempDirectory("hub-model-reclaim-shared-").toFile()
            val noEncoder: PlanOptions.() -> Unit = { mmproj = false }
            instance(shared).model(ref).fetch {}
            instance(shared).model(ref, noEncoder).fetch {}
            val sharedBlobs = blobsOf(shared)
            FakeHub(updateRepo(commit = "b".repeat(40), seed = 51)).use { newer ->
                val client = instance(shared, endpoint = newer.apiUrl)
                client.download(ref).toList()
                assertTrue("another selection still uses the old blob", blobsOf(shared).containsAll(sharedBlobs))
                assertTrue(
                    "and still loads it",
                    client
                        .model(ref, noEncoder)
                        .fetch {}
                        .toString()
                        .contains("a".repeat(40)),
                )
            }
        }

    @Test fun aReclaimThatFailsStillCompletesTheUpdate() =
        runBlocking {
            val ref = HfModel("test/update")
            val cache = Files.createTempDirectory("hub-model-reclaim-fails-").toFile()
            instance(cache).model(ref).fetch {}
            // the old entry turned into a directory with something in it: removing it throws
            val oldEntry = File(cache, "${repoFolder("test/update")}/snapshots/${"a".repeat(40)}/model-Q4_K_M.gguf")
            Files.delete(oldEntry.toPath())
            File(oldEntry, "kept").apply { parentFile!!.mkdirs() }.writeText("x")
            FakeHub(updateRepo(commit = "b".repeat(40), seed = 51)).use { newer ->
                val client = instance(cache, endpoint = newer.apiUrl)
                assertEquals(
                    DownloadPhase.Complete,
                    client
                        .download(ref)
                        .toList()
                        .last()
                        .phase,
                )
                assertTrue("the new copy is the one that loads", client.source(ref).toString().contains("b".repeat(40)))
            }
        }

    /**
     * Another selection of the old commit downloads while the update reclaims, after the reclaim has read the records
     * (so they don't name it yet) and while it holds the blob's lock. That download must wait on the lock, not take the
     * entry it saw before the reclaim removed it: it fetches the blob again and loads.
     */
    @Test fun aSelectionDownloadedWhileAnUpdateReclaimsStillLoads() =
        runBlocking {
            val ref = HfModel("test/update")
            val sameFile = HfModel("test/update#model-Q4_K_M.gguf")
            val cache = Files.createTempDirectory("hub-model-reclaim-race-").toFile()
            instance(cache).model(ref).fetch {}
            val waitingOrDone = CountDownLatch(1)
            var competitor: Thread? = null
            HubTestHooks.onPhase = { phase ->
                when {
                    phase == "lockWait" -> {
                        waitingOrDone.countDown()
                    }

                    phase == "reclaimRead" && competitor == null -> {
                        competitor =
                            thread {
                                runBlocking { instance(cache).download(sameFile).toList() }
                                waitingOrDone.countDown()
                            }
                        assertTrue("the download reached the lock", waitingOrDone.await(10, TimeUnit.SECONDS))
                    }
                }
            }
            try {
                FakeHub(updateRepo(commit = "b".repeat(40), seed = 51)).use { newer ->
                    instance(cache, endpoint = newer.apiUrl).download(ref).toList()
                }
                competitor?.join(15_000)
            } finally {
                HubTestHooks.onPhase = null
            }
            assertTrue("the reclaim read the records with the other download under way", competitor != null)
            val loads = instance(cache).source(sameFile).toString()
            assertTrue(loads, loads.contains("a".repeat(40)) && File(pathOf(loads)).isFile)
        }

    private fun blobsOf(cache: File): Set<String> =
        File(cache, "${repoFolder("test/update")}/blobs")
            .listFiles()
            .orEmpty()
            .filter { it.isFile && !it.name.endsWith(".downloadInProgress") }
            .map { it.name }
            .toSet()

    @Test fun hasUpdateIsFalseOffline() =
        runBlocking {
            val cache = Files.createTempDirectory("hub-model-offline-").toFile()
            val ref = HfModel("test/update")
            assertFalse("nothing cached", instance(cache).hasUpdate(ref))
            assertTrue("nothing cached asks nothing", hub.log().isEmpty())
            instance(cache).model(ref).fetch {}
            val offline = "http://127.0.0.1:1/"
            assertFalse(instance(cache, HttpTransport(offline, null, null), offline).hasUpdate(ref))
        }

    @Test fun filesArriveInThePlansOrder() =
        runBlocking {
            val ref = HfModel("test/headers:Q4_K_M")
            val options: PlanOptions.() -> Unit = { draft = DraftRequest.dflash() }
            repeat(PLAN_ORDER_ROUNDS) {
                val cache = Files.createTempDirectory("hub-model-order-").toFile()
                val client = instance(cache)
                hub.reset()
                client.download(ref, options).toList()
                val fetched =
                    hub
                        .log()
                        .map { it.jsonObject }
                        .filter { it.getValue("host").jsonPrimitive.content == "cdn" }
                        .filter { it.getValue("method").jsonPrimitive.content == "GET" }
                        .map {
                            it
                                .getValue("path")
                                .jsonPrimitive.content
                                .substringAfterLast('/')
                        }
                // the CDN serves blobs by oid; the model and its draft here are one blob, fetched once
                assertEquals(
                    client
                        .plan(ref, options)
                        .files
                        .map { it.oid }
                        .distinct(),
                    fetched,
                )
                cache.deleteRecursively()
            }
        }

    @Test fun withHeadersFetchesOnlyHeadersAndCleansUp() =
        runBlocking {
            val cache = Files.createTempDirectory("hub-model-headers-").toFile()
            val requests = RecordingTransport(HttpTransport(hub.apiUrl, null, null))
            val client = instance(cache, requests)
            val estimates = File(cache, ".llamabro/estimate")
            val seen =
                client.model("test/headers:Q4_K_M") { draft = DraftRequest.dflash() }.withHeaders { local ->
                    val text = local.toString()
                    val model = File(pathOf(text))
                    assertTrue(text, model.isFile)
                    assertEquals("a sparse stub of the full length", ggufFile.length(), model.length())
                    val mmproj = File(Regex("mmproj=([^,)]+)").find(text)!!.groupValues[1])
                    assertEquals(MMPROJ_BYTES, mmproj.length())
                    assertTrue(File(Regex("draft=([^,)]+)").find(text)!!.groupValues[1]).isFile)
                    text
                }
            assertTrue(seen.contains(estimates.absolutePath))
            assertTrue("stubs removed: ${leftovers(estimates)}", leftovers(estimates).isEmpty())
            val cdn =
                hub.log().filter {
                    it.jsonObject
                        .getValue("host")
                        .jsonPrimitive.content == "cdn"
                }
            val ranges = cdn.map { it.jsonObject["range"]?.jsonPrimitive?.content }
            assertEquals("the model's and the draft's headers only", listOf(HEADER_RANGE, HEADER_RANGE), ranges)
            assertTrue("no projector bytes", requests.urls.none { it.contains("mmproj") })

            try {
                client.model("test/headers:Q4_K_M").withHeaders<Unit> { error("estimate failed") }
                fail("the block's failure passes through")
            } catch (expected: IllegalStateException) {
                assertEquals("estimate failed", expected.message)
            }
            assertTrue("stubs removed after a failure: ${leftovers(estimates)}", leftovers(estimates).isEmpty())
        }

    private fun leftovers(dir: File): List<File> = dir.walkTopDown().filter { it.isFile }.toList()

    /** The model path of a local source's description: `FileSource(path=…, mmproj=…, draft=…)`. */
    private fun pathOf(text: String): String = fieldOf(text, "path")

    private fun fieldOf(text: String, name: String): String = Regex("\\b$name=([^,)]+)").find(text)!!.groupValues[1]

    private fun instance(
        cache: File,
        transport: Transport = HttpTransport(hub.apiUrl, null, null),
        endpoint: String = hub.apiUrl,
        tokens: AtomicInteger? = null,
    ): LlamaBroHub {
        val config =
            HubConfig {
                this.endpoint = endpoint
                cacheDir = cache
                tokens?.let { counter ->
                    token {
                        counter.incrementAndGet()
                        null
                    }
                }
            }
        return LlamaBroHub(config, cache, transport, JvmStorage())
    }

    /** Every request the hub opens, so a test can assert there were none. */
    private class RecordingTransport(private val inner: Transport) : Transport {
        val urls: MutableList<String> = Collections.synchronizedList(mutableListOf())

        override suspend fun open(
            method: String,
            url: String,
            headers: Map<String, String>,
            range: Long?,
            rangeEnd: Long?,
        ): Response {
            urls += "$method $url"
            return inner.open(method, url, headers, range, rangeEnd)
        }
    }

    private companion object {
        const val MMPROJ_BYTES = 1L shl 20
        const val PLAN_ORDER_ROUNDS = 5
        const val HEADER_RANGE = "bytes=0-8388607"

        lateinit var hub: FakeHub
        lateinit var ggufFile: File

        fun updateRepo(commit: String, seed: Int, size: Int = 1 shl 16): JsonObject =
            buildJsonObject {
                put(
                    "test/update",
                    buildJsonObject {
                        put("commit", commit)
                        put(
                            "files",
                            buildJsonArray {
                                add(
                                    buildJsonObject {
                                        put("path", "model-Q4_K_M.gguf")
                                        put("size", size)
                                        put("seed", seed)
                                    },
                                )
                            },
                        )
                    },
                )
            }

        /** A valid GGUF whose header is a few bytes and whose tensor data runs past the first 8 MiB request. */
        fun gguf(): File {
            val buffer = ByteBuffer.allocate(256).order(ByteOrder.LITTLE_ENDIAN)
            val name = "weights".toByteArray()
            buffer.put("GGUF".toByteArray())
            buffer.putInt(3)
            buffer.putLong(1) // tensors
            buffer.putLong(0) // metadata pairs
            buffer.putLong(name.size.toLong())
            buffer.put(name)
            buffer.putInt(1)
            buffer.putLong(1)
            buffer.putInt(0)
            buffer.putLong(0)
            val header = buffer.array().copyOf((buffer.position() + 31) and -32)
            return Files.createTempFile("hub-model-gguf-", ".gguf").toFile().apply {
                writeBytes(header)
                appendBytes(ByteArray(12 shl 20))
                deleteOnExit()
            }
        }

        @JvmStatic @BeforeClass
        fun startHub() {
            ggufFile = gguf()
            val repos =
                buildJsonObject {
                    for ((name, repo) in updateRepo(commit = "a".repeat(40), seed = 50)) put(name, repo)
                    put(
                        "test/headers",
                        buildJsonObject {
                            put(
                                "files",
                                buildJsonArray {
                                    for (path in listOf("model-Q4_K_M.gguf", "dflash-model-Q4_K_M.gguf")) {
                                        add(
                                            buildJsonObject {
                                                put("path", path)
                                                put("source", ggufFile.absolutePath)
                                            },
                                        )
                                    }
                                    add(
                                        buildJsonObject {
                                            put("path", "mmproj-model-F16.gguf")
                                            put("size", MMPROJ_BYTES)
                                            put("seed", 52)
                                        },
                                    )
                                },
                            )
                        },
                    )
                }
            hub = FakeHub(repos)
        }

        @JvmStatic @AfterClass
        fun stopHub() {
            hub.close()
        }
    }
}
