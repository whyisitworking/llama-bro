package com.suhel.llamabro.demo.models

import android.app.Notification
import android.content.Context
import android.content.pm.ServiceInfo
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.suhel.llamabro.demo.DemoApp
import com.suhel.llamabro.hub.HfModel
import com.suhel.llamabro.hub.HubException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.TimeUnit

/** Starts and stops a model's download. */
interface DownloadScheduler {
    fun enqueue(model: HfModel)

    fun cancel(model: HfModel)
}

/** Unique work `download:<key>`, kept when one is already queued or running, over the network, expedited. */
class WorkDownloadScheduler(private val manager: Lazy<WorkManager>) : DownloadScheduler {
    constructor(manager: WorkManager) : this(lazyOf(manager))

    override fun enqueue(model: HfModel) {
        val request =
            OneTimeWorkRequestBuilder<DownloadWorker>()
                .setInputData(Data.Builder().putString(DownloadWorker.REF, model.toString()).build())
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
        manager.value.enqueueUniqueWork(uniqueName(model), ExistingWorkPolicy.KEEP, request)
    }

    override fun cancel(model: HfModel) {
        manager.value.cancelUniqueWork(uniqueName(model))
    }

    private fun uniqueName(model: HfModel) = "download:$model"
}

/**
 * Downloads one model through the hub, which resumes a partial, verifies every file and shares progress with the
 * app. One transfer runs at a time (D44): a second worker waits, in this process, for the first.
 */
class DownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val model =
            try {
                HfModel.parse(inputData.getString(REF) ?: return Result.failure())
            } catch (_: IllegalArgumentException) {
                return Result.failure()
            }
        val container = (applicationContext as DemoApp).container
        var foreground = promote(model, DownloadNotifications.waiting(applicationContext, model))
        return try {
            transferLock.withLock {
                if (foreground) foreground = promote(model, DownloadNotifications.preparing(applicationContext, model))
                container.hub.download(model).collect { progress ->
                    if (foreground) {
                        foreground =
                            promote(
                                model,
                                DownloadNotifications.progress(
                                    applicationContext,
                                    model,
                                    progress.bytesDone,
                                    progress.bytesTotal,
                                    progress.etaSeconds,
                                ),
                            )
                    }
                }
            }
            container.models.changed()
            Result.success()
        } catch (e: HubException) {
            if (e.retryable && runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
        }
    }

    /** Expedited work on Android 11 and older asks for this before it runs. */
    override suspend fun getForegroundInfo(): ForegroundInfo {
        val model = HfModel.parse(inputData.getString(REF).orEmpty())
        return info(model, DownloadNotifications.preparing(applicationContext, model))
    }

    /**
     * Runs the work as a foreground service. Android 12+ refuses that while the app is in the background (a retry after
     * backoff, say); the download then goes on as ordinary background work, so this returns false.
     */
    private suspend fun promote(model: HfModel, notification: Notification): Boolean =
        try {
            setForeground(info(model, notification))
            true
        } catch (cancelled: CancellationException) {
            throw cancelled // a CancellationException is an IllegalStateException: never mistake one for a refusal
        } catch (error: IllegalStateException) {
            Log.w("DownloadWorker", "foreground not allowed now; continuing in the background: ${error.message}")
            false
        }

    private fun info(model: HfModel, notification: Notification) =
        ForegroundInfo(DownloadNotifications.id(model), notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)

    companion object {
        const val REF = "ref"

        /** Builder's call, settled here: five tries with WorkManager's backoff, then the hub's state says Failed. */
        private const val MAX_ATTEMPTS = 5

        private val transferLock = Mutex()
    }
}
