@file:JvmName("DownloadWork")

package com.suhel.llamabro.hub.work

import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.Operation
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import com.suhel.llamabro.hub.DraftRequest
import com.suhel.llamabro.hub.HfModel
import java.util.concurrent.TimeUnit

/**
 * Downloads [model] in the background through [com.suhel.llamabro.hub.LlamaBroHub.download], as WorkManager work that
 * survives the app's process. Needs [LlamaBroWorkerFactory] in WorkManager's configuration.
 *
 * The work is unique per model and options ([downloadWorkName]): enqueuing one that is queued or running keeps it
 * ([ExistingWorkPolicy.KEEP]), so a second tap doesn't restart a transfer. It runs when [constraints] are met (by
 * default, any network: the app's call, so pass Wi-Fi only, charging or storage-not-low as it sees fit; with a
 * constraint other than network or storage, such as charging, it isn't expedited, as WorkManager requires), runs
 * expedited where the quota allows, and retries a retryable [com.suhel.llamabro.hub.HubException] with exponential
 * backoff; each try resumes the partial files (F114-F116). Watch it through the hub's
 * [progress][com.suhel.llamabro.hub.LlamaBroHub.progress], or through WorkManager with [downloadWorkName] and
 * [DownloadWorkProgress].
 *
 * [mmproj] and [draft] are [com.suhel.llamabro.hub.PlanOptions]'s, with its defaults. [constraints] don't make the
 * work another one: a queued download keeps the constraints it was enqueued with.
 */
public fun WorkManager.enqueueDownload(
    model: HfModel,
    mmproj: Boolean = true,
    draft: DraftRequest? = null,
    constraints: Constraints = defaultDownloadConstraints(),
): Operation =
    enqueueUniqueWork(
        downloadWorkName(model, mmproj, draft),
        ExistingWorkPolicy.KEEP,
        downloadRequest(model, mmproj, draft, constraints),
    )

/**
 * What [enqueueDownload] waits for unless the app passes its own: any network connection. Public so an app can add to
 * the default rather than restate it, for example
 * `Constraints.Builder(defaultDownloadConstraints()).setRequiresStorageNotLow(true).build()`; a later version that
 * changes the default reaches those apps too.
 */
public fun defaultDownloadConstraints(): Constraints =
    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

/**
 * Cancels the download [enqueueDownload] started with the same arguments. The partial files stay, so enqueuing it
 * again resumes (F114); [com.suhel.llamabro.hub.LlamaBroHub.delete] removes them.
 */
public fun WorkManager.cancelDownload(model: HfModel, mmproj: Boolean = true, draft: DraftRequest? = null): Operation =
    cancelUniqueWork(downloadWorkName(model, mmproj, draft))

/**
 * The unique work name of a download, for [WorkManager.getWorkInfosForUniqueWorkFlow] and its kin. Equal for equal
 * arguments, different for a different model or options.
 */
public fun downloadWorkName(model: HfModel, mmproj: Boolean = true, draft: DraftRequest? = null): String =
    "llamabro-download:$model mmproj=$mmproj draft=${draftKey(draft) ?: "none"}"

/** Keys of the progress a download's [androidx.work.WorkInfo.progress] carries while it runs. */
public object DownloadWorkProgress {
    /** Bytes verified or received so far across the download's files, a `Long`. */
    public const val BYTES_DONE: String = "bytesDone"

    /** Bytes the download's files add up to, a `Long`. */
    public const val BYTES_TOTAL: String = "bytesTotal"
}

internal fun downloadRequest(
    model: HfModel,
    mmproj: Boolean,
    draft: DraftRequest?,
    constraints: Constraints = defaultDownloadConstraints(),
): OneTimeWorkRequest =
    OneTimeWorkRequestBuilder<DownloadWorker>()
        .setInputData(DownloadInput(model, mmproj, draft).toData())
        .setConstraints(constraints)
        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
        .apply { if (expeditable(constraints)) setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST) }
        .build()

/**
 * WorkManager refuses an expedited request with any constraint but network and storage. Content URI triggers, which it
 * refuses too, are left out: a download has no use for them.
 */
private fun expeditable(constraints: Constraints): Boolean =
    !constraints.requiresCharging() && !constraints.requiresDeviceIdle() && !constraints.requiresBatteryNotLow()

/** WorkManager's minimum is 10 s; a dropped connection usually needs longer than that to come back. */
private const val BACKOFF_SECONDS = 30L

/** A draft as a string: "" for the model's own repository, else the reference it comes from; null for none. */
private fun draftKey(draft: DraftRequest?): String? = draft?.let { it.from?.toString().orEmpty() }

/** [draftKey]'s inverse for a draft that is there. */
private fun draftFrom(key: String): HfModel? = if (key.isEmpty()) null else HfModel.parse(key)

/**
 * A download's selection, carried in WorkManager's input data as strings (HfModel's `toString` parses back). It holds
 * each of [com.suhel.llamabro.hub.PlanOptions]'s properties: one added there must be carried here too, which
 * `DownloadWorkerTest.inputCarriesEveryPlanOption` checks.
 */
internal class DownloadInput(val model: HfModel, val mmproj: Boolean, val draft: DraftRequest?) {
    fun toData(): Data =
        Data
            .Builder()
            .putString(MODEL, model.toString())
            .putBoolean(MMPROJ, mmproj)
            .putString(DRAFT, draftKey(draft))
            .build()

    companion object {
        const val MODEL = "model"
        const val MMPROJ = "mmproj"
        const val DRAFT = "draft"

        /** The input [toData] wrote, or null when it is missing or no longer parses. */
        fun from(data: Data): DownloadInput? =
            try {
                val model = HfModel.parse(data.getString(MODEL) ?: return null)
                val draft = data.getString(DRAFT)?.let { from -> DraftRequest.dflash(draftFrom(from)) }
                DownloadInput(model, data.getBoolean(MMPROJ, true), draft)
            } catch (_: IllegalArgumentException) {
                null
            }
    }
}
