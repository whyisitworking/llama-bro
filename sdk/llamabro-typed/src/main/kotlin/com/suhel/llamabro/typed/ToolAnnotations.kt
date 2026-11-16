package com.suhel.llamabro.typed

/**
 * Marks a class, interface or object whose [LbTool] methods are typed tools (D61). With the `llamabro-typed-ksp`
 * processor applied, the build writes `fun X.asToolbox(json: Json = Json): Toolbox` beside it, with the class's own
 * visibility, so the app registers the tools of an instance it built (or a DI container injected):
 * `conversation { toolbox(assistant.asToolbox()) }`. The `@LbTool` methods of `@LbToolbox` supertypes are included,
 * each once.
 *
 * @param prefix put before every tool name exactly as written (`"mail_"`); the whole name must still match
 *   `^[a-zA-Z0-9_-]{1,64}$`.
 */
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS)
public annotation class LbToolbox(val prefix: String = "")

/**
 * Makes a method of an [LbToolbox] a tool. The method takes one `@Serializable` arguments class (where real defaults
 * apply), or parameters that each carry [LbToolParam], of which a nullable `= null` is optional; a parameter of type
 * [LbToolCallContext] is passed the call and left out of the schema. It returns a `String` (sent as is), `Unit`
 * (sent as `Done`), a primitive, a `List` or a `@Serializable` class (sent as JSON). A `suspend` method runs where the
 * agent loop calls it; any other runs on `Dispatchers.IO`. Misuse is a build error.
 *
 * @param description what the tool does and what its result means; required, since the model chooses by it.
 * @param name the tool's name; the method's name in snake_case when empty.
 * @param requiresApproval a call pauses the turn until the app approves or denies it (D62).
 */
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.FUNCTION)
public annotation class LbTool(val description: String, val name: String = "", val requiresApproval: Boolean = false)

/**
 * Makes a parameter of an [LbTool] method one of the tool's arguments, for methods that take separate parameters
 * instead of one `@Serializable` class. The processor writes a `@Serializable` arguments class from them, so the app
 * applies the kotlinx.serialization compiler plugin. Bounds (`@LbRange`, `@LbLength`, `@LbPattern`, `@LbItems`) apply
 * to properties only, so an argument that needs one goes in a `@Serializable` arguments class, as a default other than
 * `null` does.
 *
 * @param description becomes the argument's `@LbDescription`; optional, since a clear name often says it.
 */
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.VALUE_PARAMETER)
public annotation class LbToolParam(val description: String = "")
