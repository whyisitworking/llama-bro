package com.suhel.llamabro.hub.internal

import com.suhel.llamabro.hub.spi.Storage
import java.io.File

/** Host implementation for JVM tests. */
internal class JvmStorage(private val allocatable: Long? = null) : Storage {
    override fun allocatableBytes(dir: File): Long = allocatable ?: dir.usableSpace

    override fun allocate(dir: File, bytes: Long) = Unit

    override fun fsyncDir(dir: File) = Unit
}
