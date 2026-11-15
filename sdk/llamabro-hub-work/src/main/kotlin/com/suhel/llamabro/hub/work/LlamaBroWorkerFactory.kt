package com.suhel.llamabro.hub.work

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import com.suhel.llamabro.DownloadProgress
import com.suhel.llamabro.hub.DraftRequest
import com.suhel.llamabro.hub.HfModel
import com.suhel.llamabro.hub.LlamaBroHub
import kotlinx.coroutines.flow.Flow

/**
 * Creates the worker behind [enqueueDownload] with the app's own [LlamaBroHub], so the download uses the app's
 * config (token provider, logger, cache folder) with no global (D70, D75).
 *
 * Register it in WorkManager's [androidx.work.Configuration], from an `Application` that implements
 * `Configuration.Provider`:
 *
 * ```kotlin
 * override val workManagerConfiguration: Configuration
 *     get() = Configuration.Builder().setWorkerFactory(LlamaBroWorkerFactory(hub)).build()
 * ```
 *
 * With other factories (Hilt's `HiltWorkerFactory`, say), add it to a [androidx.work.DelegatingWorkerFactory]: it
 * creates only its own worker and returns null for every other class.
 *
 * @param hub the hub the downloads run on: the same object the app uses, so its [LlamaBroHub.progress] shows them.
 * @param notifications what the download shows; [DownloadNotifications] itself is the default.
 */
public class LlamaBroWorkerFactory internal constructor(
    private val downloads: ModelDownloads,
    private val notifications: DownloadNotifications,
    private val tries: (Context) -> StalledTries = ::PreferencesStalledTries,
    private val clock: () -> Long = ::monotonicMillis,
) : WorkerFactory() {
    @JvmOverloads
    public constructor(hub: LlamaBroHub, notifications: DownloadNotifications = DownloadNotifications()) :
        this(hubDownloads(hub), notifications)

    override fun createWorker(
        appContext: Context,
        workerClassName: String,
        workerParameters: WorkerParameters,
    ): ListenableWorker? =
        if (workerClassName == DownloadWorker::class.java.name) {
            DownloadWorker(appContext, workerParameters, downloads, notifications, tries(appContext), clock)
        } else {
            null
        }
}

/**
 * The worker's view of the hub: [LlamaBroHub.download] for one selection. A seam so the worker's JVM tests run without
 * a real hub, which needs Android's StorageManager.
 */
internal fun interface ModelDownloads {
    fun download(model: HfModel, mmproj: Boolean, draft: DraftRequest?): Flow<DownloadProgress>
}

private fun hubDownloads(hub: LlamaBroHub) =
    ModelDownloads { model, mmproj, draft ->
        hub.download(model) {
            this.mmproj = mmproj
            this.draft = draft
        }
    }
