package com.suhel.llamabro.hub.internal

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Reads the tensor-data offset without touching GGUF tensor bytes. */
internal object GgufHeader {
    fun dataOffset(bytes: ByteArray): Long? = dataOffset(ByteBuffer.wrap(bytes))

    /** [bytes] from its position to its limit; a mapped file keeps a large header off the Java heap. */
    fun dataOffset(bytes: ByteBuffer): Long? = try {
        val reader = Reader(bytes.slice())
        require(reader.ascii(4) == "GGUF") { "invalid GGUF magic" }
        val version = reader.u32()
        require(version == 2L || version == 3L) { "unsupported GGUF version $version" }
        val tensors = reader.u64()
        val metadata = reader.u64()
        require(tensors <= 10_000_000 && metadata <= 10_000_000) { "invalid GGUF counts" }
        var alignment = 32L
        repeat(metadata.toInt()) {
            val key = reader.string()
            val type = reader.u32()
            if (key == "general.alignment" && type == 4L) {
                alignment = reader.u32()
            } else {
                reader.value(type)
            }
        }
        require(alignment in 1..4096 && alignment and (alignment - 1) == 0L) { "invalid GGUF alignment" }
        repeat(tensors.toInt()) {
            reader.string()
            val dims = reader.u32()
            require(dims <= 16) { "invalid GGUF tensor dimensions" }
            reader.skip(dims * 8L)
            reader.u32()
            reader.u64()
        }
        val offset = ((reader.position + alignment - 1) / alignment) * alignment
        if (offset > reader.size) null else offset
    } catch (_: NeedMore) {
        null
    }

    private class NeedMore : RuntimeException()

    private class Reader(source: ByteBuffer) {
        private val buffer = source.order(ByteOrder.LITTLE_ENDIAN)
        val position: Long get() = buffer.position().toLong()
        val size: Long get() = buffer.limit().toLong()

        fun skip(count: Long) {
            require(count >= 0 && count <= Int.MAX_VALUE) { "invalid GGUF length" }
            if (count > buffer.remaining()) throw NeedMore()
            buffer.position(buffer.position() + count.toInt())
        }

        fun ascii(count: Int): String {
            if (count > buffer.remaining()) throw NeedMore()
            val data = ByteArray(count)
            buffer.get(data)
            return data.toString(Charsets.US_ASCII)
        }

        fun u32(): Long {
            if (buffer.remaining() < 4) throw NeedMore()
            return buffer.int.toLong() and 0xffffffffL
        }

        fun u64(): Long {
            if (buffer.remaining() < 8) throw NeedMore()
            return buffer.long.also { require(it >= 0) { "invalid GGUF length" } }
        }

        fun string(): String {
            val size = u64()
            require(size <= Int.MAX_VALUE) { "invalid GGUF string length" }
            return ascii(size.toInt())
        }

        fun value(type: Long) {
            when (type) {
                0L, 1L, 7L -> skip(1)
                2L, 3L -> skip(2)
                4L, 5L, 6L -> skip(4)
                8L -> skip(u64())
                9L -> {
                    val element = u32()
                    val count = u64()
                    require(element != 9L && count <= 10_000_000) { "invalid GGUF array" }
                    repeat(count.toInt()) { value(element) }
                }
                10L, 11L, 12L -> skip(8)
                else -> throw IllegalArgumentException("invalid GGUF value type $type")
            }
        }
    }
}
