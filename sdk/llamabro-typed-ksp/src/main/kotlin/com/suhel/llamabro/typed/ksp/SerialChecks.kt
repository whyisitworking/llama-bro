package com.suhel.llamabro.typed.ksp

import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSNode
import com.google.devtools.ksp.symbol.KSPropertyDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.Modifier

/** The kotlinx.serialization checks the processor makes before the schema converter would at runtime. */
internal class SerialChecks(private val report: (String, KSNode) -> Unit) {
    /**
     * Refuses a sealed subclass reached from [types] whose serial name is its class's full name: no `@SerialName`, or
     * a dotted one, which the runtime schema can't tell apart from none (D63), so build and runtime agree.
     */
    fun sealedNamesOk(types: List<KSType>, tool: KSFunctionDeclaration): Boolean {
        val seen = mutableSetOf<String>()
        return types.map { visit(it, tool, seen) }.all { it }
    }

    private fun visit(type: KSType, tool: KSFunctionDeclaration, seen: MutableSet<String>): Boolean {
        val inArguments =
            type.arguments
                .mapNotNull { it.type?.resolve() }
                .map { visit(it, tool, seen) }
                .all { it }
        val decl = type.classDeclaration() ?: return inArguments
        val name = decl.fqn()
        if (!seen.add(name) || name.startsWith("kotlin.") || name.startsWith("java.")) return inArguments
        val sealedOk = !decl.isSealed() || leaves(decl).map { leaf(it, tool, seen) }.all { it }
        val propertiesOk = !decl.has(SERIALIZABLE) || serialized(decl).map { visit(it, tool, seen) }.all { it }
        return inArguments && sealedOk && propertiesOk
    }

    private fun leaf(sub: KSClassDeclaration, tool: KSFunctionDeclaration, seen: MutableSet<String>): Boolean {
        val serialName = sub.annotation(SERIAL_NAME)?.arg("value") as? String
        val why =
            "its serial name would be its class's full name, which the model would have to write and a rename would " +
                "break (reached from the arguments of tool ${tool.simpleName.asString()}): give it a short @SerialName"
        val ok =
            when {
                serialName == null -> {
                    false.also { report("the sealed subclass ${sub.fqn()} has no @SerialName; $why", sub) }
                }

                looksQualified(serialName) -> {
                    false.also {
                        report("the sealed subclass ${sub.fqn()} has the dotted @SerialName \"$serialName\"; $why", sub)
                    }
                }

                else -> {
                    true
                }
            }
        return visit(sub.asStarProjectedType(), tool, seen) && ok
    }

    /** The concrete classes a sealed hierarchy serializes, through nested sealed classes. */
    private fun leaves(sealed: KSClassDeclaration): List<KSClassDeclaration> =
        sealed.getSealedSubclasses().toList().flatMap { sub -> if (sub.isSealed()) leaves(sub) else listOf(sub) }

    private fun serialized(decl: KSClassDeclaration): List<KSType> =
        decl
            .getAllProperties()
            .filter(KSPropertyDeclaration::hasBackingField)
            .filterNot { it.has(TRANSIENT) }
            .map { it.type.resolve() }
            .toList()

    /** What a tool may return (D61): a `String`, `Unit`, a primitive, a `List` of these or a `@Serializable` class. */
    fun sendable(type: KSType): Boolean {
        val decl = type.classDeclaration() ?: return false
        return when (decl.fqn()) {
            in SIMPLE -> {
                true
            }

            "kotlin.collections.List" -> {
                type.arguments
                    .singleOrNull()
                    ?.type
                    ?.resolve()
                    ?.let(::sendable) ?: false
            }

            else -> {
                decl.classKind == ClassKind.ENUM_CLASS || decl.has(SERIALIZABLE)
            }
        }
    }

    /** One `@Serializable` class, the whole arguments object: not nullable, sealed, abstract or an interface. */
    fun argumentsObject(type: KSType): Boolean {
        val decl = type.classDeclaration() ?: return false
        return !type.isMarkedNullable &&
            decl.classKind in setOf(ClassKind.CLASS, ClassKind.OBJECT) &&
            !decl.isSealed() &&
            Modifier.ABSTRACT !in decl.modifiers &&
            decl.has(SERIALIZABLE)
    }

    private companion object {
        val SIMPLE =
            setOf("String", "Unit", "Int", "Long", "Short", "Byte", "Double", "Float", "Boolean", "Char")
                .map { "kotlin.$it" }
                .toSet()
    }
}

private fun KSClassDeclaration.isSealed(): Boolean = Modifier.SEALED in modifiers
