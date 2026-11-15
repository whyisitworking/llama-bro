package com.suhel.llamabro.hub.work

import android.app.Notification
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.ForegroundUpdater
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.ProgressUpdater
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.impl.utils.futures.SettableFuture
import androidx.work.testing.TestListenableWorkerBuilder
import com.suhel.llamabro.DownloadPhase
import com.suhel.llamabro.DownloadProgress
import com.suhel.llamabro.hub.DownloadState
import com.suhel.llamabro.hub.DraftRequest
import com.suhel.llamabro.hub.HfModel
import com.suhel.llamabro.hub.HubException
import com.suhel.llamabro.hub.PlanOptions
import com.suhel.llamabro.hub.testing.FakeLlamaBroHub
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Modifier
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The worker on the JVM through work-testing's [TestListenableWorkerBuilder]. The repo has no Robolectric, so most tests
 * reach the hub through the worker's [ModelDownloads] seam; the public `LlamaBroWorkerFactory(hub)` runs over a real
 * hub built by `FakeLlamaBroHub` (llamabro-hub-testing), which stands in for Android's StorageManager. The notifications
 * are a test double; the default notifications and WorkManager's KEEP are covered by the androidTest.
 */
class DownloadWorkerTest {
    private val model = HfModel("unsloth/Qwen3.5-0.8B-GGUF:Q4_K_M")

    /** One unique work keeps its id across retries, which is what [StalledTries] is keyed by. */
    private val workId = UUID.randomUUID()

    @Test
    fun factoryCreatesTheWorkerWithTheAppsHub() =
        runTest {
            val asked = CopyOnWriteArrayList<String>()
            val downloads =
                ModelDownloads { model, mmproj, draft ->
                    asked += "$model mmproj=$mmproj draft=${draft?.let { "dflash(${it.from})" }}"
                    flowOf(complete())
                }
            val factory = LlamaBroWorkerFactory(downloads, RecordingNotifications(), { MemoryTries() })
            var params: WorkerParameters? = null
            val capturing =
                object : WorkerFactory() {
                    override fun createWorker(
                        appContext: Context,
                        workerClassName: String,
                        workerParameters: WorkerParameters,
                    ) = factory.createWorker(appContext, workerClassName, workerParameters).also {
                        params =
                            workerParameters
                    }
                }
            val worker =
                TestListenableWorkerBuilder
                    .from(TestContext(), DownloadWorker::class.java)
                    .setWorkerFactory(capturing)
                    .setInputData(downloadInput(model, mmproj = false, draft = DraftRequest.dflash()))
                    .build()

            // the reflective fallback would have no hub and fail: success means the factory made it, with its hub
            assertEquals(ListenableWorker.Result.success(), worker.doWork())
            assertEquals(listOf("$model mmproj=false draft=dflash(null)"), asked)
            // anything else is left to the app's other factories (a DelegatingWorkerFactory, Hilt's)
            assertNull(factory.createWorker(TestContext(), "com.example.OtherWorker", params!!))
        }

