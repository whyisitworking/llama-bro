package com.suhel.llamabro.demo.models

import android.app.Notification
import android.content.Context
import androidx.concurrent.futures.CallbackToFutureAdapter
import androidx.test.core.app.ApplicationProvider
import androidx.work.ForegroundInfo
import androidx.work.ForegroundUpdater
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import com.google.common.util.concurrent.ListenableFuture
import com.suhel.llamabro.demo.testing.TestAppRule
import com.suhel.llamabro.demo.testing.demoRules
import com.suhel.llamabro.hub.HfModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The download worker against the fake Hugging Face server that `demo/scripts/check.sh --device` starts on
 * 127.0.0.1:18190 (corpus `demo/scripts/hub-corpus.json`, reached through adb reverse). The hub is the real one.
 */
class DownloadWorkerTest {
    private val app = TestAppRule()

    @get:Rule val rules = demoRules(app)

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val big = HfModel.parse("test/worker:Q4_K_M")
    private val b = HfModel.parse("test/worker-b:Q4_K_M")
    private val c = HfModel.parse("test/worker-c:Q4_K_M")

    @Before fun slowServer() {
        post("/_reset", "{}")
        post("/_control", """{"faults":[{"kind":"slow","rate_mib_s":8}]}""")
    }

    @After fun cleanUp() =
        runBlocking(Dispatchers.IO) {
            post("/_reset", "{}")
            for (m in listOf(big, b, c, HfModel.parse("test/small:Q4_K_M"))) app.container.hub.delete(m)
        }

    private class Foreground : ForegroundUpdater {
        val infos = CopyOnWriteArrayList<ForegroundInfo>()

        override fun setForegroundAsync(
            context: Context,
            id: UUID,
            foregroundInfo: ForegroundInfo,
        ): ListenableFuture<Void?> {
            infos += foregroundInfo
            return CallbackToFutureAdapter.getFuture {
                it.set(null)
                "foreground"
            }
        }
    }

    private fun worker(model: HfModel, foreground: Foreground = Foreground()) =
        TestListenableWorkerBuilder<DownloadWorker>(context)
            .setInputData(workDataOf(DownloadWorker.REF to model.toString()))
            .setForegroundUpdater(foreground)
            .build()

    private fun post(path: String, body: String) {
        val c = URL("http://127.0.0.1:18190$path").openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.doOutput = true
        c.outputStream.use { it.write(body.toByteArray()) }
        c.inputStream.use { it.readBytes() }
        c.disconnect()
    }

    private fun serverLog(): List<JSONObject> {
        val text = URL("http://127.0.0.1:18190/_log").openStream().use { String(it.readBytes()) }
        val a = JSONArray(text)
        return (0 until a.length()).map { a.getJSONObject(it) }
    }

    private fun cdnGets() = serverLog().filter { it.getString("host") == "cdn" && it.getString("method") == "GET" }

    @Test fun workerDownloadsAndVerifies() =
        runBlocking(Dispatchers.IO) {
            val foreground = Foreground()
            val result = worker(big, foreground).doWork()
            assertEquals(ListenableWorker.Result.success(), result)
            assertEquals(
                DownloadStatus.Complete,
                app.container.hub
                    .progress(big)
                    .value,
            )
            assertTrue(
                app.container.hub
                    .cached()
                    .any { it.model == big },
            )
            // the notification was handed to the foreground service: a waiting line first, then progress
            assertTrue(foreground.infos.isNotEmpty())
            val last: Notification = foreground.infos.last().notification
            assertEquals(DownloadNotifications.CHANNEL, last.channelId)
            assertTrue(
                foreground.infos.any {
                    it.notification.extras
                        .getString(Notification.EXTRA_TITLE)!!
                        .startsWith("Downloading")
                },
            )
        }

    @Test fun workerResumesFromPartial() =
        runBlocking(Dispatchers.IO) {
            val total = 25_165_824L
            val first = launch { worker(big).doWork() }
            withTimeout(60_000) {
                while (true) {
                    val s =
                        app.container.hub
                            .progress(big)
                            .value
                    if (s is DownloadStatus.Downloading && s.bytesDone >= total / 3) break
                    delay(100)
                }
            }
            first.cancelAndJoin() // what pause does: the work stops, the partial stays
            withTimeout(10_000) {
                app.container.hub
                    .progress(big)
                    .first { it is DownloadStatus.Partial }
            }
            val before = cdnGets().size

            assertEquals(ListenableWorker.Result.success(), worker(big).doWork())

            val resumed = cdnGets().drop(before).first()
            val range = resumed.optString("range")
            assertTrue("the second GET carries a Range, was: $range", range.startsWith("bytes="))
            assertTrue("it resumes past 0, was: $range", range.removePrefix("bytes=").removeSuffix("-").toLong() > 0)
            assertEquals(
                DownloadStatus.Complete,
                app.container.hub
                    .progress(big)
                    .value,
            ) // the hub verified every byte
        }

    @Test fun twoGetsRunOneAtATime() =
        runBlocking(Dispatchers.IO) {
            val one = async { worker(b).doWork() }
            val two = async { worker(c).doWork() }
            assertEquals(ListenableWorker.Result.success(), one.await())
            assertEquals(ListenableWorker.Result.success(), two.await())
            // D44: the second waits for the first, so the server never saw their blobs interleaved
            val runs = mutableListOf<String>()
            for (g in cdnGets()) {
                val blob = g.getString("path")
                if (runs.lastOrNull() != blob) runs += blob
            }
            assertEquals("each blob's requests are one run: $runs", 2, runs.size)
        }

    // ---- why a download stops: each failure the hub names reaches the app as a plain kind ----

    private fun failedWith(model: HfModel, expected: DownloadFailure.Kind, retryable: Boolean) =
        runBlocking(Dispatchers.IO) {
            val result = worker(model).doWork()
            val status =
                app.container.hub
                    .progress(model)
                    .value
            assertTrue("a failed download says why, was: $status", status is DownloadStatus.Failed)
            val failure = (status as DownloadStatus.Failed).failure
            assertEquals(expected, failure.kind)
            assertEquals(retryable, failure.retryable)
            assertEquals(
                if (retryable) ListenableWorker.Result.retry() else ListenableWorker.Result.failure(),
                result,
            )
        }

    @Test fun aRepoThatIsNotThereIsNotFound() =
        failedWith(HfModel.parse("test/missing:Q4_K_M"), DownloadFailure.Kind.NotFound, retryable = false)

    @Test fun aGatedRepoWithoutATokenIsUnauthorized() =
        failedWith(HfModel.parse("test/gated:Q4_K_M"), DownloadFailure.Kind.Unauthorized, retryable = false)

    @Test fun aServerThatSaysSlowDownIsRateLimited() {
        post("/_control", """{"faults":[{"kind":"status","host":"api","match":"tree","status":429,"times":-1}]}""")
        failedWith(HfModel.parse("test/small:Q4_K_M"), DownloadFailure.Kind.RateLimited, retryable = true)
    }

    @Test fun aFileThatArrivesChangedFailsItsCheck() {
        post("/_control", """{"faults":[{"kind":"corrupt","path":"model-Q4_K_M.gguf","offset":2}]}""")
        failedWith(HfModel.parse("test/small:Q4_K_M"), DownloadFailure.Kind.IntegrityMismatch, retryable = true)
    }
}
