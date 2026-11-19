package com.suhel.llamabro.testing

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.io.File
import java.io.FileDescriptor
import java.io.FileInputStream

/** The fake reads a descriptor as `lb_read_fd` does: whole, from offset 0, the caller's offset kept and the fd open. */
class DevFdReaderTest {
    private fun fdOf(fd: FileDescriptor): Int =
        FileDescriptor::class.java
            .getDeclaredField("fd")
            .apply { isAccessible = true }
            .getInt(fd)

    @Test
    fun readsTheWholeFileAndLeavesItOpen() {
        val file = File.createTempFile("clip", ".bin").apply { writeBytes(ByteArray(200_000) { it.toByte() }) }
        try {
            FileInputStream(file).use { stream ->
                stream.skip(50)
                assertArrayEquals(file.readBytes(), readDevFd(fdOf(stream.fd)))
                assertEquals(50L, stream.channel.position())
                assertNotEquals(-1, stream.read())
            }
        } finally {
            file.delete()
        }
    }
}
