package com.suhel.llamabro.demo

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Keeps the app alive while a reply is still being written after the person left it (D59): a short foreground service
 * (`shortService`, at most about 3 minutes, no special permission) with a "Writing a reply…" notification and a Stop
 * action. It ends with the reply; when Android's limit comes first, the reply stops as Stop would stop it, keeping
 * what arrived. It is never used to hold an idle model: [BackgroundRelease] frees that.
 */
class ReplyService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val engine get() = (application as DemoApp).container.engine

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            engine.stopAllReplies()
            return START_NOT_STICKY
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(this), shortServiceType())
        // the reply ends (done, stopped or failed): nothing left to keep alive
        scope.launch {
            engine.replying.first { !it }
            stopSelf()
        }
        return START_NOT_STICKY
    }

    /** Android's limit for a short service (API 34); the reply stops, and what arrived is kept. */
    override fun onTimeout(startId: Int) {
        engine.stopAllReplies()
        stopSelf()
    }

    /** The same limit from API 35 on. */
    override fun onTimeout(startId: Int, fgsType: Int) = onTimeout(startId)

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "replies"
        private const val NOTIFICATION_ID = 7_200
        private const val ACTION_STOP = "com.suhel.llamabro.demo.STOP_REPLY"

        /**
         * Starts the service as the app leaves the screen with a reply under way. Android allows a foreground service
         * to start as an app leaves a visible state, not later; if it refuses, the reply goes on unprotected as it
         * did before.
         */
        fun startIfReplying(context: Context, replying: Boolean) {
            if (!replying) return
            try {
                context.startForegroundService(Intent(context, ReplyService::class.java))
            } catch (e: IllegalStateException) {
                // ForegroundServiceStartNotAllowedException (API 31+) is an IllegalStateException
                Log.w("ReplyService", "not started: ${e.javaClass.simpleName}")
            }
        }

        /** Back on screen: the app is visible again and needs no service. */
        fun stop(context: Context) {
            context.stopService(Intent(context, ReplyService::class.java))
        }

        private fun shortServiceType(): Int =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE
            } else {
                0
            }

        private fun notification(context: Context): Notification {
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, "Replies", NotificationManager.IMPORTANCE_LOW),
            )
            val open =
                PendingIntent.getActivity(
                    context,
                    NOTIFICATION_ID,
                    Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            val stop =
                PendingIntent.getService(
                    context,
                    NOTIFICATION_ID,
                    Intent(context, ReplyService::class.java).setAction(ACTION_STOP),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            return NotificationCompat
                .Builder(context, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_notify_chat)
                .setContentTitle("Writing a reply…")
                .setContentText("It stops if it runs for more than a few minutes in the background")
                .setContentIntent(open)
                .addAction(0, "Stop", stop)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build()
        }
    }
}

/**
 * Observes the process lifecycle: leaving the screen while [replying] starts [ReplyService], coming back stops it.
 * Android lets a foreground service start as an app leaves a visible state, which is this moment.
 */
class ReplyKeeper(private val context: Context, private val replying: () -> Boolean) : DefaultLifecycleObserver {
    override fun onStop(owner: LifecycleOwner) = ReplyService.startIfReplying(context, replying())

    override fun onStart(owner: LifecycleOwner) = ReplyService.stop(context)
}
