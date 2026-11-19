package com.suhel.llamabro.hub.work

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.icu.text.MeasureFormat
import android.icu.util.Measure
import android.icu.util.MeasureUnit
import android.text.format.Formatter
import androidx.core.app.NotificationCompat
import com.suhel.llamabro.DownloadPhase
import com.suhel.llamabro.DownloadProgress
import com.suhel.llamabro.hub.HfModel
import com.suhel.llamabro.hub.HubException
import java.util.Locale

/**
 * What a background download shows. The defaults work with no setup: while it runs, the model's name, a progress bar
 * with the size and the time left, and a Cancel action; when it ends, a short "Downloaded" or "Download failed". They
 * use the [CHANNEL_ID] channel ("Downloads"), which the SDK creates.
 *
 * To change any of it, subclass and override: [downloading] for the ongoing notification (start from
 * [downloadingBuilder] to change only the icon or the tap target), [finished] and [failed] for the end, returning null
 * to show none. Pass the subclass to [LlamaBroWorkerFactory].
 *
 * On Android 13+ the notifications show only once the app holds `POST_NOTIFICATIONS`, which the SDK declares but never
 * requests: asking is the app's call. Without it the download runs all the same.
 */
public open class DownloadNotifications {
    /**
     * The ongoing notification, shown while the download runs as a foreground service: built once when the work
     * starts ([RunningDownload.progress] null) and again on progress, paced to about once a second (a change of phase
     * and the last update always come through), so keep it cheap.
     */
    public open fun downloading(context: Context, download: RunningDownload): Notification =
        downloadingBuilder(context, download).build()

    /** Shown when the download completes; null shows nothing. */
    public open fun finished(context: Context, model: HfModel): Notification? = finishedBuilder(context, model).build()

    /**
     * Shown when the download gives up: [error] is not retryable (usually a [HubException]), or the tries that moved no
     * bytes ran out. Null shows nothing. A cancelled download shows neither this nor [finished].
     */
    public open fun failed(context: Context, model: HfModel, error: Exception): Notification? =
        failedBuilder(context, model, error).build()

    /** The model's name as the defaults show it: the repository's name without `-GGUF`, and the tag or file. */
    protected open fun displayName(model: HfModel): String {
        val repo =
            model.repo
                .substringAfter('/')
                .removeSuffix("-GGUF")
                .removeSuffix("-gguf")
        return model.file?.substringAfterLast('/') ?: listOfNotNull(repo, model.tag).joinToString(" ")
    }

