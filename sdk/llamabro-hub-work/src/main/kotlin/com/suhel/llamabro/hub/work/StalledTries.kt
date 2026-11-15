package com.suhel.llamabro.hub.work

import android.content.Context
import androidx.core.content.edit
import java.util.UUID

/**
 * How far a download's earlier tries got, so that only tries that moved no bytes count towards the retry cap.
 * WorkManager's `runAttemptCount` counts every start: a lost network, process death, an OS stop or Android 15's
 * `dataSync` time limit. A long download over flaky Wi-Fi would end in a failure although each try resumed and moved
 * bytes (F114-F116). Kept outside WorkManager because a retry's result carries no data to the next try.
 */
internal interface StalledTries {
    /** The record for the work [id]: the most bytes any try reached, and how many tries since then reached no more. */
    fun read(id: UUID): Record

    fun write(id: UUID, record: Record)

    /** Forgets [id] once its work has ended. */
    fun clear(id: UUID)

    class Record(val bytesReached: Long, val stalled: Int)
}

/** [StalledTries] in a small private preferences file, so it survives the process like the work it describes. */
internal class PreferencesStalledTries(context: Context) : StalledTries {
    private val preferences by lazy { context.getSharedPreferences("llamabro-hub-work-tries", Context.MODE_PRIVATE) }

    override fun read(id: UUID): StalledTries.Record {
        val saved = preferences.getString(id.toString(), null)?.split(':')
        val bytes = saved?.getOrNull(0)?.toLongOrNull()
        val stalled = saved?.getOrNull(1)?.toIntOrNull()
        return if (bytes != null && stalled != null) StalledTries.Record(bytes, stalled) else StalledTries.Record(0, 0)
    }

    override fun write(id: UUID, record: StalledTries.Record) {
        preferences.edit { putString(id.toString(), "${record.bytesReached}:${record.stalled}") }
    }

    override fun clear(id: UUID) {
        preferences.edit { remove(id.toString()) }
    }
}
