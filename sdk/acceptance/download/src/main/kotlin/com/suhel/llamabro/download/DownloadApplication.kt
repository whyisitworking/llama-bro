package com.suhel.llamabro.download

import android.app.Application
import android.util.Log
import androidx.work.Configuration
import com.suhel.llamabro.LogLevel
import com.suhel.llamabro.Logger
import com.suhel.llamabro.hub.HubConfig
import com.suhel.llamabro.hub.LlamaBroHub

class DownloadApplication :
    Application(),
    Configuration.Provider {
    /** WorkManager's JobScheduler ids stay below the user-initiated jobs' range (UserDownloadJobService.jobId). */
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setJobSchedulerJobIdRange(0, WORK_MANAGER_MAX_JOB_ID).build()

    /**
     * One config object for the UI, the worker and the job service (D70): a fresh process starts the Application
     * first, so each reaches the same config, and the logger inside it stays one reference.
     */
    val hubConfig: HubConfig by lazy {
        HubConfig {
            // Debug builds log each request (method, URL without query, status, Range) under the tag LlamaBroHub.
            if (BuildConfig.DEBUG) {
                logger =
                    Logger { level, message -> Log.println(priority(level), "LlamaBroHub", message) }
            }
            endpoint =
                if (BuildConfig.DEBUG) {
                    getSharedPreferences("debug", MODE_PRIVATE).getString("endpoint", null)
                        ?: HubConfig.Default.endpoint
                } else {
                    HubConfig.Default.endpoint
                }
        }
    }

    fun hub(): LlamaBroHub = LlamaBroHub.create(this, hubConfig)

    // Read once with the config (D70): a value saved after that takes effect in the next process.
    fun rememberDebugEndpoint(value: String?) {
        if (BuildConfig.DEBUG && !value.isNullOrBlank()) {
            getSharedPreferences("debug", MODE_PRIVATE).edit().putString("endpoint", value).apply()
        }
    }

    companion object {
        const val WORK_MANAGER_MAX_JOB_ID = 99_999

        private fun priority(level: LogLevel): Int =
            when (level) {
                LogLevel.Debug -> Log.DEBUG
                LogLevel.Info -> Log.INFO
                LogLevel.Warn -> Log.WARN
                LogLevel.Error -> Log.ERROR
            }
    }
}
