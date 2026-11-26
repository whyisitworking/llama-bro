package com.suhel.llamabro.rag.store

import okio.FileHandle
import okio.FileSystem
import okio.ForwardingFileSystem
import okio.IOException
import okio.Path

/**
 * A file system that dies at a chosen write: once [crashAt] is set, the first write to a file it names lands only its
 * first half and then throws, and a move onto a file it names throws before moving, as a power cut would leave them.
 * After the crash every write fails, so nothing more reaches the disk.
 */
internal class CrashingFileSystem(delegate: FileSystem) : ForwardingFileSystem(delegate) {
    var crashAt: String? = null
    var crashed: Boolean = false
        private set

    /** The disk is back: writes work again, and nothing crashes until [crashAt] is set again. */
    fun recover() {
        crashed = false
        crashAt = null
    }

    private fun dies(path: Path): Boolean = crashed || path.name == crashAt

    private fun crash(): Nothing {
        crashed = true
        throw IOException("crash")
    }

    override fun openReadWrite(file: Path, mustCreate: Boolean, mustExist: Boolean): FileHandle {
        if (crashed) crash()
        val handle = super.openReadWrite(file, mustCreate, mustExist)
        return if (file.name == crashAt) HalfWrite(handle) else handle
    }

    override fun atomicMove(source: Path, target: Path) {
        if (dies(source) || dies(target)) crash()
        super.atomicMove(source, target)
    }

    override fun delete(path: Path, mustExist: Boolean) {
        if (crashed) crash()
        super.delete(path, mustExist)
    }

    private inner class HalfWrite(private val inner: FileHandle) : FileHandle(readWrite = true) {
        override fun protectedWrite(fileOffset: Long, array: ByteArray, arrayOffset: Int, byteCount: Int) {
            inner.write(fileOffset, array, arrayOffset, byteCount / 2)
            inner.flush()
            crash()
        }

        override fun protectedRead(fileOffset: Long, array: ByteArray, arrayOffset: Int, byteCount: Int): Int =
            inner.read(fileOffset, array, arrayOffset, byteCount)

        override fun protectedFlush() = inner.flush()

        override fun protectedResize(size: Long) = inner.resize(size)

        override fun protectedSize(): Long = inner.size()

        override fun protectedClose() = inner.close()
    }
}
