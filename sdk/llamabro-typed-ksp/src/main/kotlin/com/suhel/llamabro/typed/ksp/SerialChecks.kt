package com.suhel.llamabro.typed.ksp

import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSAnnotation
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

    /**
     * Whether kotlinx.serialization writes [type] with no serializers module, as the arguments class the processor
     * writes needs: a primitive, an unsigned number, a `String`, an enum, a `@Serializable` class, a built-in
     * collection, `Pair` or `Triple` of such types, or a type given a serializer by a type-use
     * `@Serializable(with = …)` ([annotations], or the type's own). A type-use `@Serializable` with no `with` picks no
     * serializer (the arguments class leaves it out), so the type must be serializable by itself.
     */
    fun serializable(type: KSType, annotations: Sequence<KSAnnotation> = emptySequence()): Boolean {
        if (serializerAnnotation(annotations + type.annotations).isNotEmpty()) return true
        val decl = type.classDeclaration() ?: return false
        val own = decl.fqn() in BUILT_IN || decl.classKind == ClassKind.ENUM_CLASS || decl.has(SERIALIZABLE)
        return own &&
            type.arguments.all { argument ->
                val reference = argument.type ?: return@all false
                serializable(reference.resolve(), reference.annotations + argument.annotations)
            }
    }

    /**
     * Whether [type] or a type argument at any depth has its serializer chosen at runtime, so the schema converter
     * can't describe it: one marked `@Contextual` or `@Polymorphic` (which the arguments class the processor writes
     * doesn't copy, so it wouldn't compile either), or an open-polymorphic one with no annotation at all, an interface
     * or a `@Serializable` abstract class that isn't sealed. A type given `@Serializable(with = …)` is not.
     */
    fun runtimeChosen(type: KSType, annotations: Sequence<KSAnnotation> = emptySequence()): Boolean =
        (annotations + type.annotations).any { it.fqnOf() in RUNTIME_CHOSEN } ||
            (serializerAnnotation(annotations + type.annotations).isEmpty() && openPolymorphic(type)) ||
            type.arguments.any { argument ->
                val reference = argument.type ?: return@any false
                runtimeChosen(reference.resolve(), reference.annotations + argument.annotations)
            }

    private fun openPolymorphic(type: KSType): Boolean {
        val decl = type.classDeclaration() ?: return false
        // the built-in collections are interfaces too, with serializers of their own
        if (decl.isSealed() || decl.fqn() in BUILT_IN) return false
        return decl.classKind == ClassKind.INTERFACE ||
            (decl.classKind == ClassKind.CLASS && Modifier.ABSTRACT in decl.modifiers && decl.has(SERIALIZABLE))
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

        /**
         * What kotlinx.serialization serializes with no annotation, [SIMPLE] included: its `initBuiltins()` at the
         * pinned version (1.10.0; `Nothing` left out, as no argument has it) and the collections the plugin knows.
         */
        val BUILT_IN =
            SIMPLE +
                listOf(
                    "UByte",
                    "UShort",
                    "UInt",
                    "ULong",
                    "UByteArray",
                    "UShortArray",
                    "UIntArray",
                    "ULongArray",
                    "Pair",
                    "Triple",
                    "Array",
                    "time.Duration",
                    "time.Instant",
                    "uuid.Uuid",
                ).map { "kotlin.$it" } +
                listOf(
                    "Boolean",
                    "Byte",
                    "Short",
                    "Char",
                    "Int",
                    "Long",
                    "Float",
                    "Double",
                ).map { "kotlin.${it}Array" } +
                listOf(
                    "List",
                    "MutableList",
                    "ArrayList",
                    "Collection",
                    "Set",
                    "MutableSet",
                    "HashSet",
                    "LinkedHashSet",
                    "Map",
                    "MutableMap",
                    "HashMap",
                    "LinkedHashMap",
                    "Map.Entry",
                ).map { "kotlin.collections.$it" }

        /** Type-use annotations whose serializer is found only at runtime, in a serializers module. */
        val RUNTIME_CHOSEN = setOf("kotlinx.serialization.Contextual", "kotlinx.serialization.Polymorphic")
    }
}

private fun KSClassDeclaration.isSealed(): Boolean = Modifier.SEALED in modifiers
