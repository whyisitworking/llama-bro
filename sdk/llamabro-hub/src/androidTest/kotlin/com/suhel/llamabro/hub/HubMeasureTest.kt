package com.suhel.llamabro.hub

import android.os.SystemClock
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.suhel.llamabro.hub.internal.AndroidStorage
import com.suhel.llamabro.hub.internal.HttpTransport
import com.suhel.llamabro.hub.internal.repoFolder
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Explicit T8 phone measurements; ordinary Hub device checks skip this large transfer. */
class HubMeasureTest {
    @Test fun parallelismAndResumeVerification() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("t8Measure") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.noBackupFilesDir, "llamabro/hub-measure")
        val endpoint = "http://127.0.0.1:18190/"
        val config = HubConfig { this.endpoint = endpoint }
        fun hub(cache: File, parallel: Int) = LlamaBroHub(config, cache,
            HttpTransport(endpoint, null, null), AndroidStorage(context), parallelDownloads = parallel)

        try {
            val parallelRef = HfModel("test/parallel:Q4_K_M")
            for (parallel in listOf(1, 4, 4, 1)) {
                val cache = File(root, "parallel-$parallel-${SystemClock.elapsedRealtime()}")
                val instance = hub(cache, parallel)
                val plan = instance.plan(parallelRef)
                assertEquals("four split parts", 4, plan.files.size)
                val start = SystemClock.elapsedRealtime()
                instance.download(parallelRef).collect()
                val millis = SystemClock.elapsedRealtime() - start
                Log.i("LlamaBroHubMeasure", "parallel=$parallel bytes=${plan.bytesTotal} millis=$millis")
                assertTrue("transfer time was not measured", millis > 0)
                cache.deleteRecursively()
            }

            val verifyRef = HfModel("test/verify:Q4_K_M")
            val verifyCache = File(root, "verify")
            val instance = hub(verifyCache, 1)
            val plan = instance.plan(verifyRef)
            val threshold = 5L * 1024 * 1024 * 1024 / 2
            assertTrue("verification file must exceed 2.5 GiB", plan.bytesTotal > threshold)
            try {
                instance.download(verifyRef).collect { progress ->
                    if (progress.phase == DownloadPhase.Downloading && progress.bytesDone >= threshold)
                        throw CancellationException("measure a 2.5 GiB partial")
                }
            } catch (_: CancellationException) {
                // The downloader keeps the verified partial for the second collection.
            }
            val file = plan.files.single()
            val partial = File(verifyCache,
                "${repoFolder(file.sourceRepo)}/blobs/${file.oid}.downloadInProgress")
            assertTrue("partial shorter than 2.5 GiB: ${partial.length()}", partial.length() >= threshold)
            assertTrue("download finished before cancellation", partial.length() < file.sizeBytes)
            val partialBytes = partial.length()

            var verifyingAt: Long? = null
            var downloadingAt: Long? = null
            instance.download(verifyRef).collect { progress ->
                if (progress.phase == DownloadPhase.Verifying && verifyingAt == null)
                    verifyingAt = SystemClock.elapsedRealtime()
                if (progress.phase == DownloadPhase.Downloading && verifyingAt != null && downloadingAt == null)
                    downloadingAt = SystemClock.elapsedRealtime()
            }
            val verifyMillis = requireNotNull(downloadingAt) - requireNotNull(verifyingAt)
            Log.i("LlamaBroHubMeasure", "verifyBytes=$partialBytes verifyMillis=$verifyMillis")
            assertTrue("verification time was not measured", verifyMillis > 0)
        } finally {
            root.deleteRecursively()
        }
    }
}
