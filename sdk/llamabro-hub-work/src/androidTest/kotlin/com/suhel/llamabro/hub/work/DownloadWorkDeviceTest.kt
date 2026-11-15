package com.suhel.llamabro.hub.work

import android.app.Notification
import android.app.NotificationManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import com.suhel.llamabro.DownloadPhase
import com.suhel.llamabro.DownloadProgress
import com.suhel.llamabro.hub.DownloadState
import com.suhel.llamabro.hub.HfModel
import com.suhel.llamabro.hub.HubConfig
import com.suhel.llamabro.hub.HubException
import com.suhel.llamabro.hub.LlamaBroHub
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * What the JVM tests can't reach without Robolectric: WorkManager's KEEP and cancel, the default notifications on a
 * real Context, and the factory's public constructor over a real hub. No network and no model.
 */
@RunWith(AndroidJUnit4::class)
class DownloadWorkDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val model = HfModel("unsloth/Qwen3.5-0.8B-GGUF:Q4_K_M")

    @Test
    fun enqueueKeepsTheQueuedDownloadAndCancelStopsIt() {
        val hub = LlamaBroHub.create(context, HubConfig { cacheDir = File(context.cacheDir, "hub-work-keep") })
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration
                .Builder()
                .setExecutor(SynchronousExecutor())
                .setWorkerFactory(LlamaBroWorkerFactory(hub))
                .build(),
        )
        val work = WorkManager.getInstance(context)
        // The test WorkManager holds constrained work until told the network is there, so both stay queued.
        work.enqueueDownload(model).result.get()
        work.enqueueDownload(model).result.get()
        work.enqueueDownload(model, mmproj = false).result.get()

        val queued = work.getWorkInfosForUniqueWork(downloadWorkName(model)).get()
        assertEquals(1, queued.size)
        assertEquals(1, work.getWorkInfosForUniqueWork(downloadWorkName(model, mmproj = false)).get().size)

        work.cancelDownload(model).result.get()
        assertEquals(WorkInfo.State.CANCELLED, work.getWorkInfoById(queued.single().id).get()!!.state)
    }

    @Test
    fun factoryRunsTheDownloadOnTheAppsHub() =
        runBlocking {
            // Nothing listens on the discard port: the download fails, and the app's own hub records it.
            val hub =
                LlamaBroHub.create(
                    context,
                    HubConfig {
                        cacheDir = File(context.cacheDir, "hub-work-factory")
                        endpoint = "http://127.0.0.1:9"
                    },
                )
            val worker =
                TestListenableWorkerBuilder
                    .from(context, DownloadWorker::class.java)
                    .setWorkerFactory(LlamaBroWorkerFactory(hub, Silent))
                    .setInputData(DownloadInput(model, mmproj = true, draft = null).toData())
                    .build()

            assertNotEquals(ListenableWorker.Result.success(), worker.doWork())
            assertTrue(hub.progress(model).value is DownloadState.Failed)
        }

    @Test
    fun defaultNotificationsBuild() {
        val notifications = DownloadNotifications()
        val download =
            RunningDownload(
                model,
                DownloadProgress(DownloadPhase.Downloading, 512L shl 20, 1L shl 30, etaSeconds = 200),
            ) { WorkManager.getInstance(context).createCancelPendingIntent(java.util.UUID.randomUUID()) }

        val running = notifications.downloading(context, download)
        assertEquals(DownloadNotifications.CHANNEL_ID, running.channelId)
        assertEquals("Qwen3.5-0.8B Q4_K_M", running.extras.getString(Notification.EXTRA_TITLE))
        assertEquals(50, running.extras.getInt(Notification.EXTRA_PROGRESS))
        assertEquals(1, running.actions.size)
        assertNotNull(
            context
                .getSystemService(
                    NotificationManager::class.java,
                ).getNotificationChannel(DownloadNotifications.CHANNEL_ID),
        )

        val finished = notifications.finished(context, model)
        assertEquals("Downloaded", finished?.extras?.getString(Notification.EXTRA_TITLE))
        val failed = notifications.failed(context, model, networkError())
        assertEquals("Download failed", failed?.extras?.getString(Notification.EXTRA_TITLE))
    }

    private object Silent : DownloadNotifications() {
        override fun finished(context: android.content.Context, model: HfModel): Notification? = null

        override fun failed(context: android.content.Context, model: HfModel, error: Exception): Notification? = null
    }

    /** HubException's constructors are internal to the hub; the test reaches one by reflection. */
    private fun networkError(): HubException =
        HubException.Network::class.java
            .getDeclaredConstructor(String::class.java, Throwable::class.java, Boolean::class.javaPrimitiveType)
            .apply { isAccessible = true }
            .newInstance("connection refused", null, true)
}
