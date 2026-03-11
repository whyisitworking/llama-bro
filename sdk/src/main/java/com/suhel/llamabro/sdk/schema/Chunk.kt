package com.suhel.llamabro.sdk.schema

sealed interface Chunk {
    val text: String

    data class Thinking(override val text: String) : Chunk
    data class Content(override val text: String) : Chunk
}
