package com.suhel.llamabro.hub

import com.suhel.llamabro.DownloadPhase
import com.suhel.llamabro.hub.internal.HttpTransport
import com.suhel.llamabro.hub.internal.HubTestHooks
import com.suhel.llamabro.hub.internal.JvmStorage
import com.suhel.llamabro.hub.internal.repoFolder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/** One writer per file: a download started while another of the same file still runs waits for it. */
internal class OneWriterTest {
    @Before fun reset() = hub.reset()

    /**
     * A Pause then a quick Resume: the new transfer starts while the stopped one still holds the partial, here through
     * a second hub whose cache directory is spelled another way (a link to it, as `/data/data` is to `/data/user/0`).
     * It waits for the old writer to let go, then resumes the same partial: never two writers on one file.
     */
    @Test fun quickResumeWaitsForTheStoppedWriter() =
        runBlocking {
            val cache = Files.createTempDirectory("hub-quick-resume-").toFile()
            val alias = File(Files.createTempDirectory("hub-quick-resume-alias-").toFile(), "cache")
            Files.createSymbolicLink(alias.toPath(), cache.toPath())
            val writers = AtomicInteger()
            val mostWriters = AtomicInteger()
            val counting: (File, Boolean) -> FileOutputStream = { file, append ->
                mostWriters.accumulateAndGet(writers.incrementAndGet(), ::maxOf)
                object : FileOutputStream(file, append) {
                    private var open = true

                    override fun close() {
                        if (open) writers.decrementAndGet()
                        open = false
                        super.close()
                    }
                }
            }
            val paused = instance(cache, counting)
            val resumed = instance(alias, counting)
            val ref = HfModel("test/download")
            hub.control("[{\"kind\":\"slow\",\"path\":\"model-Q4_K_M.gguf\",\"rate_mib_s\":0.5}]")
            val reached = CompletableDeferred<Unit>()
            val job =
                launch(Dispatchers.IO) {
                    paused.download(ref).collect { update ->
                        if (update.phase == DownloadPhase.Downloading && update.bytesDone >= 1L shl 20) {
                            reached.complete(Unit)
                        }
                    }
                }
            withTimeout(10_000) { reached.await() }
            val waiting = CompletableDeferred<Unit>()
            HubTestHooks.onPhase = { if (it == "lockWait") waiting.complete(Unit) }
            val progress =
                try {
                    val resume = async(Dispatchers.IO) { resumed.download(ref).toList() }
                    withTimeout(10_000) { waiting.await() } // the resumed transfer waits on the file
                    job.cancel()
                    hub.reset()
                    withTimeout(30_000) { resume.await() }
                } finally {
                    HubTestHooks.onPhase = null
                }
            job.join()
            assertEquals(DownloadPhase.Complete, progress.last().phase)
            assertEquals("one writer at a time", 1, mostWriters.get())
            val file = resumed.plan(ref).files.single()
            assertEquals(file.oid, sha256(File(cache, "${repoFolder(ref.repo)}/blobs/${file.oid}")))
        }

    /**
     * Deleting a file removes its blob once no entry links it; another tag's download that links the blob meanwhile
     * (after the delete removed the entries, before it removes the blob) keeps it.
     */
    @Test fun aBlobLinkedWhileAFileIsDeletedStays() =
        runBlocking {
            val cache = Files.createTempDirectory("hub-delete-link-race-").toFile()
            val client = instance(cache) { file, append -> FileOutputStream(file, append) }
            client.download(HfModel("test/shared:Q4_K_M")).toList()
            var linked = false
            HubTestHooks.onPhase = { phase ->
                if (phase == "deleteEntries" && !linked) {
                    linked = true
                    thread { runBlocking { client.download(HfModel("test/shared:Q8_0")).toList() } }.join(15_000)
                }
            }
            try {
                assertTrue(client.delete(HfModel("test/shared#mmproj-model-F16.gguf")))
            } finally {
                HubTestHooks.onPhase = null
            }
            assertTrue("the other tag downloaded during the delete", linked)
            val loads = client.source(HfModel("test/shared:Q8_0")).toString()
            val mmproj = Regex("mmproj=([^,)]+)").find(loads)!!.groupValues[1]
            assertTrue(loads, File(mmproj).isFile)
        }

    private fun instance(cache: File, openOutput: (File, Boolean) -> FileOutputStream): LlamaBroHub {
        val config =
            HubConfig {
                endpoint = hub.apiUrl
                cacheDir = cache
            }
        return LlamaBroHub(config, cache, HttpTransport(hub.apiUrl, null, null), JvmStorage(), openOutput)
    }

    private fun sha256(file: File): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(file.readBytes())
            .joinToString("") { "%02x".format(it) }

    private companion object {
        lateinit var hub: FakeHub

        @JvmStatic @BeforeClass
        fun startHub() {
            hub = FakeHub()
        }

        @JvmStatic @AfterClass
        fun stopHub() {
            hub.close()
        }
    }
}
