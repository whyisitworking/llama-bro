package com.suhel.llamabro.internal

import com.suhel.llamabro.InternalLlamaBroApi
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer

/**
 * The native layer as the Kotlin API sees it: [JniEngine] in production, a fake in tests. Public only for
 * `llamabro-testing` (D27); apps never implement it.
 */
@InternalLlamaBroApi
public interface Engine {
    /** null = ok, else the error JSON. */
    public fun init(configJson: String): String?

    public fun setLog(listener: ((level: Int, text: String) -> Unit)?)

    /** `lb_runtime_devices` JSON. */
    public fun devices(): String

    /** `lb_runtime_backends` JSON. The default is the CPU alone, loaded, for engines with no other backend. */
    public fun backends(): String = """[{"name":"cpu","loaded":true,"error":null,"devices":["CPU"]}]"""

    /** `lb_fit_estimate` JSON. Fakes have no memory measurement by default. */
    public fun fitEstimate(configJson: String): String =
        """{"fits":null,"n_ctx":null,"n_ctx_min":4096,"used_min_mib":0,"used_max_mib":null,"budget_mib":null}"""

    /**
     * `lb_model_load`. A real engine throws on failure. [onProgress] answers whether to go on: `false` stops the load
     * at that tick, and the engine frees what it read and throws [NativeError] with [NativeError.CANCELLED] (D67).
     */
    public fun load(configJson: String, onProgress: (Float) -> Boolean): EngineModel

    /**
     * `lb_read_fd`: the whole file behind [fd], read from offset 0, and [fd] left open with its offset unchanged (D84:
     * a descriptor in chat content is read once into bytes). Throws [NativeError] when it can't be read. The default,
     * for fakes on a JVM, reads it through `/dev/fd` with positional reads, as the native side uses `pread`.
     */
    public fun readFd(fd: Int): ByteArray =
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
}

@InternalLlamaBroApi
public interface EngineModel {
    /** `lb_model_info` JSON. */
    public val infoJson: String

    /**
     * `lb_model_request_with` (`lb_model_request` when [attachments] is empty): [endpoint] is the C API's name for the
     * route (`llamabro.h`). One method with no overloads, so a wrapper delegating with `by` can't miss one.
     * [attachments] go beside the body (D84): a transcription's `file`, or, on the chat routes, the file behind each
     * `lb-attachment:<name>` placeholder in a content part's `image_url.url` or `input_audio.data`, which the native
     * side writes into the body as a data URL. They are read before this returns, so a descriptor may be closed and
     * bytes reused once it has. Callbacks may arrive before this returns. A real engine throws if the request couldn't
     * start.
     */
    public fun request(
        endpoint: String,
        paramsJson: String?,
        body: ByteArray,
        attachments: List<EngineAttachment>,
        sink: RequestSink,
    ): EngineRequest

    /** Cancels every request, waits for their terminal events, frees the model. */
    public fun close()
}

/**
 * A file sent beside a request (`lb_attachment`, D84): [name] is the placeholder's (`"0"` for `lb-attachment:0`) or the
 * form field (`"file"` for a transcription), [mime] its type (`audio/wav`, `image/png`), and the bytes come from one
 * source: a path or a descriptor, read natively, or bytes, copied across JNI once.
 */
@InternalLlamaBroApi
public sealed class EngineAttachment {
    public abstract val name: String
    public abstract val mime: String

    /** A file the native side opens and reads. */
    public class Path(override val name: String, override val mime: String, public val path: String) :
        EngineAttachment()

    /** An open descriptor the native side reads from offset 0 and leaves open: the caller closes it. */
    public class Fd(override val name: String, override val mime: String, public val fd: Int) : EngineAttachment()

    /** Bytes already in memory. */
    public class Bytes(override val name: String, override val mime: String, public val bytes: ByteArray) :
        EngineAttachment()
}

@InternalLlamaBroApi
public interface EngineRequest {
    public fun cancel()

    /**
     * `lb_request_send`: one client event of a realtime request (D97), copied and queued in order. Answers the
     * `lb_status`: [SEND_OK], [SEND_NOT_REALTIME] for another endpoint's request, or [SEND_CLOSED] once the
     * conversation has closed. Never after [free].
     */
    public fun send(message: ByteArray): Int = SEND_NOT_REALTIME

    /** Only after the terminal event. */
    public fun free()
}

/** `LB_OK` from [EngineRequest.send]. */
public const val SEND_OK: Int = 0

/** `LB_ERR_INVALID_ARG` from [EngineRequest.send]: not a realtime request. */
public const val SEND_NOT_REALTIME: Int = 1

/** `LB_ERR_CANCELLED` from [EngineRequest.send]: the conversation has closed. */
public const val SEND_CLOSED: Int = 6

/** A request's events: chunks of the HTTP body with its status, then exactly one of [onDone] or [onCancelled]. */
@InternalLlamaBroApi
public interface RequestSink {
    public fun onChunk(httpStatus: Int, data: ByteArray)

    public fun onDone()

    public fun onCancelled()
}

/** The client's side of a realtime conversation (D97): [send] answers false once it has closed. */
internal interface RealtimeLink {
    fun send(message: String): Boolean
}
