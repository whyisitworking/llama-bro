package com.suhel.llamabro.hub.work

import android.Manifest
import android.app.Notification
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.suhel.llamabro.DownloadPhase
import com.suhel.llamabro.DownloadProgress
import com.suhel.llamabro.hub.HubException
import kotlinx.coroutines.CancellationException

/**
 * Runs one [enqueueDownload]: collects [ModelDownloads.download] as a `dataSync` foreground service, with
 * [DownloadNotifications] showing it. A retry collects the same flow again, which resumes the partial files rather
 * than starting over (F114-F116); cancelling the work cancels the collection and keeps them.
 *
 * Only tries that moved no bytes count towards [MAX_STALLED_TRIES] ([StalledTries]): a try that got further resets
 * the count, so a long download over a flaky network keeps going. The notification and WorkInfo progress are paced to
 * about one update per [PROGRESS_INTERVAL_MS], whatever the hub's own interval; a change of phase and the last update
 * always go through.
 *
 * Internal: [LlamaBroWorkerFactory] creates it with the app's hub (D75), and apps name it only through
 * [enqueueDownload].
 */
internal class DownloadWorker(
    context: Context,
    params: WorkerParameters,
    private val downloads: ModelDownloads?,
    private val notifications: DownloadNotifications,
    private val tries: StalledTries,
    private val clock: () -> Long,
) : CoroutineWorker(context, params) {
    /**
     * WorkManager's default factory builds a worker by reflection when the app didn't register [LlamaBroWorkerFactory];
     * this one then fails with a log line that says so, rather than WorkManager's bare "could not create".
     */
    constructor(context: Context, params: WorkerParameters) :
        this(context, params, null, DownloadNotifications(), PreferencesStalledTries(context), ::monotonicMillis)

    override suspend fun doWork(): Result {
        if (downloads == null) {
            Log.e(TAG, "no hub: register LlamaBroWorkerFactory(hub) in WorkManager's Configuration")
            return Result.failure()
        }
        val input = DownloadInput.from(inputData) ?: return Result.failure()
        val notificationId = notificationId(input)
        var foreground = promote(notificationId, input, null)
        val pacer = ProgressPacer(clock, PROGRESS_INTERVAL_MS)
        var reached = 0L

        suspend fun report(progress: DownloadProgress) {
            setProgress(
                workDataOf(
                    DownloadWorkProgress.BYTES_DONE to progress.bytesDone,
                    DownloadWorkProgress.BYTES_TOTAL to progress.bytesTotal,
                ),
            )
            if (foreground) foreground = promote(notificationId, input, progress)
        }
        return try {
            downloads.download(input.model, input.mmproj, input.draft).collect { progress ->
                reached = maxOf(reached, progress.bytesDone)
                if (pacer.due(progress)) report(progress)
            }
            pacer.held()?.let { report(it) }
            tries.clear(id)
            post(notificationId, notifications.finished(applicationContext, input.model))
            Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled // stopped or cancelled: WorkManager decides what comes next, and the partials stay
        } catch (error: HubException) {
            if (error.retryable && retryAfter(reached)) Result.retry() else fail(notificationId, input, error)
        } catch (
            @Suppress("TooGenericExceptionCaught") error: Exception, // anything else still ends with "Download failed"
        ) {
            Log.e(TAG, "download of ${input.model} failed", error)
            fail(notificationId, input, error)
        }
    }

    /** Expedited work on Android 11 and older asks for this before [doWork] runs; bad input fails there, quietly. */
    override suspend fun getForegroundInfo(): ForegroundInfo {
        val input = DownloadInput.from(inputData)
        return if (input == null) {
            ForegroundInfo(
                id.hashCode(),
                notifications.preparing(applicationContext),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            foregroundInfo(notificationId(input), input, null)
        }
    }

    /**
     * Whether a retryable failure gets another try: always when this try got further than every earlier one, else
     * until [MAX_STALLED_TRIES] tries in a row moved nothing.
     */
    private fun retryAfter(reached: Long): Boolean {
        val before = tries.read(id)
        val now =
            if (reached > before.bytesReached) {
                StalledTries.Record(reached, 0)
            } else {
                StalledTries.Record(before.bytesReached, before.stalled + 1)
            }
        if (now.stalled >= MAX_STALLED_TRIES) return false
        tries.write(id, now)
        return true
    }

    private fun fail(notificationId: Int, input: DownloadInput, error: Exception): Result {
        tries.clear(id)
        post(notificationId, notifications.failed(applicationContext, input.model, error))
        return Result.failure()
    }

    /**
     * Runs the work as a foreground service. Android 12+ refuses that while the app is in the background (a retry after
     * backoff, say); the download then goes on as ordinary background work, so this returns false and the caller stops
     * asking. A denied notification permission doesn't refuse it: the service runs and its notification stays hidden.
     */
    private suspend fun promote(notificationId: Int, input: DownloadInput, progress: DownloadProgress?): Boolean =
        try {
            setForeground(foregroundInfo(notificationId, input, progress))
            true
        } catch (cancelled: CancellationException) {
            throw cancelled // a CancellationException is an IllegalStateException: never mistake one for a refusal
        } catch (refused: IllegalStateException) {
            Log.w(TAG, "foreground not allowed now; downloading in the background: ${refused.message}")
            false
        }

    private fun foregroundInfo(notificationId: Int, input: DownloadInput, progress: DownloadProgress?): ForegroundInfo {
        val download =
            RunningDownload(input.model, progress) {
                WorkManager.getInstance(applicationContext).createCancelPendingIntent(id)
            }
        return ForegroundInfo(
            notificationId,
            notifications.downloading(applicationContext, download),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
    }

    /**
     * Shows a finished or failed notification, if the app's [DownloadNotifications] made one and may post it. Tagged,
     * so the foreground notification's removal with the same id leaves it.
     */
    private fun post(notificationId: Int, notification: Notification?) {
        if (notification == null) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        NotificationManagerCompat.from(applicationContext).notify(RESULT_TAG, notificationId, notification)
    }

    private fun notificationId(input: DownloadInput): Int =
        downloadWorkName(input.model, input.mmproj, input.draft).hashCode()

    companion object {
        /** Tries in a row that moved no bytes before a retryable error counts as a failure. */
        const val MAX_STALLED_TRIES = 5

        /** Android shows only a few notification updates a second; once a second is plenty for a download. */
        const val PROGRESS_INTERVAL_MS = 1_000L

        private const val TAG = "llamabro-hub-work"
        private const val RESULT_TAG = "llamabro-download-result"
    }
}

internal fun monotonicMillis(): Long = System.nanoTime() / NANOS_PER_MILLI

private const val NANOS_PER_MILLI = 1_000_000L

/**
 * Lets an update through when its phase differs from the last one sent or [intervalMs] have passed; holds the latest
 * other one, which [held] hands back so the last update is never lost.
 */
internal class ProgressPacer(private val clock: () -> Long, private val intervalMs: Long) {
    private var sentPhase: DownloadPhase? = null
    private var sentAt = 0L
    private var pending: DownloadProgress? = null

    fun due(progress: DownloadProgress): Boolean {
        val now = clock()
        if (progress.phase != sentPhase || now - sentAt >= intervalMs) {
            sentPhase = progress.phase
            sentAt = now
            pending = null
            return true
        }
        pending = progress
        return false
    }

    fun held(): DownloadProgress? = pending.also { pending = null }
}
