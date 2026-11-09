package com.suhel.llamabro.hub.internal

import android.content.Context
import android.os.storage.StorageManager
import android.system.Os
import android.system.OsConstants
import java.io.File

/** Android's volume-aware space accounting and allocation for the Hub cache. */
internal class AndroidStorage(context: Context) : Storage {
    private val manager: StorageManager = context.getSystemService(StorageManager::class.java)

    override fun allocatableBytes(dir: File): Long = manager.getAllocatableBytes(manager.getUuidForPath(dir))

    override fun allocate(dir: File, bytes: Long) {
        manager.allocateBytes(manager.getUuidForPath(dir), bytes)
    }

    override fun fsyncDir(dir: File) {
        val fd = Os.open(dir.path, OsConstants.O_RDONLY, 0)
        try {
            Os.fsync(fd)
        } finally {
            Os.close(fd)
        }
    }
}
