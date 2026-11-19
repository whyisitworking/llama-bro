package com.suhel.llamabro.testing

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer

/**
 * `lb_read_fd` for the fake engine on a JVM: the whole file behind [fd] through `/dev/fd`, read from offset 0 with
 * positional reads, as the native side uses `pread`, so the caller's offset is kept and the descriptor stays open.
 */
internal fun readDevFd(fd: Int): ByteArray =
    FileInputStream(File("/dev/fd/$fd")).use { stream ->
        val channel = stream.channel
        val out = ByteArrayOutputStream()
        val buffer = ByteBuffer.allocate(64 * 1024)
        var position = 0L
        while (true) {
            buffer.clear()
            val n = channel.read(buffer, position)
            if (n < 0) break
            out.write(buffer.array(), 0, n)
            position += n
        }
        out.toByteArray()
    }
