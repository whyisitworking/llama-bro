package com.suhel.llamabro.demo.models

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.suhel.llamabro.demo.MainActivity
import com.suhel.llamabro.hub.HfModel

/** The foreground notification of a download (channel `downloads`): "Downloading Qwen3.5 0.8B · 41% · 2 min left". */
internal object DownloadNotifications {
    const val CHANNEL = "downloads"

    private const val ID_BASE = 7_300
    private const val ID_SPREAD_MASK = 0xFFFF
    private const val SECONDS_PER_MINUTE = 60
    private const val PERCENT_MAX = 100

    /** One per model, so two downloads never overwrite each other's line. */
    fun id(model: HfModel): Int = ID_BASE + (model.toString().hashCode() and ID_SPREAD_MASK)

    /** The model's own name from the catalog, else the repo's name. */
    fun displayName(model: HfModel): String =
        Catalog.byRepo(model.repo)?.realName
            ?: model.repo.substringAfter('/').removeSuffix("-GGUF")

    fun text(name: String, percent: Int?, etaSeconds: Long?): String {
        val parts = mutableListOf("Downloading $name")
        if (percent != null) parts += "$percent%"
        if (etaSeconds != null) parts += Format.timeLeftText(etaSeconds)
        return parts.joinToString(" · ")
    }

    fun percent(bytesDone: Long, bytesTotal: Long): Int? =
        if (bytesTotal > 0) (bytesDone * PERCENT_MAX / bytesTotal).toInt().coerceIn(0, PERCENT_MAX) else null

    fun preparing(context: Context, model: HfModel): Notification =
        build(context, model, text(displayName(model), null, null), null)

    fun waiting(context: Context, model: HfModel): Notification =
        build(context, model, "Waiting to download ${displayName(model)}", null)

    fun progress(
        context: Context,
        model: HfModel,
        bytesDone: Long,
        bytesTotal: Long,
        etaSeconds: Long?,
    ): Notification {
        val percent = percent(bytesDone, bytesTotal)
        return build(context, model, text(displayName(model), percent, etaSeconds), percent)
    }

    private fun build(context: Context, model: HfModel, line: String, percent: Int?): Notification {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Downloads", NotificationManager.IMPORTANCE_LOW),
        )
        val open =
            PendingIntent.getActivity(
                context,
                id(model),
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        return NotificationCompat
            .Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(line)
            .setContentIntent(open)
            .setProgress(PERCENT_MAX, percent ?: 0, percent == null)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }
}
