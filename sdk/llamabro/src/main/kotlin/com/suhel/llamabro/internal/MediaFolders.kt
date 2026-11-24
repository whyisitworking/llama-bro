package com.suhel.llamabro.internal

import java.io.File
import kotlin.random.Random

/**
 * Owns the media folder llama-server reads request attachments from: `noBackupFilesDir/llamabro/media/`, shared by
 * every process of the app, with one `<pid>-<random>/` folder per process (D87, F157). The bridge links a request's
 * path attachments into [dir] while the request runs. Another process of the app may be running requests from its own
 * folder, so [prepare] removes only the folders of processes that ended; the random part tells this process from an
 * ended one that had the same pid.
 */
internal class MediaFolders(
    noBackupDir: File,
    private val processId: Int,
    /** Whether a process runs: an app sees `/proc/<pid>` of its own processes, which are all that use the folder. */
    private val processAlive: (Int) -> Boolean,
) {
    private val root = File(noBackupDir, "llamabro/media")

    /** This process's folder, llama-server's media root. */
    val dir: File =
        File(root, "$processId-${Random.nextLong().toULong().toString(HEX).padStart(HEX_TOKEN_LENGTH, '0')}")

    /**
     * Removes the links a crash left in the root: the folders of processes that ended (a dead pid, or [processId] from
     * an earlier process), and anything else that isn't a process's folder; then creates [dir]. A running process's
     * folder is left alone.
     */
    fun prepare() {
        root.listFiles()?.forEach { f ->
            val owner = f.name.substringBefore('-').toIntOrNull()
            val ended = owner == null || !f.isDirectory || owner == processId || !processAlive(owner)
            if (f != dir && ended) f.deleteRecursively()
        }
        dir.mkdirs()
    }

    private companion object {
        const val HEX = 16
        const val HEX_TOKEN_LENGTH = 16
    }
}
