package com.suhel.llamabro.hub.internal

import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** A process-wide mutex plus a kernel lock, covering threads and other processes. */
internal object Locks {
    private val mutexes = ConcurrentHashMap<String, Mutex>()

    suspend fun <T> withBlobLock(path: File, action: suspend () -> T): T {
        val mutex = mutexes.computeIfAbsent(path.absolutePath) { Mutex() }
        return mutex.withLock {
            path.parentFile?.mkdirs()
            FileChannel.open(path.toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
                val lock = runInterruptible(Dispatchers.IO) { channel.lock() }
                try { action() } finally { lock.release() }
            }
        }
    }
}
