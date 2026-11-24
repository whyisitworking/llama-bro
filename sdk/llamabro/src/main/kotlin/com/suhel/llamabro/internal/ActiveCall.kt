package com.suhel.llamabro.internal

import com.suhel.llamabro.LlamaBroException
import com.suhel.llamabro.spi.EngineRequest
import com.suhel.llamabro.spi.NativeError
import com.suhel.llamabro.spi.RequestSink
import kotlinx.coroutines.channels.ProducerScope
import java.io.ByteArrayOutputStream

/**
 * One native request's state, from `Model`'s request flow to the native callbacks: it turns the response's chunks into
 * the flow's items ([sink]) and owns the native handle's lifetime. It exists apart from `Model` because the handle's
 * rules are subtle: the handle may arrive after the first callbacks, and free() must follow the terminal event, while
 * cancel() must never touch a freed request, so all three go through [lock].
 */
internal class ActiveCall<T>(val scope: ProducerScope<T>, private val chunks: Chunks<T>) : RealtimeLink {
    private val lock = Any()
    private var handle: EngineRequest? = null
    private var terminal = false
    private var cancelRequested = false

    /** Client events sent before the handle arrived, sent in order once it has. */
    private val early = mutableListOf<ByteArray>()

    fun setHandle(h: EngineRequest) {
        synchronized(lock) {
            handle = h
            if (terminal) {
                h.free()
            } else if (cancelRequested) {
                h.cancel()
            } else {
                early.forEach { h.send(it) }
            }
            early.clear()
        }
    }

    override fun send(message: String): Boolean =
        synchronized(lock) {
            if (terminal || cancelRequested) return false
            val bytes = message.toByteArray(Charsets.UTF_8)
            val h = handle
            if (h == null) {
                early += bytes
                true
            } else {
                h.send(bytes) == NativeError.OK
            }
        }

    fun cancelIfRunning() {
        synchronized(lock) {
            if (terminal) return
            val h = handle
            if (h == null) cancelRequested = true else h.cancel()
        }
    }

    private fun onTerminal() {
        synchronized(lock) {
            if (terminal) return
            terminal = true
            handle?.free()
        }
    }

    /** The native callbacks for this call: the response's chunks become the flow's items as [mode] says. */
    fun sink(mode: BodyMode): RequestSink =
        object : RequestSink {
            private val parser = SseParser()
            private val whole = ByteArrayOutputStream()
            private var failed = false

            override fun onChunk(httpStatus: Int, data: ByteArray) {
                if (failed) return
                if (httpStatus >= HttpStatus.ERROR_FROM) {
                    fail(ErrorMapping.fromBody(httpStatus, String(data, Charsets.UTF_8)))
                } else if (mode == BodyMode.Each) {
                    scope.trySend(chunks.of(data))
                } else if (mode == BodyMode.Whole) {
                    whole.write(data)
                } else {
                    for (payload in parser.feed(data)) {
                        when {
                            payload == "[DONE]" -> {
                                Unit
                            }

                            // the flow completes at the terminal event
                            ErrorMapping.isErrorPayload(payload) -> {
                                fail(ErrorMapping.fromBody(httpStatus, payload))
                                return
                            }

                            else -> {
                                scope.trySend(chunks.of(payload))
                            }
                        }
                    }
                }
            }

            override fun onDone() {
                if (mode == BodyMode.Whole && !failed &&
                    whole.size() > 0
                ) {
                    scope.trySend(chunks.of(whole.toByteArray()))
                }
                onTerminal()
                scope.close()
            }

            override fun onCancelled() {
                onTerminal()
                scope.close()
            }

            private fun fail(e: LlamaBroException) {
                failed = true
                scope.close(e)
            }
        }
}

/** How a response's chunks become the flow's strings. */
internal enum class BodyMode {
    /** The whole body, once, at the end. */
    Whole,

    /** Each SSE `data:` payload until `[DONE]`. */
    Sse,

    /** Each chunk as it comes: a realtime conversation's events (D97), or speech's audio (D139). */
    Each,
}

/** What a response's chunks become: text, or bytes for a binary body such as speech (D139). */
internal sealed interface Chunks<T> {
    fun of(bytes: ByteArray): T

    fun of(text: String): T

    data object Text : Chunks<String> {
        override fun of(bytes: ByteArray): String = bytes.decodeToString()

        override fun of(text: String): String = text
    }

    data object Bytes : Chunks<ByteArray> {
        override fun of(bytes: ByteArray): ByteArray = bytes

        override fun of(text: String): ByteArray = text.encodeToByteArray()
    }
}
