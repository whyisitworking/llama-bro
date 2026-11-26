package com.suhel.llamabro.demo

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import com.suhel.llamabro.demo.models.Names
import com.suhel.llamabro.demo.server.NetworkState
import com.suhel.llamabro.demo.server.PairingApprovals
import com.suhel.llamabro.demo.server.ServingState
import com.suhel.llamabro.demo.ui.server.ServerFormat
import com.suhel.llamabro.hub.HfModel
import com.suhel.llamabro.server.PairingRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * What the service reads: where serving is, the model served, the network and the battery, and how to stop it; and
 * the computer asking to pair, whether the app is [inFront] (where its dialog asks instead), and how to answer.
 */
class ServingFeed(
    val state: StateFlow<ServingState>,
    val served: StateFlow<HfModel?>,
    val network: NetworkState,
    val stop: suspend () -> Unit,
    val pairing: StateFlow<PairingRequest?>,
    val inFront: Flow<Boolean>,
    val answerPairing: (PairingRequest, Boolean) -> Unit,
)

/**
 * Keeps the app alive while it serves, with the person in another app and the screen off (docs/design/server.md §3):
 * a foreground service of type `specialUse` with a "Serving <model> on <URL>" notification and a Stop action, holding
 * a partial wake lock and a Wi-Fi lock for as long as it runs, without which the CPU and the Wi-Fi radio nap. It runs
 * from the start of serving to its end ([keepServing] starts it, in front: Android 12 refuses a foreground service
 * started from the background); there is no time limit, as the notification and Stop are the person's control. When
 * the Wi-Fi goes the server goes on (it is bound to every address) and the notification says so until it is back.
 * It holds no model itself: [com.suhel.llamabro.demo.chat.BackgroundRelease] frees nothing while serving.
 * A computer asking to pair while the app is behind another gets a high-importance notification of its own, with the
 * code, the address and Approve and Deny (docs/design/server.md §6, D174); in front, the app's dialog asks instead.
 */
