package com.suhel.llamabro.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobScheduler
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.WorkManager
import com.suhel.llamabro.DownloadProgress

internal object DownloadNotifications {
    const val ID = 73
    private const val CHANNEL = "downloads"
    private const val CANCEL = "com.suhel.llamabro.download.CANCEL"

    fun build(
        context: Context,
        ref: String,
        progress: DownloadProgress,
        userInitiatedJob: Boolean = false,
    ): Notification {
        val done = progress.bytesDone / 1_048_576
        val total = progress.bytesTotal / 1_048_576
        val eta = progress.etaSeconds?.let { "${it}s left" } ?: "ETA pending"
        val file = progress.currentFile ?: "Preparing"
        val percent =
            if (progress.bytesTotal > 0) {
                (progress.bytesDone * 100 / progress.bytesTotal).toInt().coerceIn(0, 100)
            } else {
                null
            }
        return build(context, ref, "$file · $done/$total MB · $eta", percent, userInitiatedJob)
    }

    /** Shown at once when work starts, before the Hub has resolved anything. */
    fun preparing(context: Context, ref: String, userInitiatedJob: Boolean = false): Notification =
        build(context, ref, "Preparing $ref", null, userInitiatedJob)

    private fun build(
        context: Context,
        ref: String,
        detail: String,
        percent: Int?,
        userInitiatedJob: Boolean,
    ): Notification {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Downloads", NotificationManager.IMPORTANCE_LOW))
        val cancel =
            Intent(context, CancelDownloadReceiver::class.java).apply {
                action = CANCEL
                putExtra("ref", ref)
                putExtra("userInitiatedJob", userInitiatedJob)
            }
        val requestCode = ref.hashCode() xor if (userInitiatedJob) 1 else 0
        val pending =
            PendingIntent.getBroadcast(
                context,
                requestCode,
                cancel,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        return NotificationCompat
            .Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Downloads")
            .setContentText(detail)
            .setStyle(NotificationCompat.BigTextStyle().bigText(detail))
            .setProgress(100, percent ?: 0, percent == null)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancel", pending)
            .build()
    }
}

class CancelDownloadReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val ref = intent.getStringExtra("ref") ?: return
        if (intent.getBooleanExtra("userInitiatedJob", false) &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
        ) {
            val scheduler = context.getSystemService(JobScheduler::class.java)
            scheduler.cancel(UserDownloadJobService.jobId(ref))
        } else {
            WorkManager.getInstance(context).cancelUniqueWork(ref)
        }
    }
}
