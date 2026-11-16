package com.suhel.llamabro.typed

import com.suhel.llamabro.Model
import com.suhel.llamabro.Typed
import com.suhel.llamabro.chat.Content
import com.suhel.llamabro.chat.RequestOptions
import com.suhel.llamabro.chat.ResponseFormat
import com.suhel.llamabro.conversation
import com.suhel.llamabro.conversation.Conversation
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer

/**
 * Asks for the reply as a [T] (D63): the core `ask`, held by llama-server's grammar to [serializer]'s schema
 * ([typedSchema]), then decoded with [json]. The model may think first; the schema applies to the reply after it
 * (F143). Fields are written in declaration order, required ones first, so declare a reasoning field before the
 * answer.
 *
 * The result is [Typed.Value] (the decoded value, the model's JSON and its reasoning), [Typed.Incomplete] when the
 * reply isn't a complete answer (its partial JSON), or [Typed.Invalid] when the grammar's JSON doesn't decode (a
 * number past `Int`, a failing `init` check). The JSON is stored as the assistant message, so later turns see it.
 * The conversation's tools stay in the prompt but this turn cannot call them (F143). Engine failures still throw.
 *
 * This call owns the `responseFormat`: one in the conversation's defaults is replaced by the typed call's, while one
 * set in [options] is refused.
 *
 * @throws IllegalArgumentException before any request if [T] has no schema ([typedSchema]) or [options] set a
 *   `responseFormat`.
 */
public suspend fun <T> Conversation.ask(
    text: String,
    vararg attachments: Content,
    serializer: KSerializer<T>,
    json: Json = Json,
    options: RequestOptions.Builder.() -> Unit = {},
): Typed<T> {
    val format = responseFormat(serializer, json)
    val reply =
        ask(text, *attachments) {
            // cleared first, so any value after options() is the app's, whatever the defaults held; core's send
            // applies options when it is called, not when its flow runs, so this throws before any request
            responseFormat = null
            options()
            require(responseFormat == null) {
                "a typed ask sets responseFormat itself; leave it out of options"
            }
            responseFormat = format
        }
    return reply.decode(serializer, json)
}

/** Asks for the reply as a [T]; see the overload that takes a serializer. */
public suspend inline fun <reified T> Conversation.ask(
    text: String,
    vararg attachments: Content,
    json: Json = Json,
    noinline options: RequestOptions.Builder.() -> Unit = {},
): Typed<T> =
    ask(text, *attachments, serializer = json.serializersModule.serializer<T>(), json = json, options = options)

/**
 * One typed reply to [prompt], with no history (D63): a typed [ask] on a fresh `conversation(system)`, which is
 * dropped afterwards. As with [ask], a `responseFormat` set in [options] is refused.
 *
 * @throws IllegalArgumentException before any request if [T] has no schema or [options] set a `responseFormat`.
 */
public suspend fun <T> Model.generate(
    prompt: String,
    vararg attachments: Content,
    serializer: KSerializer<T>,
    system: String? = null,
    json: Json = Json,
    options: RequestOptions.Builder.() -> Unit = {},
): Typed<T> = conversation(system).ask(prompt, *attachments, serializer = serializer, json = json, options = options)

/** One typed reply to [prompt], with no history; see the overload that takes a serializer. */
public suspend inline fun <reified T> Model.generate(
    prompt: String,
    vararg attachments: Content,
    system: String? = null,
    json: Json = Json,
    noinline options: RequestOptions.Builder.() -> Unit = {},
): Typed<T> =
    generate(
        prompt,
        *attachments,
        serializer = json.serializersModule.serializer<T>(),
        system = system,
        json = json,
        options = options,
    )

/** The schema first, so a type without one throws before any request; named after the type's simple name. */
private fun responseFormat(serializer: KSerializer<*>, json: Json): ResponseFormat.JsonSchema =
    ResponseFormat.JsonSchema(
        typedSchema(serializer, json),
        name = serializer.descriptor.serialName.substringAfterLast('.'),
    )

private fun <T> Typed<String>.decode(serializer: KSerializer<T>, json: Json): Typed<T> =
    when (this) {
        is Typed.Value -> {
            try {
                Typed.Value(json.decodeFromString(serializer, this.json), this.json, reasoning)
            } catch (e: IllegalArgumentException) {
                // SerializationException is one; so is a failing require in the type's init block
                Typed.Invalid(this.json, e)
            }
        }

        is Typed.Incomplete -> {
            this
        }

        is Typed.Invalid -> {
            this
        }
    }
