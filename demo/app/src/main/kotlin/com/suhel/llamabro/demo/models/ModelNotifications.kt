package com.suhel.llamabro.demo.models

import android.app.Notification
import android.content.Context
import com.suhel.llamabro.hub.HfModel
import com.suhel.llamabro.hub.work.DownloadNotifications
import com.suhel.llamabro.hub.work.RunningDownload

/**
 * The SDK worker's notifications (`llamabro-hub-work`, D75) in the app's words: a model by its own name on every
 * surface (D50), and "Downloading Qwen3.5 0.8B" over the SDK's size, time left, bar and Cancel.
 */
class ModelNotifications : DownloadNotifications() {
    override fun displayName(model: HfModel): String = Names.of(model)

    override fun downloading(context: Context, download: RunningDownload): Notification =
        downloadingBuilder(context, download).setContentTitle("Downloading ${Names.of(download.model)}").build()
}