    /** The public factory over a real hub: the input's options must reach `hub.download` as given (D75). */
    @Test
    fun publicFactoryPassesTheOptionsToTheHub() =
        runBlocking {
            val drafts = HfModel("z-lab/Qwen3.5-0.8B-DFlash-GGUF")
            val fake =
                FakeLlamaBroHub {
                    repo(model.repo) {
                        gguf("Qwen3.5-0.8B-Q4_K_M.gguf")
                        gguf("mmproj-F16.gguf")
                        gguf("dflash-Qwen3.5-0.8B-Q8_0.gguf")
                    }
                    repo(drafts.repo) { gguf("dflash-Qwen3.5-0.8B-Q4_0.gguf") }
                }

            suspend fun download(mmproj: Boolean, draft: DraftRequest?): Set<String> {
                val before = fake.requests.size
                val worker =
                    TestListenableWorkerBuilder
                        .from(TestContext(), DownloadWorker::class.java)
                        .setWorkerFactory(LlamaBroWorkerFactory(fake.hub, RecordingNotifications()))
                        .setInputData(downloadInput(model, mmproj, draft))
                        .setProgressUpdater(ProgressRecorder())
                        .setForegroundUpdater(ForegroundRecorder())
                        .build()
                assertEquals(ListenableWorker.Result.success(), worker.doWork())
                val state =
                    fake.hub.progress(model) {
                        this.mmproj = mmproj
                        this.draft = draft
                    }
                assertEquals(DownloadState.Complete, state.value)
                return fake.requests
                    .drop(before)
                    .filter { it.startsWith("GET ") && "/resolve/" in it }
                    .map { it.substringAfter("/resolve/").substringAfter('/') }
                    .toSet()
            }

            assertEquals(
                setOf("Qwen3.5-0.8B-Q4_K_M.gguf", "dflash-Qwen3.5-0.8B-Q8_0.gguf"),
                download(mmproj = false, draft = DraftRequest.dflash()),
            )
            assertEquals(
                setOf("mmproj-F16.gguf", "dflash-Qwen3.5-0.8B-Q4_0.gguf"), // the model file is there already
                download(mmproj = true, draft = DraftRequest.dflash(drafts)),
            )
        }

    @Test
    fun enqueueIsUniquePerModel() {
        val other = HfModel("unsloth/Qwen3.5-4B-GGUF:Q4_K_M")
        assertEquals(downloadWorkName(model), downloadWorkName(HfModel(model.toString())))
        assertNotEquals(downloadWorkName(model), downloadWorkName(other))
        assertNotEquals(downloadWorkName(model), downloadWorkName(model, mmproj = false))
        assertNotEquals(downloadWorkName(model), downloadWorkName(model, draft = DraftRequest.dflash()))
        assertNotEquals(
            downloadWorkName(model, draft = DraftRequest.dflash()),
            downloadWorkName(model, draft = DraftRequest.dflash(other)),
        )

        val request = downloadRequest(model, mmproj = false, draft = DraftRequest.dflash(other))
        assertEquals(DownloadWorker::class.java.name, request.workSpec.workerClassName)
        assertEquals(NetworkType.CONNECTED, request.workSpec.constraints.requiredNetworkType)
        assertEquals(BackoffPolicy.EXPONENTIAL, request.workSpec.backoffPolicy)
        assertTrue(request.workSpec.expedited)
        val input = DownloadInput.from(request.workSpec.input)!!
        assertEquals(model, input.model)
        assertEquals(false, input.mmproj)
        assertEquals(other, input.draft?.from)
    }

    @Test
    fun workerReportsProgressAndSucceeds() =
        runTest {
            val notifications = RecordingNotifications()
            val progress = ProgressRecorder()
            val foreground = ForegroundRecorder()
            val result =
                worker(
                    { _, _, _ -> flowOf(downloading(10, 100, eta = 9), downloading(60, 100, eta = 4), complete()) },
                    notifications,
                    progress = progress,
                    foreground = foreground,
                    clock = SteppingClock(stepMs = DownloadWorker.PROGRESS_INTERVAL_MS),
                ).doWork()

            assertEquals(ListenableWorker.Result.success(), result)
            assertEquals(
                listOf(10L to 100L, 60L to 100L, 100L to 100L),
                progress.updates.map {
                    it.getLong(DownloadWorkProgress.BYTES_DONE, -1) to it.getLong(DownloadWorkProgress.BYTES_TOTAL, -1)
                },
            )
            assertEquals(listOf(null, 10L, 60L, 100L), notifications.running.map { it.progress?.bytesDone })
            assertTrue(
                foreground.infos.all { it.foregroundServiceType == ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC },
            )
            assertEquals(
                1,
                foreground.infos
                    .map { it.notificationId }
                    .distinct()
                    .size,
            )
            assertEquals(listOf(model.toString()), notifications.finished)
            assertEquals(emptyList<String>(), notifications.failed)
        }

