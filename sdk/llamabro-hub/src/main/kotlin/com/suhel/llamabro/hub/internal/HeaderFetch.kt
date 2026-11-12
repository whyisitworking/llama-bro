package com.suhel.llamabro.hub.internal

import com.suhel.llamabro.hub.HubException
import com.suhel.llamabro.hub.HubFile
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import kotlin.coroutines.coroutineContext

/**
 * Fetches only the GGUF header into a stub file, growing the bounded range when metadata needs more bytes. Each round
 * asks only for the bytes not yet held and writes them to disk, so a large header never sits on the Java heap.
 */
internal class HeaderFetch(private val endpoint: String, private val transport: Transport) {
    internal class Header(val dataOffset: Long, val bytesFetched: Long)

    /** What one round brought: the bytes now held in the stub, and how many of them came over the network. */
    private class Round(val held: Long, val fetched: Long)

    /** Leaves [target] as the header followed by a sparse tail up to the file's full size. */
    suspend fun fetch(file: HubFile, target: File): Header {
        var requested = FIRST_REQUEST_BYTES
        var held = 0L
        var fetched = 0L
        requireNotNull(target.parentFile).mkdirs()
        RandomAccessFile(target, "rw").use { stub ->
            stub.setLength(0)
            while (requested <= LIMIT_BYTES) {
                val count = requested.coerceAtMost(file.sizeBytes)
                if (count <= 0) throw HubException.NotFound("empty GGUF: ${file.path}", emptyList())
                val round = fetchRound(file, stub, held, count)
                held = round.held
                fetched += round.fetched
                val offset = dataOffset(file, stub, held)
                if (offset != null) {
                    stub.setLength(offset) // exactly the header, as T1's stubs
                    stub.setLength(file.sizeBytes)
                    return Header(offset, fetched)
                }
                requested *= 2
            }
        }
        throw HubException.NotFound("GGUF header of ${file.path} is larger than 256 MiB", emptyList())
    }

    /** Asks for the bytes from [start] up to [count] and writes them to the stub. */
    private suspend fun fetchRound(file: HubFile, stub: RandomAccessFile, start: Long, count: Long): Round {
        var fetched = 0L
        var position = start
        transport.open("GET", resolveUrl(endpoint, file), range = start, rangeEnd = count - 1).use { response ->
            position = positionFor(response.status, start, file)
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
        }
        if (position < count) {
            throw HubException.Network("GGUF header of ${file.path} ended at $position of $count bytes", null, true)
        }
        return Round(position, fetched)
    }

    /** Where the stub continues: a server that ignores Range answers 200 from byte 0, so the stub starts again. */
    private fun positionFor(status: Int, start: Long, file: HubFile): Long =
        when (status) {
            206 -> start
            200 -> 0L
            401, 403 -> throw HubException.Unauthorized(status, "access denied: ${file.path}")
            404 -> throw HubException.NotFound("GGUF not found: ${file.path}", emptyList())
            else -> throw HubException.Network("GGUF header request returned $status", null, status >= 500)
        }

    /**
     * The offset where the tensor data starts, or null while the header held so far is cut short (and more of the
     * file can still be asked for).
     */
    private fun dataOffset(file: HubFile, stub: RandomAccessFile, held: Long): Long? {
        val offset =
            try {
                stub.channel.map(FileChannel.MapMode.READ_ONLY, 0, held).let(GgufHeader::dataOffset)
            } catch (e: IllegalArgumentException) {
                throw HubException.NotFound("${file.path} is not a valid GGUF: ${e.message}", emptyList())
            }
        if (offset == null && held == file.sizeBytes) {
            throw HubException.NotFound(
                "${file.path} ends inside its GGUF header (${file.sizeBytes} bytes)",
                emptyList(),
            )
        }
        return offset
    }

    private companion object {
        const val FIRST_REQUEST_BYTES = 8L shl 20
        const val LIMIT_BYTES = 256L shl 20
    }
}
