package com.suhel.llamabro.conversation

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

/**
 * A tool call's arguments. Created by the agent loop; the raw string is parsed the way tools/ui's
 * parseToolArguments does (stores/agentic/index.svelte.ts): blank means `{}`, anything else must be JSON.
 * Accessors throw [IllegalArgumentException] for a missing or mistyped field; the loop turns that into an
 * `Error: …` result for the model.
 */
public class JsonArgs internal constructor(public val raw: String) {
    private val obj: JsonObject = if (raw.isBlank()) {
        JsonObject(emptyMap())
    } else {
        Json.parseToJsonElement(raw.trim()) as? JsonObject ?: throw IllegalArgumentException("arguments must be a JSON object")
    }

    public fun string(name: String): String = stringOrNull(name) ?: throw IllegalArgumentException("missing string argument: $name")

    public fun stringOrNull(name: String): String? = (obj[name] as? JsonPrimitive)?.takeIf { it.isString }?.content

    public fun int(name: String): Int = primitive(name).intOrNull ?: throw IllegalArgumentException("argument $name is not an integer")

    public fun double(name: String): Double = primitive(name).doubleOrNull ?: throw IllegalArgumentException("argument $name is not a number")

    public fun boolean(name: String): Boolean = primitive(name).booleanOrNull ?: throw IllegalArgumentException("argument $name is not a boolean")

    private fun primitive(name: String): JsonPrimitive =
        (obj[name] as? JsonPrimitive)?.takeIf { !it.isString } ?: throw IllegalArgumentException("missing argument: $name")
}
