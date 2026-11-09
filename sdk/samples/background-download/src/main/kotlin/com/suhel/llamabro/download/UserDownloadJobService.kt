package com.suhel.llamabro.download

import android.app.job.JobParameters
import android.app.job.JobService
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import com.suhel.llamabro.hub.HfModel
import com.suhel.llamabro.hub.HubException
import com.suhel.llamabro.hub.LlamaBroHub
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/** The Android 14+ user-initiated data transfer job: the same Hub download, owned by JobScheduler. */
@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
class UserDownloadJobService : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val running = java.util.concurrent.ConcurrentHashMap<Int, Job>() // one per model's job

    override fun onStartJob(params: JobParameters): Boolean {
        val ref = params.extras.getString("ref") ?: return false
        val model = try { HfModel.parse(ref) } catch (_: IllegalArgumentException) { return false }
        // The system stops a user-initiated job that hasn't posted its notification within a few seconds.
        val id = params.jobId // also the notification's id, so two models' jobs keep their own
        setNotification(params, id, DownloadNotifications.preparing(applicationContext, ref, userInitiatedJob = true),
            JOB_END_NOTIFICATION_POLICY_DETACH)
        running[id] = scope.launch {
            val reschedule = try {
                LlamaBroHub.getInstance(applicationContext).download(model).collect { progress ->
                    setNotification(params, id,
                        DownloadNotifications.build(applicationContext, ref, progress, userInitiatedJob = true),
                        JOB_END_NOTIFICATION_POLICY_DETACH)
                }
                false
            } catch (cancelled: CancellationException) {
                throw cancelled // onStopJob: the system reschedules
            } catch (error: HubException) {
                error.retryable
            } catch (error: Exception) {
                Log.e("UserDownloadJob", "download of $ref failed", error)
                false
            }
            running.remove(id)
            jobFinished(params, reschedule)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        running.remove(params.jobId)?.cancel()
        return true
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        /** One job per model, so scheduling a second model doesn't replace the first; above WorkManager's ids. */
        fun jobId(ref: String): Int =
            DownloadApplication.WORK_MANAGER_MAX_JOB_ID + 1 + (ref.hashCode() and 0x7fffffff) % 1_000_000
    }
}
