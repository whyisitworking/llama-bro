package com.suhel.llamabro.demo.testing

import android.os.StrictMode
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkManager
import com.suhel.llamabro.LlamaBro
import com.suhel.llamabro.demo.AppContainer
import com.suhel.llamabro.demo.DemoApp
import com.suhel.llamabro.demo.audio.SessionFocus
import com.suhel.llamabro.demo.data.ImageStore
import com.suhel.llamabro.demo.data.TokenStore
import com.suhel.llamabro.demo.models.WorkDownloadScheduler
import com.suhel.llamabro.hub.LlamaBroHub
import java.io.File

/**
 * The application of every instrumented test. Its container is rebuilt from a [TestConfig] by [use], and the main
 * thread runs under StrictMode with `penaltyDeath`: a disk or network call on it fails the test (set the
 * instrumentation argument `strictMode` to `false` to look at a failure without it).
 */
class TestDemoApp : DemoApp() {
    private var config = TestConfig.fresh()
    private var current: AppContainer? = null

    override val container: AppContainer
        get() = synchronized(this) { current ?: build(config).also { current = it } }

    override fun onCreate() {
        if (InstrumentationRegistry.getArguments().getString("strictMode") != "false") {
            // Violations are recorded, and `TestAppRule` fails the test for any except those raised inside the system
            // server (OnePlus's binder proxy reads a file while the accessibility service registers a client): those
            // reach the app through Binder with `com.android.server` frames and aren't ours.
            StrictMode.setThreadPolicy(
                StrictMode.ThreadPolicy
                    .Builder()
                    .detectDiskReads()
                    .detectDiskWrites()
                    .detectNetwork()
                    .penaltyLog()
                    .penaltyListener(Runnable::run) { StrictViolations.record(it) }
                    .build(),
            )
        }
        super.onCreate()
    }

    /**
     * Every instrumented test talks to the fake Hugging Face server that `demo/scripts/check.sh` starts (adb reverse).
     */
    override val hubEndpoint: String get() = "http://127.0.0.1:18190/"

    /** Tests run their own recovery (`container.chats.recoverInterrupted()`), so nothing touches storage at launch. */
    override fun onStartup() = Unit

    /** Replaces the container with one built from [newConfig]; the previous one is closed and its files deleted. */
    fun use(newConfig: TestConfig): AppContainer =
        synchronized(this) {
            release()
            config = newConfig
            container
        }

    /** Closes the current container and deletes the files of its config. */
    fun release() =
        synchronized(this) {
            current?.close()
            current = null
            deleteFiles(config)
        }

    private fun build(c: TestConfig): AppContainer =
        AppContainer(
            app = this,
            llamaBro = c.llamaBro ?: LlamaBro.create(this, llamaBroConfig),
            hub = c.hub ?: LlamaBroHub.create(this, hubConfig),
            streamer = c.streamer,
            scheduler = c.scheduler ?: WorkDownloadScheduler(lazy { WorkManager.getInstance(this) }),
            discoveryOverride = c.discovery,
            asksForNotifications = c.asksForNotifications,
            databaseName = c.databaseName,
            settingsName = c.settingsName,
            clock = c.clock,
            tokenStore = TokenStore(this, File(filesDir, c.tokenFileName)),
            images = ImageStore(File(filesDir, c.imagesDirName)),
            microphone = c.microphone,
            voiceMicrophone = c.voiceMicrophone,
            speech = c.speech,
            voiceSpeech = { rate, _ -> c.speech(rate) },
            voiceFocus = { SessionFocus.None },
            chatLibraryOverride = c.chatLibrary,
        )

    private fun deleteFiles(c: TestConfig) {
        deleteDatabase(c.databaseName)
        File(filesDir, "datastore/${c.settingsName}.preferences_pb").delete()
        File(filesDir, c.tokenFileName).delete()
        File(filesDir, c.imagesDirName).deleteRecursively()
    }
}
