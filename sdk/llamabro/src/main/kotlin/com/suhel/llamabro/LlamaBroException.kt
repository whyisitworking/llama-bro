package com.suhel.llamabro

/**
 * Every error the SDK raises, mapped from llama-server's error types. Apps catch these; only the SDK
 * creates them. [cause] holds the underlying error where there is one.
 */
public sealed class LlamaBroException(message: String, cause: Throwable?) : Exception(message, cause) {
    /** llama-server rejected the request (`invalid_request_error` and the like), or the call was misused. */
    public class InvalidRequest internal constructor(
        message: String,
        cause: Throwable? = null,
        /** The bridge's code for its own refusals (`attachment`), so the SDK needn't read their words. */
        internal val code: String? = null,
    ) : LlamaBroException(message, cause)

    /**
     * The prompt doesn't fit (`exceed_context_size_error`). Compact or shorten, then retry. The counts are
     * llama-server's `n_prompt_tokens` and `n_ctx`, null if it didn't send them.
     */
    public class ContextExceeded internal constructor(
        public val promptTokens: Int?,
        public val contextSize: Int?,
        message: String,
    ) : LlamaBroException(message, null)

    /**
     * An embedding or rerank input is longer than the batch (D65): llama-server reads each such input in one batch
     * and can't split it. [tokens] is the input's length and [limit] the batch size, `ModelConfig.microBatchSize`
     * (512 by default). Shorten or split the text, or load with a larger `microBatchSize` and `batchSize`; the SDK
     * does neither by itself.
     */
    public class InputTooLong internal constructor(public val tokens: Int, public val limit: Int, message: String) :
        LlamaBroException(message, null)

    /** The model or this build can't do it (`not_supported_error`), e.g. embeddings without an embedding model. */
    public class NotSupported internal constructor(message: String) : LlamaBroException(message, null)

    /** The server can't take the request now (`unavailable_error`). */
    public class Unavailable internal constructor(message: String) : LlamaBroException(message, null)

    /** Any other llama-server error, with its HTTP status; or a reply the SDK couldn't read (status 200). */
    public class ServerError internal constructor(
        public val httpStatus: Int,
        message: String,
        cause: Throwable? = null,
    ) : LlamaBroException(message, cause)

    /** The model didn't load; the message is upstream's reason (llama.cpp only logs it, F51). */
    public class ModelLoadFailed internal constructor(public val reason: Reason, message: String, cause: Throwable?) :
        LlamaBroException(message, cause) {
            /** Why a load failed; the message has llama.cpp's own reason. */
            public enum class Reason {
                /** The model or projector file is missing or can't be read. */
                FileNotFound,

                /**
                 * Not enough free memory for the model at the smallest automatic context (4096 tokens), on any device.
                 */
                InsufficientMemory,

                /** Anything else: a damaged or unsupported file, too little memory, a bad setting. See the message. */
                Other,
            }
        }

    /**
     * `DevicePolicy.Only([kind])` couldn't be met: no such device in this app or on this phone, or it failed to load
     * the model. The message has each reason; [cause] is the last load error, if any.
     */
    public class DeviceUnavailable internal constructor(
        public val kind: DeviceKind,
        message: String,
        cause: Throwable?,
    ) : LlamaBroException(message, cause)

    /** The model was closed, before or during the call. */
    public class ModelClosed internal constructor(cause: Throwable? = null) :
        LlamaBroException("model is closed", cause)

    /** `Conversation.compact()` ended at the output limit; the compaction was rolled back. */
    public class CompactionTruncated internal constructor() : LlamaBroException("summary hit the output limit", null)

    /** Another send, resume, regenerate or compaction is running on this conversation (`Conversation.isBusy`). */
    public class Busy internal constructor() :
        LlamaBroException("another send, resume, regenerate or compact is running on this conversation", null)
}