    @Test
    fun workerRetriesOnARetryableError() =
        runTest {
            val notifications = RecordingNotifications()
            val tries = MemoryTries()
            val stalled = ModelDownloads { _, _, _ -> failingWith(networkError(retryable = true)) }

            repeat(DownloadWorker.MAX_STALLED_TRIES - 1) {
                assertEquals(ListenableWorker.Result.retry(), worker(stalled, notifications, tries = tries).doWork())
            }
            assertEquals(emptyList<String>(), notifications.failed) // a retry is not a failure yet
            // the cap counts tries that moved no bytes, whatever WorkManager's own count says
            assertEquals(ListenableWorker.Result.failure(), worker(stalled, notifications, tries = tries).doWork())
            assertEquals(listOf(model.toString()), notifications.failed)
            assertEquals(0, tries.records.size) // forgotten once the work ended

            val permanent = ModelDownloads { _, _, _ -> failingWith(networkError(retryable = false)) }
            assertEquals(ListenableWorker.Result.failure(), worker(permanent, RecordingNotifications()).doWork())
        }

    @Test
    fun aTryThatMovedBytesRetriesWhateverTheAttemptCount() =
        runTest {
            val tries = MemoryTries()
            val stalled = ModelDownloads { _, _, _ -> failingWith(networkError(retryable = true)) }
            var bytes = 0L
            val moving =
                ModelDownloads { _, _, _ ->
                    bytes += 10
                    flow {
                        emit(downloading(bytes, 100))
                        throw networkError(retryable = true)
                    }
                }

            // a long download over flaky Wi-Fi: WorkManager's count passes the cap, each try resumes and moves on
            repeat(2 * DownloadWorker.MAX_STALLED_TRIES) { attempt ->
                assertEquals(
                    ListenableWorker.Result.retry(),
                    worker(moving, tries = tries, runAttemptCount = attempt).doWork(),
                )
            }
            // progress resets the count: the stalled tries after it get the whole allowance again
            repeat(DownloadWorker.MAX_STALLED_TRIES - 1) {
                assertEquals(
                    ListenableWorker.Result.retry(),
                    worker(stalled, tries = tries, runAttemptCount = 50).doWork(),
                )
            }
            assertEquals(
                ListenableWorker.Result.failure(),
                worker(stalled, tries = tries, runAttemptCount = 50).doWork(),
            )
        }

    @Test
    fun workerPacesProgressToAboutASecond() =
        runTest {
            val notifications = RecordingNotifications()
            val progress = ProgressRecorder()
            val clock = SteppingClock(stepMs = 300)
            val result =
                worker(
                    { _, _, _ ->
                        flowOf(
                            DownloadProgress(DownloadPhase.Resolving, 0, 0),
                            downloading(10, 100),
                            downloading(20, 100),
                            downloading(30, 100),
                            downloading(40, 100),
                            downloading(50, 100),
                            downloading(60, 100),
                        )
                    },
                    notifications,
                    progress = progress,
                    clock = clock,
                ).doWork()

            assertEquals(ListenableWorker.Result.success(), result)
            // readings 300 ms apart: phase changes pass (0, 10 at 0.3 s); then 50 at 1.5 s, a second after 10; the held
            // last one at the end (60)
            assertEquals(
                listOf(0L, 10L, 50L, 60L),
                progress.updates.map { it.getLong(DownloadWorkProgress.BYTES_DONE, -1) },
            )
            assertEquals(listOf(null, 0L, 10L, 50L, 60L), notifications.running.map { it.progress?.bytesDone })
        }

    @Test
    fun anUnexpectedErrorEndsWithTheFailedNotification() =
        runTest {
            val notifications = RecordingNotifications()
            val broken = ModelDownloads { _, _, _ -> failingWith(IllegalStateException("bug")) }
            assertEquals(ListenableWorker.Result.failure(), worker(broken, notifications).doWork())
            assertEquals(listOf(model.toString()), notifications.failed)
        }

    @Test
    fun workerDownloadsWhenTheForegroundIsRefused() =
        runTest {
            // Android 12+ refuses a foreground service started from the background: the download goes on without one.
            val refused = ForegroundUpdater { _, _, _ -> throw IllegalStateException("not allowed now") }
            val result =
                worker({ _, _, _ -> flowOf(downloading(1, 2), complete()) }, foreground = refused).doWork()
            assertEquals(ListenableWorker.Result.success(), result)
        }

