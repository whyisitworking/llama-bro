package com.suhel.llamabro.download

import android.app.Notification
import android.content.Context
import android.content.pm.ServiceInfo
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.suhel.llamabro.hub.HfModel
import com.suhel.llamabro.hub.HubException
import kotlinx.coroutines.CancellationException

class ModelDownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val ref = inputData.getString("ref") ?: return Result.failure()
        val model =
            try {
                HfModel.parse(ref)
            } catch (_: IllegalArgumentException) {
                return Result.failure()
            }
        var foreground = promote(DownloadNotifications.preparing(applicationContext, ref))
        return try {
            (applicationContext as DownloadApplication).hub().download(model).collect { progress ->
                if (foreground) foreground = promote(DownloadNotifications.build(applicationContext, ref, progress))
            }
            Result.success()
        } catch (error: HubException) {
            if (error.retryable) Result.retry() else Result.failure()
        }
    }

    /**
     * Runs the work as a foreground service. Android 12+ refuses that while the app is in the background (for example a
     * retry after backoff); the download then goes on as ordinary background work, so this returns false.
     */
    private suspend fun promote(notification: Notification): Boolean =
        try {
            setForeground(
                ForegroundInfo(DownloadNotifications.ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC),
            )
            true
        } catch (cancelled: CancellationException) {
            throw cancelled // a CancellationException is an IllegalStateException: never mistake one for a refusal
        } catch (error: IllegalStateException) {
            Log.w("ModelDownloadWorker", "foreground not allowed now; continuing in the background", error)
            false
        }
}
