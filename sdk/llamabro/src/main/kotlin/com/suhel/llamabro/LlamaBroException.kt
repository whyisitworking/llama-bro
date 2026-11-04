package com.suhel.llamabro

/** Every error the SDK raises, mapped from llama-server's error types (design §6.1). */
public sealed class LlamaBroException(message: String) : Exception(message) {
    public class InvalidRequest(message: String) : LlamaBroException(message)

    /** The prompt doesn't fit (`exceed_context_size_error`). Compact or shorten, then retry. */
    public class ContextExceeded(public val promptTokens: Int, public val contextSize: Int) :
        LlamaBroException("request ($promptTokens tokens) exceeds the available context size ($contextSize tokens)")

    public class NotSupported(message: String) : LlamaBroException(message)
    public class Unavailable(message: String) : LlamaBroException(message)
    public class ServerError(public val httpStatus: Int, message: String) : LlamaBroException(message)
    public class ModelLoadFailed(message: String) : LlamaBroException(message)
    public class ModelClosed : LlamaBroException("model is closed")
    /** `Conversation.compact()` ended at the output limit; the compaction was rolled back. */
    public class CompactionTruncated : LlamaBroException("summary hit the output limit")
}
