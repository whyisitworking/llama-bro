package com.suhel.llamabro.conversation

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
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
    private val obj: JsonObject =
        if (raw.isBlank()) {
            JsonObject(emptyMap())
        } else {
            Json.parseToJsonElement(raw.trim()) as? JsonObject
                ?: throw IllegalArgumentException("arguments must be a JSON object")
        }

    public fun string(name: String): String =
        stringOrNull(name) ?: throw IllegalArgumentException("missing string argument: $name")

    public fun stringOrNull(name: String): String? = (obj[name] as? JsonPrimitive)?.takeIf { it.isString }?.content

    public fun int(name: String): Int =
        primitive(name).intOrNull ?: throw IllegalArgumentException("argument $name is not an integer")

    public fun double(name: String): Double =
        primitive(name).doubleOrNull ?: throw IllegalArgumentException("argument $name is not a number")

    public fun boolean(name: String): Boolean =
        primitive(name).booleanOrNull ?: throw IllegalArgumentException("argument $name is not a boolean")

    /** A nested object argument. */
    public fun obj(name: String): JsonArgs =
        objOrNull(name) ?: throw IllegalArgumentException("missing object argument: $name")

    public fun objOrNull(name: String): JsonArgs? = (obj[name] as? JsonObject)?.let { JsonArgs(it.toString()) }

    /** An array of strings. */
    public fun stringList(name: String): List<String> =
        array(name).map {
            (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content
                ?: throw IllegalArgumentException("argument $name has a non-string item")
        }

    /** An array of objects. */
    public fun objList(name: String): List<JsonArgs> =
        array(name).map {
            (it as? JsonObject)?.let { o -> JsonArgs(o.toString()) }
                ?: throw IllegalArgumentException("argument $name has a non-object item")
        }

    /** Whether the argument is present (null counts as present). */
    public fun has(name: String): Boolean = name in obj

    private fun array(name: String): JsonArray =
        obj[name] as? JsonArray ?: throw IllegalArgumentException("missing array argument: $name")

    private fun primitive(name: String): JsonPrimitive =
        (obj[name] as? JsonPrimitive)?.takeIf { !it.isString }
            ?: throw IllegalArgumentException("missing argument: $name")

    override fun toString(): String = raw

    public companion object {
        /** Arguments from a JSON object string, as the agent loop parses them; for testing a tool's handler alone. */
        @JvmStatic
        public fun parse(json: String): JsonArgs = JsonArgs(json)
    }
}
