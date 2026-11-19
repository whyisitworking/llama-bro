package com.suhel.llamabro.typed.ksp

import com.google.devtools.ksp.isPrivate
import com.google.devtools.ksp.isProtected
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSNode
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.KSValueParameter
import com.google.devtools.ksp.symbol.Modifier

/** One `@LbTool` method, read and checked; the name is before the toolbox's prefix. */
internal class ToolSpec(
    val function: KSFunctionDeclaration,
    val name: String,
    val description: String,
    val requiresApproval: Boolean,
    val args: ToolArgs,
    val result: String,
    val suspending: Boolean,
    /** The method's arguments as generated code passes them, in order: `args`, `args.city`, `this@tool`. */
    val call: List<String>,
)

internal sealed interface ToolArgs {
    /** One `@Serializable` class taken as is. */
    class Whole(val type: String) : ToolArgs

    /** Parameters with `@LbToolParam`, from which the processor writes an arguments class. */
    class Fields(val fields: List<Field>) : ToolArgs
}

internal class Field(val name: String, val type: String, val description: String, val optional: Boolean)

/** Reads `@LbTool` methods, logging each misuse D61 lists as an error on its symbol. */
internal class ToolReader(private val logger: KSPLogger) {
    private val serial = SerialChecks { message, symbol -> logger.error(message, symbol) }

    /** The tool [f] declares, or null after logging why it can't be one. */
    fun read(f: KSFunctionDeclaration): ToolSpec? {
        val tool = f.annotation(LB_TOOL) ?: return null
        val method = f.simpleName.asString()
        val shapeOk = shapeOk(f, method)
        val description = (tool.arg("description") as? String).orEmpty()
        val described = description.isNotBlank()
        if (!described) error("tool $method needs a description: the model chooses tools by it", f)
        val result = sendableResult(f, method)
        val args = if (shapeOk) args(f, method) else null
        val checked = described && args != null && serial.sealedNamesOk(argumentTypes(f), f)
        if (args == null || result == null || !checked) return null
        return ToolSpec(
            function = f,
            name = (tool.arg("name") as? String)?.ifEmpty { null } ?: snakeCase(method),
            description = description,
            requiresApproval = tool.arg("requiresApproval") as? Boolean ?: false,
            args = args,
            result = result.render(),
            suspending = Modifier.SUSPEND in f.modifiers,
            call = f.parameters.map { callArg(it, args) },
        )
    }

    /** The return type, or null after an error when it can't be sent to the model (D61). */
    private fun sendableResult(f: KSFunctionDeclaration, method: String): KSType? {
        val result = f.returnType?.resolve()
        if (result != null && serial.sendable(result)) return result
        error(
            "tool $method returns ${result?.render()}, which can't be sent to the model: return a String, Unit, " +
                "a primitive, a List or a @Serializable class, and say what it means in the description",
            f,
        )
        return null
    }

    /** What generated code can call: visible, not generic, not an extension, not a tool annotated again. */
    private fun shapeOk(f: KSFunctionDeclaration, method: String): Boolean {
        val overridden = annotatedOverridee(f)
        val problem =
            when {
                f.isPrivate() || f.isProtected() -> {
                    "is not visible to generated code: make it public or internal"
                }

                f.typeParameters.isNotEmpty() -> {
                    "has type parameters: a tool's types must be fixed"
                }

                f.extensionReceiver != null -> {
                    "is an extension: declare it as a plain method"
                }

                overridden != null -> {
                    "overrides a tool and is annotated again: remove @LbTool from the override; $overridden " +
                        "declares the tool, and the override implements it"
                }

                else -> {
                    null
                }
            }
        problem?.let { error("tool $method $it", f) }
        return problem == null
    }

    /** The nearest overridden method that is a tool, as `Owner.method`. */
    private fun annotatedOverridee(f: KSFunctionDeclaration): String? {
        val tool =
            generateSequence(
                f.findOverridee() as? KSFunctionDeclaration,
            ) { it.findOverridee() as? KSFunctionDeclaration }
                .firstOrNull { it.has(LB_TOOL) } ?: return null
        val owner = tool.parentDeclaration?.simpleName?.asString()
        return listOfNotNull(owner, tool.simpleName.asString()).joinToString(".")
    }

    /** One `@Serializable` class without `@LbToolParam`, or every parameter with it (the call's context aside). */
    private fun args(f: KSFunctionDeclaration, method: String): ToolArgs? {
        val values = f.parameters.filterNot(::isContext)
        val lone = values.singleOrNull()
        if (lone != null && !lone.has(LB_TOOL_PARAM)) {
            val type = lone.type.resolve()
            if (serial.argumentsObject(type)) return ToolArgs.Whole(type.render())
            error(
                "parameter ${lone.name?.asString()} is not a @Serializable class: take one non-null @Serializable " +
                    "arguments class, or annotate each parameter of $method with @LbToolParam",
                lone,
            )
            return null
        }
        val unannotated = values.filterNot { it.has(LB_TOOL_PARAM) }
        unannotated.forEach {
            error(
                "parameter ${it.name?.asString()} has no @LbToolParam: annotate every parameter of $method with " +
                    "@LbToolParam, or take one @Serializable arguments class",
                it,
            )
        }
        val fields = values.map(::field)
        return if (unannotated.isEmpty() && fields.all { it != null }) ToolArgs.Fields(fields.filterNotNull()) else null
    }

    /**
     * A separate parameter: optional only as nullable `= null` (D61), and of a type kotlinx.serialization can write,
     * so the arguments class the processor writes compiles. Its type keeps a type-use `@Serializable(with = …)`.
     */
    private fun field(p: KSValueParameter): Field? {
        val name = p.name?.asString() ?: return null
        val type = p.type.resolve()
        val optional = p.hasDefault
        val defaultProblem = if (optional) defaultProblem(name, type.isMarkedNullable, writtenDefault(p)) else null
        val problem =
            when {
                defaultProblem != null -> {
                    defaultProblem
                }

                serial.runtimeChosen(type, p.type.annotations) -> {
                    "parameter $name has type ${type.render()}: a schema can't hold a contextual or open-polymorphic " +
                        "type; use @Serializable(with = …)"
                }

                !serial.serializable(type, p.type.annotations) -> {
                    "parameter $name has type ${type.render()}, which kotlinx.serialization can't serialize: make it " +
                        "@Serializable, give the type @Serializable(with = …), or take one @Serializable arguments class"
                }

                else -> {
                    null
                }
            }
        if (problem != null) {
            error(problem, p)
            return null
        }
        val description = (p.annotation(LB_TOOL_PARAM)?.arg("description") as? String).orEmpty()
        return Field(name, p.type.renderSerialized(), description, optional)
    }

    private fun argumentTypes(f: KSFunctionDeclaration) = f.parameters.filterNot(::isContext).map { it.type.resolve() }

    private fun callArg(p: KSValueParameter, args: ToolArgs): String =
        when {
            isContext(p) -> "this@tool"
            args is ToolArgs.Whole -> "args"
            else -> "args." + id(checkNotNull(p.name).asString())
        }

    private fun isContext(p: KSValueParameter): Boolean =
        p.type
            .resolve()
            .declaration
            .fqn() == LB_CONTEXT

    private fun error(message: String, symbol: KSNode) = logger.error(message, symbol)
}
