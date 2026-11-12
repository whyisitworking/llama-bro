package com.suhel.llamabro.demo

import android.app.Application
import android.content.pm.ApplicationInfo
import android.util.Log
import androidx.work.Configuration
import com.suhel.llamabro.LlamaBro
import com.suhel.llamabro.LlamaBroConfig
import com.suhel.llamabro.LogLevel
import com.suhel.llamabro.Logger
import com.suhel.llamabro.hub.HubConfig
import com.suhel.llamabro.hub.LlamaBroHub
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Open so the test application can supply its own container (androidTest `TestDemoApp`). */
open class DemoApp :
    Application(),
    LlamaBro.ConfigProvider,
    LlamaBroHub.ConfigProvider,
    Configuration.Provider {
    /** Created on first use, on whichever thread asks; building it runs no native code. */
    open val container: AppContainer by lazy { ContainerFactory.create(this) }

    override fun onCreate() {
        super.onCreate()
        onStartup()
    }

    /** A reply left `Streaming` by a dead process becomes `Stopped` (Review focus 2), off the main thread. */
    protected open fun onStartup() {
        ContainerFactory.onStart(this)
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                container.chats.recoverInterrupted()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("DemoApp", "could not recover interrupted replies: ${e.javaClass.simpleName}")
            }
        }
    }

    override val llamaBroConfig: LlamaBroConfig get() = LlamaBroConfig.Default

    /** The Hub's address; the test application points it at its fake server. */
    protected open val hubEndpoint: String get() = HubConfig.Default.endpoint

    /** WorkManager starts on first use with its defaults (the startup initializer is removed in the manifest). */
    override val workManagerConfiguration: Configuration get() = Configuration.Builder().build()

    /** The token is read from the Keystore-backed store before each plan or download, and goes nowhere else. */
    override val llamaBroHubConfig: HubConfig get() =
        HubConfig {
            endpoint = hubEndpoint
            token { container.tokenStore.read() }
            // a debuggable build logs each request (method, URL, status, Range) under the tag LlamaBroHub: the
            // end-to-end run reads a resumed download's 206 from it; a release build logs nothing
            if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
                logger = Logger { level, message -> Log.println(priority(level), "LlamaBroHub", message) }
            }
        }

    private fun priority(level: LogLevel): Int =
        when (level) {
            LogLevel.Debug -> Log.DEBUG
            LogLevel.Info -> Log.INFO
            LogLevel.Warn -> Log.WARN
            LogLevel.Error -> Log.ERROR
        }
}
