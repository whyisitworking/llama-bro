package com.suhel.llamabro.demo

import android.app.Application
import android.content.Context
import android.content.pm.ApplicationInfo
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import com.suhel.llamabro.LlamaBroConfig
import com.suhel.llamabro.LogLevel
import com.suhel.llamabro.Logger
import com.suhel.llamabro.demo.chat.BackgroundRelease
import com.suhel.llamabro.hub.HubConfig
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Open so the test application can supply its own container (androidTest `TestDemoApp`). */
open class DemoApp :
    Application(),
    Configuration.Provider {
    /** Created on first use, on whichever thread asks; building it runs no native code. */
    open val container: AppContainer by lazy { ContainerFactory.create(this) }

    override fun onCreate() {
        super.onCreate()
        onStartup()
    }

    /**
     * Starts the recovery of interrupted replies on the container's scope (the engine's first turn waits for it), reads
     * the phone's facts in the background so the Models page and a model's card have them when they open, and gives the
     * model's memory back while the app sits in the background.
     */
    protected open fun onStartup() {
        ContainerFactory.onStart(this)
        container.recoverInterrupted()
        container.readDeviceFacts()
        forgetOldDownloads()
        ProcessLifecycleOwner.get().lifecycle.addObserver(release)
        ProcessLifecycleOwner.get().lifecycle.addObserver(replyKeeper)
    }

    /**
     * What builds from before the SDK's download worker (D75) left behind: their `downloads` channel, which system
     * settings would list as a second "Downloads" beside the SDK's, and work queued for their worker class, which is
     * gone, so WorkManager would fail it once. The files it fetched stay, and the model shows as paused until Resume.
     */
    private fun forgetOldDownloads() {
        NotificationManagerCompat.from(this).deleteNotificationChannel(OLD_DOWNLOAD_CHANNEL)
        // WorkManager tags every request with its worker's class name; its first use opens its database, off the main
        // thread
        container.appScope.launch { WorkManager.getInstance(this@DemoApp).cancelAllWorkByTag(OLD_DOWNLOAD_WORKER) }
    }

    /** A reply under way as the app leaves the screen keeps the app alive until it ends (D59). */
    private val replyKeeper by lazy { ReplyKeeper(this) { container.engine.replying.value } }

    /** Frees the model a while after the app leaves the screen (Settings › Memory), or when Android needs it. */
    private val release by lazy {
        BackgroundRelease(
            container.appScope,
            keepMillis = {
                container.settings.settings
                    .first()
                    .keepLoaded.millis
            },
        ) { container.engine.releaseModelIfIdle() }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        release.onTrimMemory(level)
    }

    /**
     * The configs are properties, built once: `create` returns the same object for an equal config, and a logger is
     * compared by reference, so a config built per call would differ each time (D70). A worker started in a fresh
     * process reaches them through [container], so it sees the config the UI used.
     */
    val llamaBroConfig: LlamaBroConfig = LlamaBroConfig.Default

    /** The Hub's address; the test application points it at its fake server. */
    protected open val hubEndpoint: String get() = HubConfig.Default.endpoint

    /**
     * WorkManager starts on first use (the startup initializer is removed in the manifest), creating the SDK's download
     * worker on the container's hub (D75). Asked per worker rather than captured: WorkManager reads its configuration
     * once per process, and the test application changes its container per test.
     */
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(ContainerWorkers()).build()

    private inner class ContainerWorkers : WorkerFactory() {
        override fun createWorker(
            appContext: Context,
            workerClassName: String,
            workerParameters: WorkerParameters,
        ): ListenableWorker? = container.workers.createWorker(appContext, workerClassName, workerParameters)
    }

    /**
     * The token is read from the Keystore-backed store before each search, plan or download; the hub sends it only to
     * its own host (F82).
     */
    val hubConfig: HubConfig by lazy {
        HubConfig {
            endpoint = hubEndpoint
            token { container.tokenStore.read() }
            // a debuggable build logs each request (method, URL, status, Range) under the tag LlamaBroHub: the
            // end-to-end run reads a resumed download's 206 from it; a release build logs nothing
            if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
                logger = Logger { level, message -> Log.println(priority(level), "LlamaBroHub", message) }
            }
        }
    }

    private fun priority(level: LogLevel): Int =
        when (level) {
            LogLevel.Debug -> Log.DEBUG
            LogLevel.Info -> Log.INFO
            LogLevel.Warn -> Log.WARN
            LogLevel.Error -> Log.ERROR
        }

    private companion object {
        const val OLD_DOWNLOAD_CHANNEL = "downloads"
        const val OLD_DOWNLOAD_WORKER = "com.suhel.llamabro.demo.models.DownloadWorker"
    }
}
