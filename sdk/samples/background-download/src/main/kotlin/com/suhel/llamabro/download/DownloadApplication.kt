package com.suhel.llamabro.download

import android.app.Application
import android.util.Log
import com.suhel.llamabro.LogLevel
import com.suhel.llamabro.Logger
import androidx.work.Configuration
import com.suhel.llamabro.hub.HubConfig
import com.suhel.llamabro.hub.LlamaBroHub

class DownloadApplication : Application(), LlamaBroHub.ConfigProvider, Configuration.Provider {
    /** WorkManager's JobScheduler ids stay below the user-initiated jobs' range (UserDownloadJobService.jobId). */
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setJobSchedulerJobIdRange(0, WORK_MANAGER_MAX_JOB_ID).build()

    override val llamaBroHubConfig: HubConfig
        get() = HubConfig {
            // Debug builds log each request (method, URL without query, status, Range) under the tag LlamaBroHub.
            if (BuildConfig.DEBUG) logger = Logger { level, message -> Log.println(priority(level), "LlamaBroHub", message) }
            endpoint = if (BuildConfig.DEBUG) {
                getSharedPreferences("debug", MODE_PRIVATE).getString("endpoint", null)
                    ?: HubConfig.Default.endpoint
            } else {
                HubConfig.Default.endpoint
            }
        }

    fun rememberDebugEndpoint(value: String?) {
        if (BuildConfig.DEBUG && !value.isNullOrBlank()) {
            getSharedPreferences("debug", MODE_PRIVATE).edit().putString("endpoint", value).apply()
        }
    }

    companion object {
        const val WORK_MANAGER_MAX_JOB_ID = 99_999

        private fun priority(level: LogLevel): Int = when (level) {
            LogLevel.Debug -> Log.DEBUG
            LogLevel.Info -> Log.INFO
            LogLevel.Warn -> Log.WARN
            LogLevel.Error -> Log.ERROR
        }
    }
}
