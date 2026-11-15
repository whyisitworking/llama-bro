package com.suhel.llamabro.demo.data.db

/**
 * What llama-server measured for one reply (the SDK's `Timings`), and where it ran: shown in the reply's details sheet.
 * Every field is nullable, so a reply stored before these were kept, or one the server reported nothing for, has none
 * (`MessageEntity.stats` is then null).
 */
data class ReplyStats(
    /** Tokens written for the reply. */
    val replyTokens: Int? = null,
    val tokensPerSecond: Double? = null,
    /** Tokens of the prompt, of which [cachedTokens] were reused from the cache instead of read again. */
    val promptTokens: Int? = null,
    val cachedTokens: Int? = null,
    /** How fast the prompt's new tokens were read. */
    val promptPerSecond: Double? = null,
    /** Speculative decoding (MTP): tokens drafted, and how many of them the model kept. */
    val draftTokens: Int? = null,
    val draftAccepted: Int? = null,
    /** The kind of chip the model ran on: "NPU", "GPU" or "CPU". */
    val device: String? = null,
)
