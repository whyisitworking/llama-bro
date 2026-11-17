package com.suhel.llamabro.demo.models

import android.annotation.SuppressLint
import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.impl.WorkManagerImpl
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.suhel.llamabro.hub.HfModel
import com.suhel.llamabro.hub.work.downloadWorkName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [WorkDownloadScheduler]'s `isScheduled` and its ends ([workEnds]) against WorkManager's own state sequences, on
 * WorkManager's test helpers: a cancel is an end, a retry is not, and a run that had finished before the watch is not.
 * The worker is a stand-in that always asks to retry, so a run never finishes by itself. Each emission is seen
 * ([seen]) before the next step, so no step depends on how quickly Room tells the watch.
 */
@SuppressLint("RestrictedApi") // WorkManagerImpl.setDelegate: hands the app's own WorkManager back after each test
class WorkDownloadSchedulerTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val model = HfModel.parse("someone/Seer-GGUF:Q4_K_M")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var manager: WorkManager
    private lateinit var scheduler: WorkDownloadScheduler

    /** Every state list the watch received, before it reads it. */
    private val seen = Channel<List<WorkInfo>>(Channel.UNLIMITED)

    /** One element per end the watch emitted. */
    private val ends = Channel<Unit>(Channel.UNLIMITED)

    @Before fun start() {
        val config =
            Configuration
                .Builder()
                .setExecutor(SynchronousExecutor())
                .setWorkerFactory(
                    object : WorkerFactory() {
                        override fun createWorker(
                            appContext: Context,
                            workerClassName: String,
                            workerParameters: WorkerParameters,
                        ): ListenableWorker = Retrying(appContext, workerParameters)
                    },
                ).build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, config)
        manager = WorkManager.getInstance(context)
        scheduler = WorkDownloadScheduler(lazyOf(manager))
    }

    @After fun stop() {
        scope.cancel()
        WorkManagerTestInitHelper.closeWorkDatabase()
        // the other tests in this process reach the app's own WorkManager again. setDelegate is WorkManager's internal
        // API (work 2.12.0, gradle/libs.versions.toml): recheck it on every WorkManager upgrade
        WorkManagerImpl.setDelegate(null)
    }

    private class Retrying(context: Context, params: WorkerParameters) : Worker(context, params) {
        override fun doWork(): Result = Result.retry()
    }

    private fun watch() {
        val infos = manager.getWorkInfosForUniqueWorkFlow(downloadWorkName(model)).onEach { seen.send(it) }
        scope.launch { workEnds(infos).collect { ends.send(Unit) } }
    }

    /** Waits until the watch has received a state list matching [condition]; the ones before it are read by then. */
    private suspend fun awaitSeen(condition: (List<WorkInfo>) -> Boolean) {
        withTimeout(WAIT_MILLIS) {
            var infos = seen.receive()
            while (!condition(infos)) infos = seen.receive()
        }
    }

    private fun live(infos: List<WorkInfo>) = infos.any { !it.state.isFinished }

    @Test fun aCancelIsAnEnd() =
        runBlocking {
            watch()
            scheduler.enqueue(model)
            awaitSeen(::live)
            assertTrue(scheduler.isScheduled(model))
            scheduler.cancel(model)
            withTimeout(WAIT_MILLIS) { ends.receive() }
            assertFalse(scheduler.isScheduled(model))
        }

    @Test fun aRetryIsNotAnEnd() =
        runBlocking {
            watch()
            scheduler.enqueue(model)
            awaitSeen(::live)
            val id =
                manager
                    .getWorkInfosForUniqueWork(downloadWorkName(model))
                    .get()
                    .single()
                    .id
            WorkManagerTestInitHelper.getTestDriver(context)!!.setAllConstraintsMet(id)
            // the run asked to retry: queued again, one attempt made
            awaitSeen { infos -> infos.any { it.state == WorkInfo.State.ENQUEUED && it.runAttemptCount >= 1 } }
            assertTrue("no end between tries", ends.tryReceive().isFailure)
            assertTrue(scheduler.isScheduled(model))
            scheduler.cancel(model)
            withTimeout(WAIT_MILLIS) { ends.receive() }
        }

    @Test fun aRunFinishedBeforeTheWatchIsNotAnEnd() =
        runBlocking {
            scheduler.enqueue(model)
            scheduler.cancel(model)
            assertFalse(scheduler.isScheduled(model))
            watch()
            awaitSeen { it.isNotEmpty() && !live(it) }
            // a new run under the same name: by the time the watch sees it, it has read the old one
            scheduler.enqueue(model)
            awaitSeen(::live)
            assertTrue("the old run's end was not reported", ends.tryReceive().isFailure)
            assertTrue(scheduler.isScheduled(model))
            scheduler.cancel(model)
            withTimeout(WAIT_MILLIS) { ends.receive() }
        }

    private companion object {
        const val WAIT_MILLIS = 10_000L
    }
}
