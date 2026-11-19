package com.suhel.llamabro.conversation

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

/**
 * A tool call's arguments. Created by the agent loop; the raw string is parsed the way tools/ui's
 * parseToolArguments does (stores/agentic/index.svelte.ts): blank means `{}`, anything else must be a JSON object.
 * Parsing and the accessors throw [LbToolError] with `invalid arguments: <reason>` for arguments that don't parse,
 * aren't an object, or lack or mistype a field, so a handler that lets it propagate sends the model
 * `Error: invalid arguments: <reason>` and the model can retry (D62).
 */
public class JsonArgs internal constructor(public val raw: String) {
    private val obj: JsonObject =
        if (raw.isBlank()) {
            JsonObject(emptyMap())
        } else {
            val element =
                try {
                    Json.parseToJsonElement(raw.trim())
                } catch (e: SerializationException) {
                    throw invalid(parseReason(e), e)
                }
            element as? JsonObject ?: throw invalid("arguments must be a JSON object")
        }

    public fun string(name: String): String = stringOrNull(name) ?: throw invalid("missing string argument: $name")

    public fun stringOrNull(name: String): String? = (obj[name] as? JsonPrimitive)?.takeIf { it.isString }?.content

    public fun int(name: String): Int = primitive(name).intOrNull ?: throw invalid("argument $name is not an integer")

    public fun double(name: String): Double =
        primitive(name).doubleOrNull ?: throw invalid("argument $name is not a number")

    public fun boolean(name: String): Boolean =
        primitive(name).booleanOrNull ?: throw invalid("argument $name is not a boolean")

    /** A nested object argument. */
    public fun obj(name: String): JsonArgs = objOrNull(name) ?: throw invalid("missing object argument: $name")

    public fun objOrNull(name: String): JsonArgs? = (obj[name] as? JsonObject)?.let { JsonArgs(it.toString()) }

    /** An array of strings. */
    public fun stringList(name: String): List<String> =
        array(name).map {
            (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content
                ?: throw invalid("argument $name has a non-string item")
        }

    /** An array of objects. */
    public fun objList(name: String): List<JsonArgs> =
        array(name).map {
            (it as? JsonObject)?.let { o -> JsonArgs(o.toString()) }
                ?: throw invalid("argument $name has a non-object item")
        }

    /** Whether the argument is present (null counts as present). */
    public fun has(name: String): Boolean = name in obj

    private fun array(name: String): JsonArray =
        obj[name] as? JsonArray ?: throw invalid("missing array argument: $name")

    private fun primitive(name: String): JsonPrimitive =
        (obj[name] as? JsonPrimitive)?.takeIf { !it.isString }
            ?: throw invalid("missing argument: $name")

    override fun toString(): String = raw

    // kotlinx appends a "JSON input: ..." excerpt of the arguments; only the parser's own message is kept
    private fun parseReason(e: SerializationException): String =
        e.message
            ?.substringBefore("JSON input")
            ?.trim()
            ?.ifEmpty { null } ?: "not JSON"

    private fun invalid(reason: String, cause: Throwable? = null) = LbToolError("invalid arguments: $reason", cause)

    public companion object {
        /**
         * Arguments from a JSON object string, as the agent loop parses them. Meant for tool arguments and for testing
         * a tool's handler alone: the failure reason is built for the model, so don't use it on other JSON.
         */
        public fun parse(json: String): JsonArgs = JsonArgs(json)
    }
}