    @Test
    fun appConstraintsReachTheRequest() {
        val wifiCharging =
            Constraints
                .Builder()
                .setRequiredNetworkType(NetworkType.UNMETERED)
                .setRequiresCharging(true)
                .setRequiresStorageNotLow(true)
                .build()
        val request = downloadRequest(model, mmproj = true, draft = null, constraints = wifiCharging)
        assertEquals(NetworkType.UNMETERED, request.workSpec.constraints.requiredNetworkType)
        assertTrue(request.workSpec.constraints.requiresCharging())
        assertTrue(request.workSpec.constraints.requiresStorageNotLow())
        assertFalse("WorkManager refuses charging on expedited work", request.workSpec.expedited)

        val wifi = Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).build()
        val expedited = downloadRequest(model, mmproj = true, draft = null, constraints = wifi)
        assertEquals(NetworkType.UNMETERED, expedited.workSpec.constraints.requiredNetworkType)
        assertTrue(expedited.workSpec.expedited)
        assertEquals(NetworkType.CONNECTED, defaultDownloadConstraints().requiredNetworkType)
    }

    @Test
    fun inputCarriesEveryPlanOption() {
        fun fields(type: Class<*>) =
            type.declaredFields
                .filterNot { Modifier.isStatic(it.modifiers) }
                .map { it.name }
                .toSet()
        assertEquals(
            "a PlanOptions property DownloadInput doesn't carry is lost in a background download",
            fields(PlanOptions::class.java),
            fields(DownloadInput::class.java) - "model",
        )
    }

    @Test
    fun workerFailsOnBadInputOrWithoutTheFactory() =
        runTest {
            val bad = TestListenableWorkerBuilder.from(TestContext(), DownloadWorker::class.java)
            bad.setWorkerFactory(
                LlamaBroWorkerFactory({ _, _, _ -> flowOf(complete()) }, RecordingNotifications(), { MemoryTries() }),
            )
            bad.setInputData(Data.Builder().putString(DownloadInput.MODEL, "not a repo").build())
            assertEquals(ListenableWorker.Result.failure(), (bad.build() as DownloadWorker).doWork())

            // WorkManager's default factory builds the worker by reflection when the app didn't register ours
            val unregistered =
                TestListenableWorkerBuilder
                    .from(TestContext(), DownloadWorker::class.java)
                    .setInputData(downloadInput(model, mmproj = true, draft = null))
                    .build()
            assertEquals(ListenableWorker.Result.failure(), (unregistered as DownloadWorker).doWork())
        }

    private fun worker(
        downloads: ModelDownloads,
        notifications: DownloadNotifications = RecordingNotifications(),
        runAttemptCount: Int = 1,
        progress: ProgressUpdater = ProgressRecorder(),
        foreground: ForegroundUpdater = ForegroundRecorder(),
        tries: MemoryTries = MemoryTries(),
        clock: () -> Long = { 0L },
    ): DownloadWorker =
        TestListenableWorkerBuilder
            .from(TestContext(), DownloadWorker::class.java)
            .setId(workId)
            .setWorkerFactory(LlamaBroWorkerFactory(downloads, notifications, { tries }, clock))
            .setInputData(downloadInput(model, mmproj = true, draft = null))
            .setRunAttemptCount(runAttemptCount)
            .setProgressUpdater(progress)
            .setForegroundUpdater(foreground)
            .build() as DownloadWorker

    private fun downloadInput(model: HfModel, mmproj: Boolean, draft: DraftRequest?): Data =
        DownloadInput(model, mmproj, draft).toData()

    private fun downloading(done: Long, total: Long, eta: Long? = null) =
        DownloadProgress(DownloadPhase.Downloading, done, total, etaSeconds = eta)

    private fun complete() = DownloadProgress(DownloadPhase.Complete, 100, 100)

    private fun failingWith(error: Throwable): Flow<DownloadProgress> = flow { throw error }

    /** HubException's constructors are internal to the hub; the test reaches one by reflection. */
    private fun networkError(retryable: Boolean): HubException =
        HubException.Network::class.java
            .getDeclaredConstructor(String::class.java, Throwable::class.java, Boolean::class.javaPrimitiveType)
            .apply { isAccessible = true }
            .newInstance("connection refused", null, retryable)
}

