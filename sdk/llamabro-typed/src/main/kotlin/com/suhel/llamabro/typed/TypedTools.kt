// typed tools register through core's raw registration seam, toolForCall (D61)
@file:OptIn(InternalLlamaBroApi::class, ExperimentalSerializationApi::class)

package com.suhel.llamabro.typed

import com.suhel.llamabro.InternalLlamaBroApi
import com.suhel.llamabro.chat.Tool
import com.suhel.llamabro.chat.ToolCall
import com.suhel.llamabro.conversation.Conversation
import com.suhel.llamabro.conversation.LbToolError
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.MissingFieldException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.serializer
import kotlin.coroutines.cancellation.CancellationException

/** The call a typed tool's handler is answering: its id, the tool's name and the arguments as the model wrote them. */
public class LbToolCallContext(public val callId: String, public val toolName: String, public val rawArguments: String)

/**
 * A tool with typed arguments and result (D61), made by [tool] and registered with [Conversation.Builder.tool] or in
 * a [Toolbox]. The arguments' JSON Schema, sent to the model, comes from [argsSerializer] through [typedSchema] with
 * [json], which also decodes the arguments and encodes the result, every field included (D88).
 */
public class TypedTool<A, R> internal constructor(
    public val name: String,
    public val description: String,
    public val argsSerializer: KSerializer<A>,
    public val resultSerializer: KSerializer<R>,
    public val requiresApproval: Boolean,
    public val json: Json,
    public val handler: suspend LbToolCallContext.(A) -> R,
) {
    internal val schema: String

    /** [json], writing fields at their default too: the model reads the result and can't know a class's defaults. */
    private val resultJson = Json(json) { encodeDefaults = true }

    init {
        require(NAME.matches(name)) { "tool name '$name' must match ${NAME.pattern}" }
        require(description.isNotBlank()) { "tool $name needs a description: the model chooses tools by it" }
        schema = typedSchema(argsSerializer, json)
        val type = (json.parseToJsonElement(schema) as? JsonObject)?.get("type")
        require(type == JsonPrimitive("object")) {
            "tool $name: the arguments must be an object (a non-null class), " +
                "not ${argsSerializer.descriptor.serialName}"
        }
    }

    /**
     * Decodes the arguments (blank means `{}`, as for raw tools), runs the handler and encodes its result: a
     * `String` as is, `Unit` as `Done` (D81), anything else as JSON. Arguments that don't decode, or whose class's
     * `init` check fails, throw [LbToolError]. Only kotlinx-serialization's own decode errors give the model their
     * reason (`Error: invalid arguments: <reason>`); any other exception thrown while decoding gives
     * `Error: invalid arguments` and is kept as the cause (D62). An [LbToolError] thrown by an `init` passes through
     * with its message.
     */
    internal suspend fun run(call: ToolCall): String {
        val raw = call.argumentsJson
        val args =
            try {
                json.decodeFromString(argsSerializer, raw.ifBlank { "{}" })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                throw decodeFailure(e)
            }
        val result = LbToolCallContext(call.id, call.name, raw).handler(args)
        // a nullable String or Unit counts as one: a null String is sent as `null`, a null Unit as Done
        return when (resultSerializer.descriptor.serialName.removeSuffix("?")) {
            "kotlin.String" -> (result as String?) ?: "null"
            "kotlin.Unit" -> DONE
            else -> resultJson.encodeToString(resultSerializer, result)
        }
    }
}

private val NAME = Regex("^[a-zA-Z0-9_-]{1,64}$")

/** What the model gets from a tool that returns `Unit` (D81). */
private const val DONE = "Done"

// only kotlinx's own decode errors give the model their reason; an app's `require` or a custom serializer's exception
// says nothing of why, since only an LbToolError's message reaches the model (D62). An LbToolError from an `init` is
// the app explaining, so it passes as it is.
private fun decodeFailure(e: Exception): Exception =
    when (e) {
        is SerializationException -> LbToolError("invalid arguments: ${reason(e)}", e)
        is LbToolError -> e
        else -> LbToolError("invalid arguments", e)
    }

// kotlinx appends a "JSON input: ..." excerpt and advice for the developer on later lines; the model gets the first
// line, as core's JsonArgs does. A missing field's message names the Kotlin class, which says nothing to the model, so
// it is told only which field, and where when not at the top.
private fun reason(e: SerializationException): String = if (e is MissingFieldException) missing(e) else firstLine(e)

private fun missing(e: MissingFieldException): String {
    val names = e.missingFields.joinToString(", ") { "'$it'" }
    val path =
        e.message
            ?.substringAfter(" at path: ", "")
            ?.lineSequence()
            ?.first()
            ?.trim()
            .orEmpty()
    val where = if (path.isEmpty() || path == "$") "" else " at $path"
    return (if (e.missingFields.size == 1) "missing field " else "missing fields ") + names + where
}

