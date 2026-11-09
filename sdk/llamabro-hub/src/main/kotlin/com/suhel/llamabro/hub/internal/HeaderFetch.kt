package com.suhel.llamabro.hub.internal

import com.suhel.llamabro.hub.HubException
import com.suhel.llamabro.hub.HubFile
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Fetches only the GGUF header into a stub file, growing the bounded range when metadata needs more bytes. Each round
 * asks only for the bytes not yet held and writes them to disk, so a large header never sits on the Java heap.
 */
internal class HeaderFetch(private val endpoint: String, private val transport: Transport) {
    internal class Header(val dataOffset: Long, val bytesFetched: Long)

    /** Leaves [target] as the header followed by a sparse tail up to the file's full size. */
    suspend fun fetch(file: HubFile, target: File): Header {
        val limit = 256L shl 20
        var requested = 8L shl 20
        var fetched = 0L
        requireNotNull(target.parentFile).mkdirs()
        RandomAccessFile(target, "rw").use { stub ->
            stub.setLength(0)
            var held = 0L
            while (requested <= limit) {
                val count = requested.coerceAtMost(file.sizeBytes)
                if (count <= 0) throw HubException.NotFound("empty GGUF: ${file.path}", emptyList())
                val start = held
                transport.open("GET", resolveUrl(endpoint, file), range = start, rangeEnd = count - 1).use { response ->
                    // A server that ignores Range answers 200 from byte 0: start the stub again.
                    var position = when (response.status) {
                        206 -> start
                        200 -> 0L
                        401, 403 -> throw HubException.Unauthorized(response.status, "access denied: ${file.path}")
                        404 -> throw HubException.NotFound("GGUF not found: ${file.path}", emptyList())
                        else -> throw HubException.Network("GGUF header request returned ${response.status}", null,
                            response.status >= 500)
                    }
                    val input = response.body ?: throw HubException.Network("empty GGUF response: ${file.path}", null, true)
                    stub.seek(position)
                    val buffer = ByteArray(1 shl 20)
                    while (position < count) {
                        coroutineContext.ensureActive()
                        val next = input.read(buffer, 0, minOf(buffer.size.toLong(), count - position).toInt())
                        if (next <= 0) break
                        stub.write(buffer, 0, next)
                        position += next
                        fetched += next
                    }
                    if (position < count) {
                        throw HubException.Network("GGUF header of ${file.path} ended at $position of $count bytes",
                            null, true)
                    }
                    held = position
                }
                val offset = try {
                    stub.channel.map(FileChannel.MapMode.READ_ONLY, 0, held).let(GgufHeader::dataOffset)
                } catch (e: IllegalArgumentException) {
                    throw HubException.NotFound("${file.path} is not a valid GGUF: ${e.message}", emptyList())
                }
                if (offset != null) {
                    stub.setLength(offset) // exactly the header, as T1's stubs
                    stub.setLength(file.sizeBytes)
                    return Header(offset, fetched)
                }
                if (count == file.sizeBytes) {
                    throw HubException.NotFound("${file.path} ends inside its GGUF header (${file.sizeBytes} bytes)",
                        emptyList())
                }
                if (requested == limit) break
                requested *= 2
            }
        }
        throw HubException.NotFound("GGUF header of ${file.path} is larger than 256 MiB", emptyList())
    }
}