    /** The default ongoing notification, unbuilt. */
    protected fun downloadingBuilder(context: Context, download: RunningDownload): NotificationCompat.Builder {
        val progress = download.progress
        val text = progress?.let { detail(context, it) } ?: context.getString(R.string.llamabro_download_preparing)
        val percent = progress?.takeIf { it.bytesTotal > 0 && it.phase != DownloadPhase.Resolving }?.let(::percent)
        return base(context, android.R.drawable.stat_sys_download)
            .setContentTitle(displayName(download.model))
            .setContentText(text)
            .setProgress(PERCENT, percent ?: 0, percent == null)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                context.getString(R.string.llamabro_download_cancel),
                download.cancelIntent,
            )
    }

    /** A notification for work whose model can't be read (input from an older version): never shown for long. */
    internal fun preparing(context: Context): Notification =
        base(context, android.R.drawable.stat_sys_download)
            .setContentTitle(context.getString(R.string.llamabro_download_preparing))
            .setProgress(0, 0, true)
            .build()

    /** The default "Downloaded" notification, unbuilt. */
    protected fun finishedBuilder(context: Context, model: HfModel): NotificationCompat.Builder =
        base(context, android.R.drawable.stat_sys_download_done)
            .setContentTitle(context.getString(R.string.llamabro_download_finished))
            .setContentText(displayName(model))
            .setAutoCancel(true)

    /** The default "Download failed" notification, unbuilt. */
    @Suppress("UnusedParameter") // the error is there for an override that says why
    protected fun failedBuilder(context: Context, model: HfModel, error: Exception): NotificationCompat.Builder =
        base(context, android.R.drawable.stat_notify_error)
            .setContentTitle(context.getString(R.string.llamabro_download_failed))
            .setContentText(displayName(model))
            .setAutoCancel(true)

    private fun base(context: Context, icon: Int): NotificationCompat.Builder {
        createChannel(context)
        return NotificationCompat
            .Builder(context, CHANNEL_ID)
            .setSmallIcon(icon)
            .setContentIntent(openApp(context))
    }

    /** "1.2 GB of 2.5 GB · 3 min left", or what the hub is doing before bytes move. */
    private fun detail(context: Context, progress: DownloadProgress): String =
        when (progress.phase) {
            DownloadPhase.Resolving -> {
                context.getString(R.string.llamabro_download_preparing)
            }

            DownloadPhase.Verifying -> {
                context.getString(R.string.llamabro_download_verifying)
            }

            DownloadPhase.Downloading, DownloadPhase.Complete -> {
                val size =
                    context.getString(
                        R.string.llamabro_download_size,
                        Formatter.formatShortFileSize(context, progress.bytesDone),
                        Formatter.formatShortFileSize(context, progress.bytesTotal),
                    )
                progress.etaSeconds?.let {
                    "$size · " +
                        context.getString(
                            R.string.llamabro_download_left,
                            timeLeft(it),
                        )
                }
                    ?: size
            }
        }

    /** The app's launcher activity, or nothing for an app without one. */
    private fun openApp(context: Context): PendingIntent? =
        context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
            PendingIntent.getActivity(context, 0, it, PendingIntent.FLAG_IMMUTABLE)
        }

    public companion object {
        /** The channel the default notifications use; [createChannel] makes it. */
        public const val CHANNEL_ID: String = "llamabro_downloads"

        /**
         * Creates the [CHANNEL_ID] channel, named "Downloads", at low importance (no sound). The defaults call it; an
         * override that posts to this channel before them should too. Calling it again does nothing.
         */
        public fun createChannel(context: Context) {
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.llamabro_downloads_channel),
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
        }

        private const val PERCENT = 100
        private const val SECONDS_PER_MINUTE = 60L
        private const val MINUTES_PER_HOUR = 60L

        private fun percent(progress: DownloadProgress): Int =
            (progress.bytesDone * PERCENT / progress.bytesTotal).toInt().coerceIn(0, PERCENT)

        /** "45 sec", "3 min", "1 hr, 5 min", in the phone's language. */
        private fun timeLeft(seconds: Long): String {
            val format = MeasureFormat.getInstance(Locale.getDefault(), MeasureFormat.FormatWidth.SHORT)
            val minutes = seconds / SECONDS_PER_MINUTE
            return when {
                minutes == 0L -> {
                    format.format(Measure(seconds, MeasureUnit.SECOND))
                }

                minutes < MINUTES_PER_HOUR -> {
                    format.format(Measure(minutes, MeasureUnit.MINUTE))
                }

                else -> {
                    format.formatMeasures(
                        Measure(minutes / MINUTES_PER_HOUR, MeasureUnit.HOUR),
                        Measure(minutes % MINUTES_PER_HOUR, MeasureUnit.MINUTE),
                    )
                }
            }
        }
    }
}

/**
 * A download in progress, as [DownloadNotifications.downloading] sees it.
 *
 * @property model the model being downloaded.
 * @property progress the hub's latest report, or null before the first one.
 */
public class RunningDownload internal constructor(
    public val model: HfModel,
    public val progress: DownloadProgress?,
    cancel: () -> PendingIntent,
) {
    /** Cancels this download's work, as [cancelDownload] does; the default notification's Cancel action sends it. */
    public val cancelIntent: PendingIntent by lazy(cancel)
}
