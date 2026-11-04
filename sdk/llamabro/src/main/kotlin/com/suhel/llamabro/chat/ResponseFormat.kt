package com.suhel.llamabro.chat

public sealed interface ResponseFormat {
    /** Any JSON object. */
    public data object JsonObject : ResponseFormat
    /** Output constrained by a JSON Schema ([schema] is the schema as a string). */
    public data class JsonSchema(public val schema: String, public val name: String? = null) : ResponseFormat
}
