package com.suhel.llamabro.hub

import com.suhel.llamabro.hub.internal.HttpTransport
import com.suhel.llamabro.hub.internal.JvmStorage
import com.suhel.llamabro.hub.internal.repoFolder
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.delay
import com.suhel.llamabro.hub.internal.HubTestHooks
import java.nio.file.Files
import java.security.MessageDigest
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal class DownloadTest {
    @Before fun reset() = hub.reset()

    @Test fun freshDownloadHashesAndLinks() = runBlocking {
        val cache = Files.createTempDirectory("hub-download-").toFile()
        val client = instance(cache)
        val progress = client.download(HfModel("test/download")).toList()
        assertEquals(DownloadPhase.Complete, progress.last().phase)
        val plan = client.plan(HfModel("test/download"))
        val file = plan.files.single { it.role == HubFile.Role.Model }
        val root = File(cache, repoFolder("test/download"))
        val blob = File(root, "blobs/${file.oid}")
        val snapshot = File(root, "snapshots/${plan.commit}/${file.path}")
        assertTrue(blob.isFile)
        assertTrue(Files.isSymbolicLink(snapshot.toPath()))
        assertEquals(blob.canonicalPath, snapshot.canonicalPath)
        assertEquals(file.oid, sha256(blob))
        assertFalse(File(root, "blobs/${file.oid}.downloadInProgress").exists())
        assertTrue(File(cache, ".locks/${repoFolder("test/download")}/${file.oid}.lock").exists())
        assertEquals(plan.commit, File(root, "refs/main").readText())
    }

    @Test fun cachedFileNotTransferred() = runBlocking {
        val cache = Files.createTempDirectory("hub-cached-").toFile()
        val client = instance(cache)
        val ref = HfModel("test/download")
        client.download(ref).toList()
        hub.reset()
        client.download(ref).toList()
        assertTrue(hub.log().none { it.jsonObject.getValue("host").jsonPrimitive.content == "cdn" })
    }

    @Test fun resumeAfterDropUsesRange() = runBlocking {
        val cache = Files.createTempDirectory("hub-resume-").toFile()
        val client = instance(cache)
        val ref = HfModel("test/download")
        hub.control("[{\"kind\":\"drop\",\"path\":\"model-Q4_K_M.gguf\",\"after_bytes\":3145728,\"times\":3}]")
        try { client.download(ref).toList(); fail("three drops should exhaust retries") }
        catch (_: HubException.Network) { }
        hub.reset()
        client.download(ref).toList()
        val ranges = hub.log().filter { it.jsonObject.getValue("host").jsonPrimitive.content == "cdn" &&
            it.jsonObject.getValue("method").jsonPrimitive.content == "GET" }
            .map { it.jsonObject.getValue("range").jsonPrimitive.content }
        assertEquals("bytes=3145728-", ranges.first())
        val plan = client.plan(ref)
        assertEquals(plan.files.single().oid, sha256(File(cache, "${repoFolder(ref.repo)}/blobs/${plan.files.single().oid}")))
    }

    @Test fun retriesWithinOneCall() = runBlocking {
        val client = instance(Files.createTempDirectory("hub-retry-").toFile())
        hub.control("[{\"kind\":\"drop\",\"path\":\"model-Q4_K_M.gguf\",\"after_bytes\":1048576,\"times\":1}]")
        assertEquals(DownloadPhase.Complete, client.download(HfModel("test/download")).toList().last().phase)
        val gets = hub.log().count { it.jsonObject.getValue("host").jsonPrimitive.content == "cdn" &&
            it.jsonObject.getValue("method").jsonPrimitive.content == "GET" }
        assertEquals(2, gets)
    }

    @Test fun corruptBytesFailIntegrityThenRecover() = runBlocking {
        val cache = Files.createTempDirectory("hub-corrupt-").toFile()
        val client = instance(cache)
        val ref = HfModel("test/download")
        hub.control("[{\"kind\":\"corrupt\",\"path\":\"model-Q4_K_M.gguf\",\"offset\":17}]")
        try { client.download(ref).toList(); fail("corrupted body must fail") }
        catch (_: HubException.IntegrityMismatch) { }
        val oid = client.plan(ref).files.single().oid
        val root = File(cache, "${repoFolder(ref.repo)}/blobs")
        assertFalse(File(root, oid!!).exists())
        assertFalse(File(root, "$oid.downloadInProgress").exists())
        assertEquals(DownloadPhase.Complete, client.download(ref).toList().last().phase)
    }

    @Test fun nonLfsFileUsesGitBlobSha1() = runBlocking {
        val cache = Files.createTempDirectory("hub-sha1-").toFile()
        val client = instance(cache)
        val ref = HfModel("test/nonlfs")
        client.download(ref).toList()
        val file = client.plan(ref).files.single()
        assertEquals(40, file.oid?.length)
        assertTrue(File(cache, "${repoFolder(ref.repo)}/blobs/${file.oid}").isFile)
        val corruptClient = instance(Files.createTempDirectory("hub-sha1-corrupt-").toFile())
        hub.control("[{\"kind\":\"corrupt\",\"path\":\"model-Q4_K_M.gguf\",\"offset\":2}]")
        try { corruptClient.download(ref).toList(); fail("corrupt non-LFS body must fail SHA-1") }
        catch (_: HubException.IntegrityMismatch) { }
    }

    @Test fun insufficientStorageBeforeStart() = runBlocking {
        val cache = Files.createTempDirectory("hub-space-").toFile()
        val config = HubConfig { endpoint = hub.apiUrl; cacheDir = cache }
        val client = LlamaBroHub(config, cache, HttpTransport(hub.apiUrl, null, null), JvmStorage(1))
        try { client.download(HfModel("test/download")).toList(); fail("space check should fail") }
        catch (e: HubException.InsufficientStorage) { assertEquals(1, e.availableBytes) }
        assertTrue(hub.log().none { it.jsonObject.getValue("host").jsonPrimitive.content == "cdn" })
    }

    @Test fun gatedRepoAndRateLimitSurfaceTypedErrors() = runBlocking {
        val client = instance(Files.createTempDirectory("hub-errors-").toFile())
        try { client.download(HfModel("test/gated")).toList(); fail("gated should fail") }
        catch (e: HubException.Unauthorized) { assertEquals(401, e.httpStatus) }
        hub.control("[{\"kind\":\"status\",\"host\":\"api\",\"match\":\"resolve\",\"status\":429,\"retry_after\":7}]")
        try { client.download(HfModel("test/download")).toList(); fail("rate limit should fail") }
        catch (e: HubException.RateLimited) { assertEquals(7L, e.retryAfterSeconds) }
    }

    @Test fun twoCollectorsShareBlobLock() = runBlocking {
        val cache = Files.createTempDirectory("hub-two-collectors-").toFile()
        val client = instance(cache)
        listOf(async { client.download(HfModel("test/download")).toList() },
            async { client.download(HfModel("test/download")).toList() }).awaitAll()
        val gets = hub.log().count { it.jsonObject.getValue("host").jsonPrimitive.content == "cdn" &&
            it.jsonObject.getValue("method").jsonPrimitive.content == "GET" }
        assertEquals(1, gets)
    }

    @Test fun twoTagsShareProjectorBlob() = runBlocking {
        val cache = Files.createTempDirectory("hub-two-tags-").toFile()
        val client = instance(cache)
        listOf(async { client.download(HfModel("test/shared:Q4_K_M")).toList() },
            async { client.download(HfModel("test/shared:Q8_0")).toList() }).awaitAll()
        val mmproj = client.plan(HfModel("test/shared:Q8_0")).files.first { it.role == HubFile.Role.Mmproj }
        val gets = hub.log().count { entry ->
            entry.jsonObject.getValue("host").jsonPrimitive.content == "cdn" &&
                entry.jsonObject.getValue("method").jsonPrimitive.content == "GET" &&
                entry.jsonObject.getValue("path").jsonPrimitive.content.endsWith(mmproj.oid!!)
        }
        assertEquals(1, gets)
    }

    @Test fun resume416DropsPartialAndRestarts() = runBlocking {
        val cache = Files.createTempDirectory("hub-416-").toFile()
        val client = instance(cache)
        val ref = HfModel("test/download")
        writePrefix(cache, client, ref)
        hub.reset()
        hub.control("[{\"kind\":\"status\",\"host\":\"cdn\",\"match\":\"blob\",\"method\":\"GET\",\"status\":416}]")
        client.download(ref).toList()
        val ranges = cdnGetRanges()
        assertEquals("bytes=1048576-", ranges[0])
        assertEquals("null", ranges[1])
        assertTrue(client.plan(ref).files.single().cached)
    }

    @Test fun resume200DropsPartialAndRestarts() = runBlocking {
        val cache = Files.createTempDirectory("hub-200-").toFile()
        val client = instance(cache)
        val ref = HfModel("test/download")
        writePrefix(cache, client, ref)
        hub.reset()
        hub.control("[{\"kind\":\"ignore_range\",\"path\":\"model-Q4_K_M.gguf\"}]")
        client.download(ref).toList()
        val ranges = cdnGetRanges()
        assertEquals("bytes=1048576-", ranges[0])
        assertEquals("null", ranges[1])
        assertTrue(client.plan(ref).files.single().cached)
    }

    @Test fun enospcKeepsPartial() = runBlocking {
        val cache = Files.createTempDirectory("hub-enospc-").toFile()
        val config = HubConfig { endpoint = hub.apiUrl; cacheDir = cache }
        val client = LlamaBroHub(config, cache, HttpTransport(hub.apiUrl, null, null), JvmStorage(),
            openOutput = { file, append -> object : FileOutputStream(file, append) {
                private var written = 0
                override fun write(bytes: ByteArray, offset: Int, length: Int) {
                    if (written >= 1 shl 20) throw IOException("write failed: ENOSPC (No space left on device)")
                    val allowed = minOf(length, (1 shl 20) - written)
                    super.write(bytes, offset, allowed)
                    written += allowed
                    if (allowed < length) throw IOException("write failed: ENOSPC (No space left on device)")
                }
            } })
        try { client.download(HfModel("test/download")).toList(); fail("ENOSPC should fail") }
        catch (e: HubException.InsufficientStorage) { assertTrue(e.neededBytes > 0) }
        val oid = client.plan(HfModel("test/download")).files.single().oid
        assertEquals(1L shl 20, File(cache, "models--test--download/blobs/$oid.downloadInProgress").length())
    }

    @Test fun symlinkFailureSwitchesToDegradedMode() = runBlocking {
        val cache = Files.createTempDirectory("hub-degraded-").toFile()
        val config = HubConfig { endpoint = hub.apiUrl; cacheDir = cache }
        val calls = AtomicInteger()
        val client = LlamaBroHub(config, cache, HttpTransport(hub.apiUrl, null, null), JvmStorage(),
            createSymlink = { _, _ -> calls.incrementAndGet(); throw IOException("symlinks unavailable") })
        client.download(HfModel("test/download")).toList()
        client.download(HfModel("test/flat")).toList()
        assertEquals(1, calls.get())
        val plan = client.plan(HfModel("test/download"))
        val snapshot = File(cache, "models--test--download/snapshots/${plan.commit}/${plan.files.single().path}")
        assertTrue(snapshot.isFile)
        assertFalse(Files.isSymbolicLink(snapshot.toPath()))
    }

    @Test fun cancelKeepsPartialAndReleasesLock() = runBlocking {
        val cache = Files.createTempDirectory("hub-cancel-").toFile()
        val client = instance(cache)
        val ref = HfModel("test/download")
        hub.control("[{\"kind\":\"slow\",\"path\":\"model-Q4_K_M.gguf\",\"rate_mib_s\":0.5}]")
        val reached = CompletableDeferred<Unit>()
        val job = launch {
            client.download(ref).collect { update ->
                if (update.bytesDone >= 1L shl 20 && update.phase == DownloadPhase.Downloading) reached.complete(Unit)
            }
        }
        withTimeout(10_000) { reached.await() }
        job.cancelAndJoin()
        val oid = client.plan(ref).files.single().oid
        assertTrue(File(cache, "models--test--download/blobs/$oid.downloadInProgress").length() > 0)
        hub.reset()
        withTimeout(15_000) { client.download(ref).toList() }
        assertTrue(client.plan(ref).files.single().cached)
    }

    @Test fun killDuringEachPhaseResumes() = runBlocking {
        for (phase in listOf("midBody", "beforeRename", "beforeLink")) {
            hub.reset()
            val cache = Files.createTempDirectory("hub-kill-$phase-").toFile()
            val child = startChild(cache, "test/download", phase)
            try {
                assertEquals(phase, awaitChildPhase(child))
            } finally {
                child.destroyForcibly()
                child.waitFor(5, TimeUnit.SECONDS)
            }
            val client = instance(cache)
            withTimeout(15_000) { client.download(HfModel("test/download")).toList() }
            val file = client.plan(HfModel("test/download")).files.single()
            assertTrue("$phase missing snapshot", file.cached)
            val partial = File(cache, "models--test--download/blobs/${file.oid}.downloadInProgress")
            assertFalse("$phase left partial", partial.exists())
        }
    }

    @Test fun secondProcessWaitsForBlobLock() = runBlocking {
        val cache = Files.createTempDirectory("hub-process-lock-").toFile()
        val child = startChild(cache, "test/download", "midBody")
        try {
            assertEquals("midBody", awaitChildPhase(child))
            val client = instance(cache)
            val before = cdnGetRanges().size
            val parent = async { client.download(HfModel("test/download")).toList() }
            delay(500)
            assertEquals("parent must wait before CDN GET", before, cdnGetRanges().size)
            child.destroyForcibly()
            child.waitFor(5, TimeUnit.SECONDS)
            withTimeout(15_000) { parent.await() }
            assertTrue(cdnGetRanges().size > before)
        } finally {
            if (child.isAlive) child.destroyForcibly()
            child.waitFor(5, TimeUnit.SECONDS)
        }
    }

    private fun startChild(cache: File, ref: String, phase: String): Process {
        val javaBin = requireNotNull(System.getProperty("java.home")) + "/bin/java"
        return ProcessBuilder(javaBin, "-cp", requireNotNull(System.getProperty("java.class.path")),
            "com.suhel.llamabro.hub.HubChildMain", cache.path, hub.apiUrl, ref, phase)
            .redirectErrorStream(true).start()
    }

    private fun awaitChildPhase(child: Process): String? {
        val executor = Executors.newSingleThreadExecutor()
        return try {
            executor.submit<String?> { child.inputStream.bufferedReader().readLine() }.get(20, TimeUnit.SECONDS)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test fun discardedPartialNeverPushesProgressPastTotal() = runBlocking {
        val cache = Files.createTempDirectory("hub-416-progress-").toFile()
        val client = instance(cache)
        val ref = HfModel("test/download")
        writePrefix(cache, client, ref)
        hub.reset()
        hub.control("[{\"kind\":\"status\",\"host\":\"cdn\",\"match\":\"blob\",\"method\":\"GET\",\"status\":416}]")
        val updates = client.download(ref).toList()
        assertTrue(updates.joinToString { "${it.bytesDone}/${it.bytesTotal}" },
            updates.all { it.bytesDone <= it.bytesTotal })
        assertEquals(updates.last().bytesTotal, updates.last().bytesDone)
    }

    @Test fun completeVerifiedPartialRenamedWithoutRequest() = runBlocking {
        val source = Files.createTempDirectory("hub-full-partial-src-").toFile()
        val ref = HfModel("test/download")
        val first = instance(source)
        first.download(ref).toList()
        val oid = first.plan(ref).files.single().oid!!
        val cache = Files.createTempDirectory("hub-full-partial-").toFile()
        File(cache, "${repoFolder(ref.repo)}/blobs/$oid.downloadInProgress").apply {
            parentFile!!.mkdirs()
            writeBytes(File(source, "${repoFolder(ref.repo)}/blobs/$oid").readBytes())
        }
        hub.reset()
        instance(cache).download(ref).toList()
        assertEquals(emptyList<String>(), cdnGetRanges())
        assertEquals(oid, sha256(File(cache, "${repoFolder(ref.repo)}/blobs/$oid")))
    }

    @Test fun completeVerifiedPartialNeedsNoRequestEvenWithoutRanges() = runBlocking {
        val source = Files.createTempDirectory("hub-full-noranges-src-").toFile()
        val ref = HfModel("test/download")
        val first = instance(source)
        first.download(ref).toList()
        val oid = first.plan(ref).files.single().oid!!
        val cache = Files.createTempDirectory("hub-full-noranges-").toFile()
        File(cache, "${repoFolder(ref.repo)}/blobs/$oid.downloadInProgress").apply {
            parentFile!!.mkdirs()
            writeBytes(File(source, "${repoFolder(ref.repo)}/blobs/$oid").readBytes())
        }
        hub.reset()
        hub.control("[{\"kind\":\"no_accept_ranges\",\"path\":\"model-Q4_K_M.gguf\",\"times\":-1}]")
        instance(cache).download(ref).toList()
        assertTrue("no CDN request at all", hub.log().none { it.jsonObject.getValue("host").jsonPrimitive.content == "cdn" })
        assertEquals(oid, sha256(File(cache, "${repoFolder(ref.repo)}/blobs/$oid")))
    }

    @Test fun noAcceptRangesRestartsFromZero() = runBlocking {
        val cache = Files.createTempDirectory("hub-no-ranges-").toFile()
        val client = instance(cache)
        val ref = HfModel("test/download")
        writePrefix(cache, client, ref)
        hub.reset()
        hub.control("[{\"kind\":\"no_accept_ranges\",\"path\":\"model-Q4_K_M.gguf\",\"method\":\"HEAD\"}]")
        client.download(ref).toList()
        assertEquals(listOf("null"), cdnGetRanges())
        assertTrue(client.plan(ref).files.single().cached)
    }

    @Test fun danglingSnapshotLinkIsReplaced() = runBlocking {
        val cache = Files.createTempDirectory("hub-dangling-").toFile()
        val calls = AtomicInteger()
        val config = HubConfig { endpoint = hub.apiUrl; cacheDir = cache }
        val client = LlamaBroHub(config, cache, HttpTransport(hub.apiUrl, null, null), JvmStorage(),
            createSymlink = { link, target -> calls.incrementAndGet(); Files.createSymbolicLink(link, target) })
        val ref = HfModel("test/download")
        client.download(ref).toList()
        val plan = client.plan(ref)
        val snapshot = File(cache, "${repoFolder(ref.repo)}/snapshots/${plan.commit}/${plan.files.single().path}")
        Files.delete(snapshot.toPath())
        Files.createSymbolicLink(snapshot.toPath(), java.nio.file.Paths.get("../../blobs/gone")) // dangling
        client.download(ref).toList()
        assertEquals("replaced by a new link, not by degraded mode", 2, calls.get())
        assertTrue(Files.isSymbolicLink(snapshot.toPath()))
        assertTrue(snapshot.isFile)
    }

    private fun writePrefix(cache: File, client: LlamaBroHub, ref: HfModel) {
        val plan = runBlocking { client.plan(ref) }
        val file = plan.files.single()
        val url = URL("${hub.apiUrl}${ref.repo}/resolve/${plan.commit}/${file.path}")
        val prefix = url.openStream().use { it.readNBytes(1 shl 20) }
        File(cache, "${repoFolder(ref.repo)}/blobs/${file.oid}.downloadInProgress").apply {
            parentFile!!.mkdirs()
            writeBytes(prefix)
        }
    }

    private fun cdnGetRanges(): List<String> = hub.log().filter {
        it.jsonObject.getValue("host").jsonPrimitive.content == "cdn" &&
            it.jsonObject.getValue("method").jsonPrimitive.content == "GET"
    }.map { it.jsonObject.getValue("range").jsonPrimitive.content }

    private fun instance(cache: File): LlamaBroHub {
        val config = HubConfig { endpoint = hub.apiUrl; cacheDir = cache }
        return LlamaBroHub(config, cache, HttpTransport(hub.apiUrl, null, null), JvmStorage())
    }

    private fun sha256(file: File): String = MessageDigest.getInstance("SHA-256")
        .digest(file.readBytes()).joinToString("") { "%02x".format(it) }

    private companion object {
        lateinit var hub: FakeHub
        @JvmStatic @BeforeClass fun startHub() { hub = FakeHub() }
        @JvmStatic @AfterClass fun stopHub() { hub.close() }
    }
}