class ServerService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val feed by lazy { (feedOf ?: ::containerFeed)(this) }
    private var watching = false

    @VisibleForTesting internal var wakeLock: PowerManager.WakeLock? = null

    @VisibleForTesting internal var wifiLock: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_APPROVE || intent?.action == ACTION_DENY) {
            answer(intent, approved = intent.action == ACTION_APPROVE)
            if (!watching) stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_STOP) {
            scope.launch {
                feed.stop()
                // started by Stop alone, in a process that was not serving: nothing is left to keep alive
                if (!watching) stopSelf()
            }
            return START_NOT_STICKY
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(), specialUseType())
        if (!watching) {
            watching = true
            holdLocks()
            scope.launch { watch() }
            scope.launch { endWithServing() }
            scope.launch { askToPair() }
        }
        return START_NOT_STICKY
    }

    /** The notification follows the state, the address and the charger; the end of serving ends the service. */
    private suspend fun watch() {
        combine(feed.state, feed.served, feed.network.addresses, feed.network.charging, ::Facts).collect { facts ->
            if (facts.state !is ServingState.Off && facts.state !is ServingState.Failed) {
                getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(facts))
            }
        }
    }

    /** The pairing question, posted while the app is behind another and withdrawn once answered or in front. */
    private suspend fun askToPair() {
        val manager = getSystemService(NotificationManager::class.java)
        combine(feed.pairing, feed.inFront) { request, front -> request.takeIf { !front } }
            .distinctUntilChanged()
            .collect { request ->
                if (request ==
                    null
                ) {
                    manager.cancel(PAIRING_ID)
                } else {
                    manager.notify(PAIRING_ID, pairingNotification(request))
                }
            }
    }

    /** An action's answer, for the request it was posted for only: one that has expired since answers nothing. */
    private fun answer(intent: Intent, approved: Boolean) {
        val asked = feed.pairing.value ?: return
        if (asked.code == intent.getStringExtra(EXTRA_CODE) && asked.client == intent.getStringExtra(EXTRA_CLIENT)) {
            feed.answerPairing(asked, approved)
        }
    }

    private fun pairingNotification(request: PairingRequest): Notification {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                PAIRING_CHANNEL,
                getString(R.string.pairing_channel),
                NotificationManager.IMPORTANCE_HIGH,
            ),
        )

        fun action(action: String, label: Int) =
            NotificationCompat.Action(
                0,
                getString(label),
                PendingIntent.getService(
                    this,
                    // one per request, so a new request never rewrites the extras of an old one's actions
                    (action + request.code + request.client).hashCode(),
                    Intent(this, ServerService::class.java)
                        .setAction(action)
                        .putExtra(EXTRA_CODE, request.code)
                        .putExtra(EXTRA_CLIENT, request.client),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
        val open =
            PendingIntent.getActivity(
                this,
                PAIRING_ID,
                Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        val client = listOfNotNull(request.client, ServerFormat.device(request.userAgent)).joinToString(" · ")
        return NotificationCompat
            .Builder(this, PAIRING_CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(getString(R.string.pairing_title, request.code))
            .setContentText(getString(R.string.pairing_text, client))
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(open)
            .addAction(action(ACTION_APPROVE, R.string.pairing_approve))
            .addAction(action(ACTION_DENY, R.string.pairing_deny))
            // the question lasts two minutes at most; the server withdraws it then, and this goes with it
            .setTimeoutAfter(PairingApprovals.EXPIRY_MILLIS)
            .build()
    }

    /**
     * Ends the service when serving has ended for good: a restart goes through Off to Starting, and the Server page
     * asks for the service only when serving begins, so an Off that Starting follows must leave it running.
     */
    private suspend fun endWithServing() {
        feed.state.collectLatest { state ->
            if (state is ServingState.Off || state is ServingState.Failed) {
                delay(SETTLE_MILLIS)
                stopSelf()
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        getSystemService(NotificationManager::class.java).cancel(PAIRING_ID)
        releaseLocks()
        super.onDestroy()
    }

    @SuppressLint("WakelockTimeout") // serving is explicit and visible, with its notification and Stop; no time limit
    private fun holdLocks() {
        wakeLock =
            getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, LOCK_TAG)
                .apply {
                    setReferenceCounted(false)
                    acquire()
                }
        wifiLock =
            applicationContext
                .getSystemService(WifiManager::class.java)
                .createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, LOCK_TAG)
                .apply {
                    setReferenceCounted(false)
                    acquire()
                }
    }

    private fun releaseLocks() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wifiLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        wifiLock = null
    }

    private class Facts(
        val state: ServingState,
        val served: HfModel?,
        val addresses: List<String>,
        val charging: Boolean,
    )

    private fun notification(): Notification =
        notification(
            Facts(
                feed.state.value,
                feed.served.value,
                feed.network.addresses.value,
                feed.network.charging.value,
            ),
        )

    private fun notification(facts: Facts): Notification {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.serving_channel), NotificationManager.IMPORTANCE_LOW),
        )
        val open =
            PendingIntent.getActivity(
                this,
                NOTIFICATION_ID,
                Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        val stop =
            PendingIntent.getService(
                this,
                NOTIFICATION_ID,
                Intent(this, ServerService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        return NotificationCompat
            .Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(title(facts))
            .setContentText(getString(if (facts.charging) R.string.serving_charging else R.string.serving_not_charging))
            .setContentIntent(open)
            .addAction(0, getString(R.string.serving_stop), stop)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
            // a foreground service's notification can be swiped away from Android 14 on; this one is for Stop to end
            .also { it.flags = it.flags or Notification.FLAG_NO_CLEAR }
    }

    private fun title(facts: Facts): String {
        val name = (facts.served ?: (facts.state as? ServingState.Serving)?.model)?.let(Names::of).orEmpty()
        val serving = facts.state as? ServingState.Serving
        return when {
            serving == null -> getString(R.string.serving_title_loading, name)
            facts.addresses.isEmpty() -> getString(R.string.serving_title_offline, name)
            else -> getString(R.string.serving_title, name, "http://${facts.addresses.first()}:${serving.port}")
        }
    }

    companion object {
        private const val CHANNEL = "serving"
        private const val NOTIFICATION_ID = 7_300
        private const val ACTION_STOP = "com.suhel.llamabro.demo.STOP_SERVING"
        private const val LOCK_TAG = "LlamaBro:serving"
        private const val PAIRING_CHANNEL = "pairing"
        private const val PAIRING_ID = 7_301
        private const val ACTION_APPROVE = "com.suhel.llamabro.demo.APPROVE_PAIRING"
        private const val ACTION_DENY = "com.suhel.llamabro.demo.DENY_PAIRING"
        private const val EXTRA_CODE = "code"
        private const val EXTRA_CLIENT = "client"

        /** How long Off or Failed must last to count as the end of serving, not the middle of a restart. */
        const val SETTLE_MILLIS = 1_000L

        /** Replaced by tests that feed the service without an app container. */
        @VisibleForTesting internal var feedOf: ((Context) -> ServingFeed)? = null

        private fun containerFeed(context: Context): ServingFeed {
            val container = (context.applicationContext as DemoApp).container
            return ServingFeed(
                container.server.state,
                container.server.served,
                container.network,
                container.server::stop,
                container.server.pairing,
                // started or resumed: a window of the app is on screen, where the dialog asks
                ProcessLifecycleOwner
                    .get()
                    .lifecycle.currentStateFlow
                    .map { it.isAtLeast(Lifecycle.State.STARTED) },
                container.server::answerPairing,
            )
        }

        /** Starts the service; to be called while the app is in front, as serving starts. */
        fun start(context: Context) {
            try {
                context.startForegroundService(Intent(context, ServerService::class.java))
            } catch (e: IllegalStateException) {
                // ForegroundServiceStartNotAllowedException (API 31+) is an IllegalStateException: serving goes on
                // without the service's protection, as it would if the app were never left
                Log.w("ServerService", "not started: ${e.javaClass.simpleName}")
            }
        }

        private fun specialUseType(): Int =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            } else {
                0
            }
    }
}

/**
 * Starts [ServerService] each time [state] turns to Starting or Serving from Off or Failed, which happens on the
 * Server page, so the app is in front; the service ends itself when serving does. Never returns.
 */
suspend fun keepServing(context: Context, state: Flow<ServingState>) {
    state
        .map { it is ServingState.Starting || it is ServingState.Serving }
        .distinctUntilChanged()
        .collect { if (it) ServerService.start(context) }
}