private fun firstLine(e: SerializationException): String =
    e.message
        ?.substringBefore("JSON input")
        ?.lineSequence()
        ?.firstOrNull()
        ?.trim()
        ?.ifEmpty { null } ?: "they don't match the schema"

/**
 * A typed tool (D61): [handler] gets the decoded arguments, with the call's [LbToolCallContext] as its receiver, and
 * captures whatever the app injected. It runs where the agent loop calls it, so a blocking handler switches to
 * `Dispatchers.IO` itself. What it throws decides what the model sees, as for a raw tool (D62): an `LbToolError`
 * sends `Error: <message>`, an `LbToolAbort` ends the turn, any other exception sends `Error: the tool failed`.
 *
 * @param args the arguments' serializer; their schema must be an object. Arguments that don't decode send
 *   `Error: invalid arguments: <reason>` and the loop goes on, the reason being kotlinx-serialization's (`missing
 *   field 'days'`, bad JSON, a custom serializer's own `SerializationException`). Any other exception thrown while
 *   decoding, such as a `require` or `check` in the arguments class's `init`, sends `Error: invalid arguments` without
 *   its message (D62),
 *   and is the cause of the `LbToolError`. To explain to the model, throw an [LbToolError] from the `init`: its
 *   message is sent.
 * @param result the result's serializer: a `String` is sent as is, `Unit` as `Done` (D81), anything else as JSON
 *   with every field, also those at their default (D88). A `String?` sends `null` when null; a `Unit?` sends `Done`.
 *   No schema for the result reaches the model (F139), so say what it means in [description].
 * @param requiresApproval a call pauses the turn until `approve` or `deny` (D62).
 * @param json decodes the arguments and encodes the result; its settings shape the schema too.
 * @throws IllegalArgumentException for a name that doesn't match `^[a-zA-Z0-9_-]{1,64}$`, a blank description, or
 *   arguments without an object schema (see [typedSchema] for the types it refuses).
 */
public fun <A, R> tool(
    name: String,
    description: String,
    args: KSerializer<A>,
    result: KSerializer<R>,
    requiresApproval: Boolean = false,
    json: Json = Json,
    handler: suspend LbToolCallContext.(A) -> R,
): TypedTool<A, R> = TypedTool(name, description, args, result, requiresApproval, json, handler)

/** A typed tool whose serializers come from [A] and [R]; see the overload that takes them. */
public inline fun <reified A, reified R> tool(
    name: String,
    description: String,
    requiresApproval: Boolean = false,
    json: Json = Json,
    noinline handler: suspend LbToolCallContext.(A) -> R,
): TypedTool<A, R> =
    tool(
        name,
        description,
        json.serializersModule.serializer<A>(),
        json.serializersModule.serializer<R>(),
        requiresApproval,
        json,
        handler,
    )

/** Typed tools registered together; the annotation processor's `asToolbox()` gives one from an app's instance. */
public interface Toolbox {
    public val tools: List<TypedTool<*, *>>
}

/** A [Toolbox] of [tools], in order. */
public fun toolboxOf(vararg tools: TypedTool<*, *>): Toolbox = ListToolbox(tools.toList())

private class ListToolbox(override val tools: List<TypedTool<*, *>>) : Toolbox

/**
 * Registers a typed tool on this conversation; the agent loop runs it as it runs a raw tool with a handler.
 * @throws IllegalArgumentException if a tool of the same name is already registered.
 */
public fun Conversation.Builder.tool(tool: TypedTool<*, *>) {
    toolForCall(Tool.function(tool.name, tool.description, tool.schema), tool.requiresApproval, tool::run)
}

/**
 * Makes a typed tool whose serializers come from [A] and [R] and registers it, so `tool<A, R>(…) { … }` inside
 * `conversation { }` registers the tool instead of only building one. See the top-level [tool] for the parameters.
 * @throws IllegalArgumentException as the top-level [tool] does, or if a tool of the same name is already registered.
 */
public inline fun <reified A, reified R> Conversation.Builder.tool(
    name: String,
    description: String,
    requiresApproval: Boolean = false,
    json: Json = Json,
    noinline handler: suspend LbToolCallContext.(A) -> R,
) {
    // the serializers' overload: only the top-level factory takes them, so this can't resolve to itself
    val args = json.serializersModule.serializer<A>()
    val result = json.serializersModule.serializer<R>()
    tool(tool(name, description, args, result, requiresApproval, json, handler))
}

/** Registers every tool of [toolbox], in order (see [tool]). */
public fun Conversation.Builder.toolbox(toolbox: Toolbox) {
    toolbox.tools.forEach { tool(it) }
}
