package com.suhel.llamabro.hub.internal

import java.io.File

/** Space reservation and directory durability, provided by the platform. */
internal interface Storage {
    fun allocatableBytes(dir: File): Long

    fun allocate(dir: File, bytes: Long)

    fun fsyncDir(dir: File)
}