/**
 * android.jar's stubs return null for the application context; WorkManager requires one. The public factory keeps its
 * retry record in preferences, here in memory.
 */
private class TestContext : ContextWrapper(null) {
    private val preferences = MemoryPreferences()

    override fun getApplicationContext(): Context = this

    override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = preferences
}

/** The part of [SharedPreferences] the retry record uses: strings, put and removed. */
private class MemoryPreferences : SharedPreferences {
    private val values = ConcurrentHashMap<String, Any?>()

    override fun getAll(): Map<String, *> = values.toMap()

    override fun getString(key: String?, defValue: String?): String? = values[key] as? String ?: defValue

    override fun getStringSet(key: String?, defValues: Set<String>?): Set<String>? = defValues

    override fun getInt(key: String?, defValue: Int): Int = defValue

    override fun getLong(key: String?, defValue: Long): Long = defValue

    override fun getFloat(key: String?, defValue: Float): Float = defValue

    override fun getBoolean(key: String?, defValue: Boolean): Boolean = defValue

    override fun contains(key: String?): Boolean = key != null && values.containsKey(key)

    override fun edit(): SharedPreferences.Editor = Editor()

    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?,
    ) = Unit

    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?,
    ) = Unit

    private inner class Editor : SharedPreferences.Editor {
        private val puts = mutableMapOf<String, String?>()
        private val removes = mutableSetOf<String>()

        override fun putString(key: String, value: String?) = apply { puts[key] = value }

        override fun putStringSet(key: String?, values: Set<String>?) = this

        override fun putInt(key: String?, value: Int) = this

        override fun putLong(key: String?, value: Long) = this

        override fun putFloat(key: String?, value: Float) = this

        override fun putBoolean(key: String?, value: Boolean) = this

        override fun remove(key: String) = apply { removes += key }

        override fun clear() = this

        override fun commit(): Boolean {
            apply()
            return true
        }

        override fun apply() {
            removes.forEach { values.remove(it) }
            puts.forEach { (key, value) -> if (value == null) values.remove(key) else values[key] = value }
        }
    }
}

private class RecordingNotifications : DownloadNotifications() {
    val running = CopyOnWriteArrayList<RunningDownload>()
    val finished = CopyOnWriteArrayList<String>()
    val failed = CopyOnWriteArrayList<String>()

    override fun downloading(context: Context, download: RunningDownload): Notification {
        running += download
        return Notification()
    }

    // Recorded, then null: showing one needs a real NotificationManager.
    override fun finished(context: Context, model: HfModel): Notification? {
        finished += model.toString()
        return null
    }

    override fun failed(context: Context, model: HfModel, error: Exception): Notification? {
        failed += model.toString()
        return null
    }
}

private class ProgressRecorder : ProgressUpdater {
    val updates = CopyOnWriteArrayList<Data>()

    override fun updateProgress(context: Context, id: UUID, data: Data) =
        SettableFuture.create<Void>().apply {
            updates += data
            set(null)
        }
}

private class ForegroundRecorder : ForegroundUpdater {
    val infos = CopyOnWriteArrayList<ForegroundInfo>()

    override fun setForegroundAsync(context: Context, id: UUID, foregroundInfo: ForegroundInfo) =
        SettableFuture.create<Void>().apply {
            infos += foregroundInfo
            set(null)
        }
}

private class MemoryTries : StalledTries {
    val records = mutableMapOf<UUID, StalledTries.Record>()

    override fun read(id: UUID) = records[id] ?: StalledTries.Record(0, 0)

    override fun write(id: UUID, record: StalledTries.Record) {
        records[id] = record
    }

    override fun clear(id: UUID) {
        records.remove(id)
    }
}

/** Each reading is [stepMs] after the last. */
private class SteppingClock(private val stepMs: Long) : () -> Long {
    private var now = 0L

    override fun invoke(): Long = now.also { now += stepMs }
}
