package com.suhel.llamabro.hub.internal

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.util.concurrent.ConcurrentHashMap

/**
 * A process-wide mutex plus a kernel lock, covering threads and other processes. One writer per file rests on it: a
 * resumed download waits here for a stopped one still letting go of the partial.
 */
internal object Locks {
    private val mutexes = ConcurrentHashMap<String, Mutex>()

    suspend fun <T> withFileLock(path: File, action: suspend () -> T): T {
        val mutex = mutexes.computeIfAbsent(keyOf(path)) { Mutex() }
        if (mutex.isLocked) HubTestHooks.onPhase?.invoke("lockWait")
        return mutex.withLock {
            FileChannel.open(path.toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
                val lock = runInterruptible(Dispatchers.IO) { channel.lock() }
                try {
                    action()
                } finally {
                    lock.release()
                }
            }
        }
    }

    /**
     * The lock file's canonical path, so two spellings of one cache (a link to it) share the mutex: the kernel lock
     * can't order two locks from one process, since the JVM refuses a second lock on a file it already locks (F161).
     * `LlamaBroHub.create` keys its hubs the same way.
     */
    private fun keyOf(path: File): String {
        path.parentFile?.mkdirs()
        return path.canonicalPath
    }
}
